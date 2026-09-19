package `in`.smartie.quotedesk.data.repository

import `in`.smartie.quotedesk.data.mapping.Keys
import `in`.smartie.quotedesk.data.model.StockPhotoRecord
import `in`.smartie.quotedesk.data.model.StockRecord
import `in`.smartie.quotedesk.domain.StockPhotoCache
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reading a photo: at most one fetch, and never a stale image. */
class StockPhotoRepositoryTest {

    private class FakeStore(private var photo: StockPhotoRecord?) : StockPhotoStore {
        var reads = 0
        override suspend fun read(documentId: String): StockPhotoRecord? {
            reads++
            return photo
        }
        fun replaceWith(next: StockPhotoRecord?) { photo = next }
    }

    private val key = Keys.productKey("gateMotors", "SIE1000")
    private val documentId = Keys.stockDocId(key)

    private fun row(hasPhoto: Boolean = true, photoRev: Double = 1.0) = StockRecord(
        documentId = documentId, key = key, hasPhoto = hasPhoto, photoRev = photoRev
    )

    private fun stored(rev: Double, marker: Byte = 1) = StockPhotoRecord(
        documentId = documentId, key = key, bytes = ByteArray(32) { marker }, rev = rev
    )

    @Test
    fun `the first read fetches and the next hundred do not`() = runTest {
        val store = FakeStore(stored(rev = 1.0))
        val repository = StockPhotoRepository(store, StockPhotoCache())
        val record = row(photoRev = 1.0)

        assertTrue(repository.load(record)!!.contentEquals(ByteArray(32) { 1 }))
        repeat(100) { repository.load(record) }

        assertEquals("one fetch, however often it is asked for", 1, store.reads)
    }

    @Test
    fun `a row with no photo is never fetched`() = runTest {
        val store = FakeStore(stored(rev = 1.0))
        val repository = StockPhotoRepository(store, StockPhotoCache())

        assertNull(repository.load(row(hasPhoto = false, photoRev = 0.0)))
        assertEquals(0, store.reads)
    }

    @Test
    fun `a replacement is fetched again, exactly once`() = runTest {
        val store = FakeStore(stored(rev = 1.0, marker = 1))
        val repository = StockPhotoRepository(store, StockPhotoCache())

        repository.load(row(photoRev = 1.0))
        store.replaceWith(stored(rev = 2.0, marker = 2))

        val replaced = repository.load(row(photoRev = 2.0))
        assertTrue(replaced!!.contentEquals(ByteArray(32) { 2 }))
        assertEquals(2, store.reads)

        repository.load(row(photoRev = 2.0))
        assertEquals("the new one is cached too", 2, store.reads)
    }

    @Test
    fun `a fetched document behind the row is not shown`() = runTest {
        // A read racing a listener update: the row has moved on, the document
        // read came back older. Showing it would show a replaced picture.
        val store = FakeStore(stored(rev = 1.0))
        val repository = StockPhotoRepository(store, StockPhotoCache())

        assertNull(repository.load(row(photoRev = 2.0)))
    }

    @Test
    fun `a missing photo document is not an error, it is simply no photo`() = runTest {
        val repository = StockPhotoRepository(FakeStore(null), StockPhotoCache())
        assertNull(repository.load(row(photoRev = 1.0)))
    }

    @Test
    fun `removing a photo drops the bytes without a read`() = runTest {
        val store = FakeStore(stored(rev = 1.0))
        val repository = StockPhotoRepository(store, StockPhotoCache())
        repository.load(row(photoRev = 1.0))

        assertNull(repository.load(row(hasPhoto = false, photoRev = 2.0)))
        assertEquals("a removal costs nothing to notice", 1, store.reads)
    }

    @Test
    fun `forgetting a row makes the next read fetch again`() = runTest {
        val store = FakeStore(stored(rev = 1.0))
        val repository = StockPhotoRepository(store, StockPhotoCache())
        val record = row(photoRev = 1.0)

        repository.load(record)
        repository.forget(record)
        repository.load(record)

        assertEquals(2, store.reads)
    }
}
