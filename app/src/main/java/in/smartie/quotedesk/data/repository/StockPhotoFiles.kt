package `in`.smartie.quotedesk.data.repository

import `in`.smartie.quotedesk.domain.StockPhoto
import `in`.smartie.quotedesk.domain.StockPhotoDisk
import java.io.File
import java.io.IOException

/** Photo bytes that survived a restart, with the revision they were kept at. */
class CachedPhoto(val rev: Double, val bytes: ByteArray)

/**
 * Photo bytes that outlive the process.
 *
 * Small enough to fake, though the tests use the real one against a temporary
 * directory: a cache whose whole purpose is to survive a restart is not worth
 * proving against something that cannot be restarted.
 */
interface StockPhotoFiles {

    /**
     * The cached photo for [documentId] when it is **at least** [atLeastRev],
     * or null.
     *
     * A file found to be older than [atLeastRev] is deleted as it is read:
     * the picture has been replaced, so the bytes are not just unusable, they
     * are waste.
     */
    fun read(documentId: String, atLeastRev: Double): CachedPhoto?

    /** Keeps [bytes], and drops every other revision of the same row. */
    fun put(documentId: String, rev: Double, bytes: ByteArray)

    /** Drops every file for a row: a removal, or a row that lost its photo. */
    fun forget(documentId: String)

    fun clear()
}

/** A cache that does nothing, for a unit test with no disk in play. */
object NoStockPhotoFiles : StockPhotoFiles {
    override fun read(documentId: String, atLeastRev: Double): CachedPhoto? = null
    override fun put(documentId: String, rev: Double, bytes: ByteArray) = Unit
    override fun forget(documentId: String) = Unit
    override fun clear() = Unit
}

/**
 * The real one: one file per photographed row, under an app-private directory
 * that is **not** backed up.
 *
 * *Why this exists at all.* The memory cache dies with the process, and a
 * Firestore `get()` is billed as a read whether or not its own persistence
 * happens to answer it from disk. So without this, every restart re-read every
 * visible photo — against a daily quota shared with the rest of the app. A
 * matching revision is served from here with **no Firestore read of any
 * kind**, which is the claim the usage figures in `docs/N3.1-plan.md` rest on.
 *
 * *What it guarantees.*
 *
 * - **One file per row.** Writing a revision deletes every other revision of
 *   the same row, so a superseded picture is gone rather than merely unused.
 * - **Never a torn file.** Bytes go to a `.part` in the same directory and are
 *   renamed into place, which is atomic within a directory. A process killed
 *   mid-write leaves a partial that is never served and is swept up later.
 * - **Never a stale or damaged picture.** What comes back off disk is checked
 *   — the revision from the name, then the bytes themselves through the same
 *   [StockPhoto.refusal] a fresh photo passes. Anything that fails is deleted
 *   and reported as a miss, so the caller fetches instead of showing it.
 * - **A bound that holds.** Total size is kept under [maxBytes] by evicting
 *   least-recently-used files, and the entry just written is never the victim.
 *
 * Nothing here throws. A cache that cannot be read is a cache miss, and a
 * cache that cannot be written is a photo that will be fetched again — both
 * are worth a slower screen, neither is worth a crash.
 */
class DiskStockPhotoFiles(
    private val root: File,
    private val maxBytes: Long = StockPhotoDisk.MAX_BYTES,
    private val now: () -> Long = System::currentTimeMillis
) : StockPhotoFiles {

    /**
     * File name to size, in **access order**.
     *
     * Seeded from the directory on first use, oldest modification first, so a
     * fresh process inherits a sensible eviction order rather than starting
     * from an arbitrary one. After that this process's own reads and writes
     * decide it.
     */
    private val entries = LinkedHashMap<String, Long>(16, 0.75f, true)
    private var scanned = false

    val fileCount: Int get() = synchronized(this) { load(); entries.size }

    val totalBytes: Long get() = synchronized(this) { load(); entries.values.sum() }

    override fun read(documentId: String, atLeastRev: Double): CachedPhoto? = synchronized(this) {
        readLocked(documentId, atLeastRev)
    }

    override fun put(documentId: String, rev: Double, bytes: ByteArray) = synchronized(this) {
        putLocked(documentId, rev, bytes)
    }

    override fun forget(documentId: String) = synchronized(this) {
        load()
        forgetOthers(documentId, keep = null)
    }

    override fun clear() = synchronized(this) {
        load()
        entries.keys.toList().forEach { drop(it) }
    }

    // --- under the lock -----------------------------------------------------

    private fun readLocked(documentId: String, atLeastRev: Double): CachedPhoto? {
        load()
        val name = entries.keys.firstOrNull { StockPhotoDisk.belongsTo(it, documentId) }
            ?: return null
        val rev = StockPhotoDisk.revOf(name)
        if (rev == null || rev < atLeastRev) {
            // Superseded, or a name this version does not understand. Either
            // way it will never be served again, so it goes now.
            drop(name)
            return null
        }
        val file = File(root, name)
        val bytes = runCatching { file.readBytes() }.getOrNull()
        // A cleared or truncated file reads as absent or as something that is
        // not a photo. Both are misses, and both are swept up here.
        if (bytes == null || StockPhoto.refusal(bytes) != null) {
            drop(name)
            return null
        }
        entries[name] = bytes.size.toLong()
        // Best effort: it makes the modification time an approximate access
        // time, so the eviction order survives into the next process. A
        // filesystem that refuses costs nothing but a worse ordering.
        runCatching { file.setLastModified(now()) }
        return CachedPhoto(rev, bytes)
    }

    private fun putLocked(documentId: String, rev: Double, bytes: ByteArray) {
        val name = StockPhotoDisk.nameFor(documentId, rev) ?: return
        if (StockPhoto.refusal(bytes) != null) return
        load()
        if (!ensureRoot()) return

        val partial = File(root, "$name${StockPhotoDisk.PARTIAL}")
        val target = File(root, name)
        val written = runCatching {
            partial.outputStream().use { it.write(bytes) }
            // Rename within one directory is atomic: the file is either the
            // old one or the whole new one, never half of either.
            if (!partial.renameTo(target)) throw IOException("could not rename $name")
        }
        if (written.isFailure) {
            runCatching { partial.delete() }
            return
        }

        // Every other revision of this row is now waste.
        forgetOthers(documentId, keep = name)
        entries[name] = bytes.size.toLong()
        evictDownTo(keep = name)
    }

    // --- housekeeping -------------------------------------------------------

    private fun forgetOthers(documentId: String, keep: String?) {
        entries.keys.toList()
            .filter { it != keep && StockPhotoDisk.belongsTo(it, documentId) }
            .forEach { drop(it) }
    }

    /**
     * Evicts the least recently used until the total fits.
     *
     * [keep] is never a victim: evicting the file just written would leave the
     * caller believing it is cached, and the next read would fetch anyway. If
     * one photo were somehow larger than the whole bound, the loop stops with
     * that single entry rather than spinning.
     */
    private fun evictDownTo(keep: String?) {
        var total = entries.values.sum()
        val victims = entries.keys.toList()
        for (name in victims) {
            if (total <= maxBytes) return
            if (name == keep) continue
            total -= entries[name] ?: 0L
            drop(name)
        }
    }

    private fun drop(name: String) {
        entries.remove(name)
        runCatching { File(root, name).delete() }
    }

    private fun ensureRoot(): Boolean =
        runCatching { root.isDirectory || root.mkdirs() }.getOrDefault(false)

    /**
     * Reads the directory once per process.
     *
     * Order is oldest modification first, which is the best guess at least
     * recently used that a restart leaves behind. Leftover `.part` files —
     * a write interrupted by the process dying — are deleted here rather than
     * counted, and anything that is not a cache file is left alone.
     */
    private fun load() {
        if (scanned) return
        scanned = true
        val files = runCatching { root.listFiles() }.getOrNull() ?: return
        files.filter { it.isFile && it.name.endsWith(StockPhotoDisk.PARTIAL) }
            .forEach { runCatching { it.delete() } }
        files.filter { it.isFile && StockPhotoDisk.revOf(it.name) != null }
            .sortedBy { it.lastModified() }
            .forEach { entries[it.name] = it.length() }
        evictDownTo(keep = null)
    }
}
