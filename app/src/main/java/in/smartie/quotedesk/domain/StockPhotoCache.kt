package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.model.StockRecord

/**
 * Photo bytes held by stock identity **and revision**.
 *
 * The two rules this exists to keep, both of which matter:
 *
 * - **Never serve bytes whose revision does not match the row.** A replaced
 *   photo is stale the moment the row updates, and a stale picture on a shelf
 *   is worse than none.
 * - **Never re-fetch on a match.** This is what stops unchanged photos being
 *   downloaded again and again, and the whole usage estimate in
 *   `docs/N3.1-plan.md` rests on it.
 *
 * Least-recently-used and bounded. This layer is **memory only and dies with
 * the process** — surviving a restart is `DiskStockPhotoFiles`'s job, and it
 * exists because Firestore's own persistence is not a substitute: a `get()`
 * its local cache answers is still a billed document read. This cache is the
 * fast layer in front of the disk, not the durable one.
 */
class StockPhotoCache(private val maxEntries: Int = DEFAULT_ENTRIES) {

    private class Held(val rev: Double, val bytes: ByteArray)

    private val held = object : LinkedHashMap<String, Held>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Held>): Boolean =
            size > maxEntries
    }

    val size: Int get() = held.size

    /**
     * What to do about [record], **and** the discard when there is no longer a
     * photo: a row that has lost its picture drops the bytes here as it is
     * asked about, without spending a read to discover it.
     */
    fun actionFor(record: StockRecord): PhotoAction {
        val action = StockPhoto.action(record.hasPhoto, held[record.documentId]?.rev, record.photoRev)
        if (action == PhotoAction.NONE) held.remove(record.documentId)
        return action
    }

    /** The bytes for [record], or null when they are missing or superseded. */
    fun bytesFor(record: StockRecord): ByteArray? =
        if (actionFor(record) == PhotoAction.USE_CACHE) held[record.documentId]?.bytes else null

    /** Keeps [bytes] against [rev]; an older revision never displaces a newer. */
    fun put(documentId: String, rev: Double, bytes: ByteArray) {
        val existing = held[documentId]
        if (existing != null && existing.rev > rev) return
        held[documentId] = Held(rev, bytes)
    }

    fun forget(documentId: String) {
        held.remove(documentId)
    }

    fun clear() = held.clear()

    private companion object {
        /**
         * Enough for a board's worth of thumbnails at 80 KiB apiece — about
         * 5 MiB at the ceiling, and far less in practice.
         */
        const val DEFAULT_ENTRIES = 64
    }
}
