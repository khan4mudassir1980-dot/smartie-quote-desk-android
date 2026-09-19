package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.mapping.Keys
import `in`.smartie.quotedesk.data.model.StockRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cache is the whole quota argument, so it is tested as such: a matching
 * revision must cost nothing, and a superseded one must never be shown.
 */
class StockPhotoCacheTest {

    private fun row(
        model: String = "SIE1000",
        hasPhoto: Boolean = true,
        photoRev: Double = 1.0
    ): StockRecord {
        val key = Keys.productKey("gateMotors", model)
        return StockRecord(
            documentId = Keys.stockDocId(key),
            key = key,
            hasPhoto = hasPhoto,
            photoRev = photoRev
        )
    }

    private fun bytes(marker: Byte, size: Int = 32) = ByteArray(size) { marker }

    @Test
    fun `a matching revision is served without a fetch`() {
        val cache = StockPhotoCache()
        val record = row(photoRev = 3.0)
        cache.put(record.documentId, 3.0, bytes(1))

        assertEquals(PhotoAction.USE_CACHE, cache.actionFor(record))
        assertTrue(cache.bytesFor(record)!!.contentEquals(bytes(1)))
    }

    @Test
    fun `a superseded revision is fetched and never served`() {
        val cache = StockPhotoCache()
        val record = row(photoRev = 4.0)
        cache.put(record.documentId, 3.0, bytes(1))

        assertEquals(PhotoAction.FETCH, cache.actionFor(record))
        assertNull("stale bytes must not be shown", cache.bytesFor(record))
    }

    @Test
    fun `nothing cached means fetch`() {
        assertEquals(PhotoAction.FETCH, StockPhotoCache().actionFor(row()))
    }

    @Test
    fun `a row that has lost its photo drops the bytes and spends no read`() {
        val cache = StockPhotoCache()
        val withPhoto = row(photoRev = 2.0)
        cache.put(withPhoto.documentId, 2.0, bytes(1))
        assertEquals(1, cache.size)

        val removed = withPhoto.copy(hasPhoto = false, photoRev = 3.0)
        assertEquals(PhotoAction.NONE, cache.actionFor(removed))
        assertEquals("the cached image is discarded, not kept", 0, cache.size)
        assertNull(cache.bytesFor(removed))
    }

    @Test
    fun `an unchanged photo is read a hundred times and fetched once`() {
        val cache = StockPhotoCache()
        val record = row(photoRev = 7.0)
        cache.put(record.documentId, 7.0, bytes(9))

        repeat(100) {
            assertEquals(PhotoAction.USE_CACHE, cache.actionFor(record))
        }
        assertEquals(1, cache.size)
    }

    @Test
    fun `a replacement invalidates, and the new bytes take over`() {
        val cache = StockPhotoCache()
        val before = row(photoRev = 1.0)
        cache.put(before.documentId, 1.0, bytes(1))

        val after = before.copy(photoRev = 2.0)
        assertEquals(PhotoAction.FETCH, cache.actionFor(after))

        cache.put(after.documentId, 2.0, bytes(2))
        assertEquals(PhotoAction.USE_CACHE, cache.actionFor(after))
        assertTrue(cache.bytesFor(after)!!.contentEquals(bytes(2)))
    }

    @Test
    fun `an older revision never displaces a newer one already held`() {
        val cache = StockPhotoCache()
        val record = row(photoRev = 5.0)
        cache.put(record.documentId, 5.0, bytes(5))
        cache.put(record.documentId, 4.0, bytes(4))

        assertTrue("the newer image stays", cache.bytesFor(record)!!.contentEquals(bytes(5)))
    }

    @Test
    fun `a revision ahead of the row is this device's own write and is served`() {
        val cache = StockPhotoCache()
        val record = row(photoRev = 4.0)
        cache.put(record.documentId, 5.0, bytes(5))

        assertEquals(PhotoAction.USE_CACHE, cache.actionFor(record))
    }

    @Test
    fun `the cache is bounded and drops what was used longest ago`() {
        val cache = StockPhotoCache(maxEntries = 2)
        val first = row("A"); val second = row("B"); val third = row("C")
        cache.put(first.documentId, 1.0, bytes(1))
        cache.put(second.documentId, 1.0, bytes(2))

        // Touching the first makes the second the least recently used.
        cache.actionFor(first)
        cache.put(third.documentId, 1.0, bytes(3))

        assertEquals(2, cache.size)
        assertEquals(PhotoAction.USE_CACHE, cache.actionFor(first))
        assertEquals(PhotoAction.FETCH, cache.actionFor(second))
        assertEquals(PhotoAction.USE_CACHE, cache.actionFor(third))
    }

    @Test
    fun `forgetting one row leaves the others alone`() {
        val cache = StockPhotoCache()
        val first = row("A"); val second = row("B")
        cache.put(first.documentId, 1.0, bytes(1))
        cache.put(second.documentId, 1.0, bytes(2))

        cache.forget(first.documentId)
        assertEquals(PhotoAction.FETCH, cache.actionFor(first))
        assertEquals(PhotoAction.USE_CACHE, cache.actionFor(second))
    }

    @Test
    fun `the bytes are handed back as held, not copied`() {
        // The caller decodes these; copying a board's worth of images on every
        // read would undo the point of caching them.
        val cache = StockPhotoCache()
        val record = row(photoRev = 1.0)
        val held = bytes(3)
        cache.put(record.documentId, 1.0, held)

        assertSame(held, cache.bytesFor(record))
    }
}
