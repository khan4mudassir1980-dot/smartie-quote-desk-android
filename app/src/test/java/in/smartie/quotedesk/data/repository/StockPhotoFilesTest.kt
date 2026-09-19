package `in`.smartie.quotedesk.data.repository

import `in`.smartie.quotedesk.domain.StockPhotoDisk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The persistent half of the photo cache, against a **real** directory.
 *
 * A cache whose whole purpose is to outlive the process is not worth proving
 * against a fake, so these tests use real files and simulate a restart the
 * only way that means anything: by throwing the object away and building a
 * new one over the same directory.
 */
class StockPhotoFilesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val key = "gateMotors|SIE1000"
    private val other = "boomBarriers|BB200"

    private fun webp(payload: Int = 512): ByteArray =
        "RIFF".toByteArray() + ByteArray(4) + "WEBP".toByteArray() + ByteArray(payload)

    private fun root(): File = folder.root

    private fun cache(
        maxBytes: Long = StockPhotoDisk.MAX_BYTES,
        now: () -> Long = System::currentTimeMillis
    ) = DiskStockPhotoFiles(root(), maxBytes, now)

    // --- the basic promise --------------------------------------------------

    @Test
    fun `what was written comes back`() {
        val files = cache()
        files.put(key, 1.0, webp(64))

        val held = files.read(key, 1.0)
        assertNotNull(held)
        assertEquals(1.0, held!!.rev, 0.0)
        assertEquals(webp(64).size, held.bytes.size)
    }

    @Test
    fun `one row's photo is not another's`() {
        val files = cache()
        files.put(key, 1.0, webp(64))
        assertNull(files.read(other, 1.0))
    }

    // --- restart ------------------------------------------------------------

    @Test
    fun `a photo survives the object being thrown away and rebuilt`() {
        cache().put(key, 4.0, webp(128))

        // A new instance over the same directory is what a restart looks like
        // from here: no memory of the first one at all.
        val afterRestart = cache()
        val held = afterRestart.read(key, 4.0)

        assertNotNull("a restart must not lose the cache", held)
        assertEquals(4.0, held!!.rev, 0.0)
        assertEquals(128 + 12, held.bytes.size)
    }

    @Test
    fun `a stale file left behind by an earlier run is not served after a restart`() {
        cache().put(key, 1.0, webp(64))

        // The row moved on while this device was not running.
        val afterRestart = cache()
        assertNull(afterRestart.read(key, 2.0))
        assertEquals("and the waste is gone", 0, afterRestart.fileCount)
    }

    // --- revisions ----------------------------------------------------------

    @Test
    fun `a superseded revision is never served`() {
        val files = cache()
        files.put(key, 1.0, webp(64))
        assertNull("revision 1 cannot answer for revision 2", files.read(key, 2.0))
    }

    @Test
    fun `a superseded revision is deleted as it is found`() {
        val files = cache()
        files.put(key, 1.0, webp(64))
        files.read(key, 2.0)
        assertEquals(0, files.fileCount)
        assertEquals(0, root().listFiles()!!.size)
    }

    @Test
    fun `a revision ahead of the row still answers`() {
        // This device wrote revision 3; the listener has not caught up yet.
        val files = cache()
        files.put(key, 3.0, webp(64))
        assertEquals(3.0, files.read(key, 2.0)!!.rev, 0.0)
    }

    @Test
    fun `replacing a photo leaves exactly one file for the row`() {
        val files = cache()
        files.put(key, 1.0, webp(64))
        files.put(key, 2.0, webp(96))
        files.put(key, 3.0, webp(128))

        assertEquals(1, files.fileCount)
        assertEquals(1, root().listFiles()!!.count { it.isFile })
        assertEquals(3.0, files.read(key, 3.0)!!.rev, 0.0)
    }

    @Test
    fun `a revision that cannot be named is simply not kept`() {
        val files = cache()
        files.put(key, 1.5, webp(64))
        assertEquals(0, files.fileCount)
        assertNull(files.read(key, 1.5))
    }

    // --- removal ------------------------------------------------------------

    @Test
    fun `forgetting a row deletes its file`() {
        val files = cache()
        files.put(key, 1.0, webp(64))
        files.put(other, 1.0, webp(64))

        files.forget(key)

        assertNull(files.read(key, 1.0))
        assertNotNull("only that row", files.read(other, 1.0))
        assertEquals(1, root().listFiles()!!.count { it.isFile })
    }

    @Test
    fun `a forgotten row stays forgotten after a restart`() {
        cache().put(key, 1.0, webp(64))
        cache().forget(key)
        assertNull(cache().read(key, 1.0))
    }

    @Test
    fun `clear empties the directory`() {
        val files = cache()
        files.put(key, 1.0, webp(64))
        files.put(other, 2.0, webp(64))

        files.clear()

        assertEquals(0, files.fileCount)
        assertEquals(0, root().listFiles()!!.count { it.isFile })
    }

    // --- damage -------------------------------------------------------------

    @Test
    fun `a corrupted file is a miss, not a broken picture`() {
        val files = cache()
        files.put(key, 1.0, webp(64))

        val file = root().listFiles()!!.single { it.isFile }
        file.writeBytes("this is not a photograph".toByteArray())

        assertNull("garbage is never served", cache().read(key, 1.0))
    }

    @Test
    fun `a corrupted file is deleted so the next load fetches`() {
        val files = cache()
        files.put(key, 1.0, webp(64))
        root().listFiles()!!.single { it.isFile }.writeBytes(ByteArray(3))

        val afterRestart = cache()
        afterRestart.read(key, 1.0)

        assertEquals(0, afterRestart.fileCount)
        assertEquals(0, root().listFiles()!!.count { it.isFile })
    }

    @Test
    fun `a truncated file is a miss`() {
        val files = cache()
        files.put(key, 1.0, webp(64))
        root().listFiles()!!.single { it.isFile }.writeBytes(ByteArray(0))
        assertNull(cache().read(key, 1.0))
    }

    @Test
    fun `a file Android cleared behind our back is a miss`() {
        val files = cache()
        files.put(key, 1.0, webp(64))
        // Android may empty app storage at any time; the index still
        // remembers the entry, and the read has to cope.
        root().listFiles()!!.forEach { it.delete() }
        assertNull(files.read(key, 1.0))
        assertEquals(0, files.fileCount)
    }

    @Test
    fun `the whole directory disappearing is a miss, not a crash`() {
        val files = cache()
        files.put(key, 1.0, webp(64))
        root().deleteRecursively()
        assertNull(files.read(key, 1.0))
    }

    @Test
    fun `a write survives the directory having been deleted`() {
        val files = cache()
        root().deleteRecursively()
        files.put(key, 1.0, webp(64))
        assertNotNull(files.read(key, 1.0))
    }

    // --- partial writes -----------------------------------------------------

    @Test
    fun `a partial file left by a killed process is never served`() {
        val files = cache()
        val partial = File(root(), StockPhotoDisk.nameFor(key, 1.0)!! + StockPhotoDisk.PARTIAL)
        partial.writeBytes(webp(64))

        assertNull(files.read(key, 1.0))
    }

    @Test
    fun `a partial file left by a killed process is swept up`() {
        File(root(), StockPhotoDisk.nameFor(key, 1.0)!! + StockPhotoDisk.PARTIAL)
            .writeBytes(webp(64))

        val files = cache()
        files.read(key, 1.0)

        assertFalse("the leftover is gone", root().listFiles()!!.any { it.name.endsWith(".part") })
    }

    @Test
    fun `nothing is left behind after a successful write`() {
        val files = cache()
        files.put(key, 1.0, webp(64))
        assertEquals(1, root().listFiles()!!.count { it.isFile })
    }

    // --- the bound ----------------------------------------------------------

    @Test
    fun `the total stays under the bound`() {
        // Each photo is 1,036 bytes on disk, so four fit in 5,000 and a
        // fifth does not.
        val files = cache(maxBytes = 5_000L)
        repeat(8) { files.put("row$it", 1.0, webp(1_024)) }

        assertTrue("over the bound: ${files.totalBytes}", files.totalBytes <= 5_000)
        assertEquals(4, files.fileCount)
    }

    @Test
    fun `eviction takes the least recently used, not the newest`() {
        // Two of the 1,036-byte photos fit in 2,200; a third cannot.
        val files = cache(maxBytes = 2_200L)
        files.put("a", 1.0, webp(1_024))
        files.put("b", 1.0, webp(1_024))

        // Touching "a" makes "b" the oldest.
        assertNotNull(files.read("a", 1.0))

        files.put("c", 1.0, webp(1_024))

        assertNotNull("a was used most recently", files.read("a", 1.0))
        assertNotNull("c was just written", files.read("c", 1.0))
        assertNull("b was the least recently used", files.read("b", 1.0))
    }

    @Test
    fun `the photo just written is never the one evicted`() {
        val files = cache(maxBytes = 2_000L)
        files.put("a", 1.0, webp(1_024))
        files.put("b", 1.0, webp(1_024))

        assertNotNull("the newest write must survive its own eviction pass", files.read("b", 1.0))
    }

    @Test
    fun `a single photo larger than the whole bound does not spin`() {
        val files = cache(maxBytes = 10L)
        files.put(key, 1.0, webp(1_024))
        // It is kept — it is the one entry and the keep rule protects it —
        // but nothing loops trying to get under a bound it cannot reach.
        assertEquals(1, files.fileCount)
    }

    @Test
    fun `an over-full directory is trimmed on the next start`() {
        val big = cache()
        repeat(6) { big.put("row$it", 1.0, webp(1_024)) }
        assertEquals(6, big.fileCount)

        // The bound changed — a new build, say — and the directory is already
        // over it. The scan trims rather than serving an unbounded cache.
        val bounded = cache(maxBytes = 3_200L)
        assertTrue(bounded.totalBytes <= 3_200)
        assertEquals(3, bounded.fileCount)
    }

    @Test
    fun `eviction after a restart takes the oldest file first`() {
        val seeding = cache()
        seeding.put("old", 1.0, webp(1_024))
        seeding.put("new", 1.0, webp(1_024))
        // Modification time is the only access order a restart inherits.
        File(root(), StockPhotoDisk.nameFor("old", 1.0)!!).setLastModified(1_000L)
        File(root(), StockPhotoDisk.nameFor("new", 1.0)!!).setLastModified(9_000L)

        val bounded = cache(maxBytes = 1_100L)

        assertNull(bounded.read("old", 1.0))
        assertNotNull(bounded.read("new", 1.0))
    }

    @Test
    fun `files that are not ours are left alone`() {
        File(root(), "something-else.txt").writeBytes("keep me".toByteArray())
        val files = cache()
        files.put(key, 1.0, webp(64))
        files.clear()

        assertTrue(File(root(), "something-else.txt").exists())
    }

    @Test
    fun `bytes that are not a photo are never written`() {
        val files = cache()
        files.put(key, 1.0, "not a photograph".toByteArray())
        assertEquals(0, files.fileCount)
        assertEquals(0, root().listFiles()!!.count { it.isFile })
    }
}
