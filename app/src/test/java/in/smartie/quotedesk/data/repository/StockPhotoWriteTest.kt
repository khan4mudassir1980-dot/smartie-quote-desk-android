package `in`.smartie.quotedesk.data.repository

import `in`.smartie.quotedesk.data.mapping.Keys
import `in`.smartie.quotedesk.data.model.StockRecord
import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.domain.Role
import `in`.smartie.quotedesk.domain.ServerTimestamp
import `in`.smartie.quotedesk.domain.StockPhoto
import `in`.smartie.quotedesk.domain.StockPhotoImage
import `in`.smartie.quotedesk.domain.StockWrite
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The photo transaction contract, driven through a fake store that can be
 * pointed at whatever the "stored" row says — which is how the racing cases
 * below are reproduced without two phones.
 */
class StockPhotoWriteTest {

    private class Written(val docId: String, val data: Map<String, Any?>?)

    private class FakeStore(
        private var stored: Map<String, Any?>? = null,
        /** Run the body this many times, as Firestore does under contention. */
        private val attempts: Int = 1,
        /** What the row looks like on later attempts, once a rival committed. */
        private val storedOnRetry: Map<String, Any?>? = null
    ) : StockStore {
        val stock = mutableListOf<Written>()
        val photos = mutableListOf<Written>()
        val movements = mutableListOf<Written>()
        var bodyRuns = 0
        var stockReads = 0

        @Suppress("UNCHECKED_CAST")
        override suspend fun <T> transaction(body: (StockTransaction) -> T): T {
            var last: T? = null
            repeat(attempts) { attempt ->
                stock.clear(); photos.clear(); movements.clear()
                bodyRuns++
                if (attempt > 0 && storedOnRetry != null) stored = storedOnRetry
                last = body(object : StockTransaction {
                    override fun readStock(docId: String): Map<String, Any?>? {
                        stockReads++
                        return stored
                    }
                    override fun writeStock(docId: String, data: Map<String, Any?>, merge: Boolean) {
                        stock += Written(docId, data)
                    }
                    override fun writeMovement(docId: String, data: Map<String, Any?>) {
                        movements += Written(docId, data)
                    }
                    override fun writePhoto(docId: String, data: Map<String, Any?>) {
                        photos += Written(docId, data)
                    }
                    override fun deletePhoto(docId: String) {
                        photos += Written(docId, null)
                    }
                })
            }
            return last as T
        }
    }

    private val admin = Member(uid = "uid_admin", name = "Asha", email = "a@x.invalid", role = Role.ADMIN)
    private val staff = Member(uid = "uid_staff", name = "Sam", email = "s@x.invalid", role = Role.STAFF)
    private val worker = Member(uid = "uid_worker", name = "Wes", email = "w@x.invalid", role = Role.WORKER)

    private val key = Keys.productKey("gateMotors", "SIE1000")
    private val record = StockRecord(
        documentId = Keys.stockDocId(key),
        key = key,
        quantity = 7.0,
        reorderLevel = 2.0,
        name = "Sliding gate motor",
        model = "SIE1000",
        group = "gateMotors",
        unit = "each"
    )

    private fun webp(payload: Int = 512): ByteArray =
        "RIFF".toByteArray() + ByteArray(4) + "WEBP".toByteArray() + ByteArray(payload)

    private fun image(payload: Int = 512) = StockPhotoImage(webp(payload), 800, 600)

    /** A stored row, as the transaction would read it. */
    private fun row(
        q: Any = 7.0,
        min: Any = 2.0,
        photoRev: Any? = null,
        hasPhoto: Any? = null
    ): Map<String, Any?> = buildMap {
        put("key", key); put("q", q); put("min", min)
        if (photoRev != null) put("photoRev", photoRev)
        if (hasPhoto != null) put("hasPhoto", hasPhoto)
    }

    private fun repository(store: FakeStore) =
        StockWriteRepository(store, now = { 1_700_000_000_000L }, newMovementId = { "mv_unused" })

    // --- the happy path ----------------------------------------------------

    @Test
    fun `setting a photo writes both documents and no movement`() = runTest {
        val store = FakeStore(stored = row())
        val result = repository(store).setPhoto(staff, record, image())

        assertEquals(StockWriteResult.WRITTEN, result)
        assertEquals(1, store.photos.size)
        assertEquals(1, store.stock.size)
        assertTrue("a photograph is not a movement", store.movements.isEmpty())

        assertEquals("gateMotors|SIE1000", store.photos.single().docId)
        assertEquals("gateMotors|SIE1000", store.stock.single().docId)
    }

    @Test
    fun `the stock row keeps its numbers and gains only the photo metadata`() = runTest {
        val store = FakeStore(stored = row(q = 9.0, min = 3.0))
        repository(store).setPhoto(admin, record, image())

        val stock = store.stock.single().data!!
        assertEquals(9.0, stock["q"])
        assertEquals(3.0, stock["min"])
        assertEquals(true, stock["hasPhoto"])
        assertEquals(1.0, stock["photoRev"])
        assertEquals(StockWrite.LAST_ACTION_PHOTO, stock["lastAction"])
        assertFalse("archiving is untouched", stock.containsKey("off"))
        assertFalse("pinning is untouched", stock.containsKey("pinned"))
    }

    @Test
    fun `the photo document carries the bytes, the revision and its author`() = runTest {
        val store = FakeStore(stored = row())
        repository(store).setPhoto(staff, record, image(2_048))

        val photo = store.photos.single().data!!
        assertEquals(key, photo["key"])
        assertEquals(1.0, photo["rev"])
        assertEquals("uid_staff", photo["byUid"])
        assertEquals(ServerTimestamp, photo["serverAt"])
        assertEquals(800.0, photo["w"])
        assertEquals(600.0, photo["h"])
        assertTrue((photo["bytes"] as ByteArray).size > 2_000)
    }

    @Test
    fun `the stored revision is what the next one is built from, not the screen's`() = runTest {
        // The row on screen is stale at rev 1; the stored row has moved to 4.
        val store = FakeStore(stored = row(photoRev = 4.0, hasPhoto = true))
        repository(store).setPhoto(staff, record.copy(photoRev = 1.0, hasPhoto = true), image())

        assertEquals(5.0, store.stock.single().data!!["photoRev"])
        assertEquals(5.0, store.photos.single().data!!["rev"])
    }

    @Test
    fun `the row is read once per attempt, not once per field`() = runTest {
        val store = FakeStore(stored = row())
        repository(store).setPhoto(staff, record, image())
        assertEquals(1, store.stockReads)
    }

    // --- removal -----------------------------------------------------------

    @Test
    fun `removing deletes the photo document and clears the row together`() = runTest {
        val store = FakeStore(stored = row(photoRev = 2.0, hasPhoto = true))
        val result = repository(store).removePhoto(admin, record.copy(hasPhoto = true, photoRev = 2.0))

        assertEquals(StockWriteResult.WRITTEN, result)
        assertNull("the document is deleted, not emptied", store.photos.single().data)
        val stock = store.stock.single().data!!
        assertEquals(false, stock["hasPhoto"])
        assertEquals(3.0, stock["photoRev"])
        assertTrue(store.movements.isEmpty())
    }

    @Test
    fun `removing a photo from a row that has none writes nothing at all`() = runTest {
        val store = FakeStore(stored = row())
        val result = repository(store).removePhoto(admin, record)

        assertEquals(StockWriteResult.NO_CHANGE, result)
        assertTrue(store.photos.isEmpty())
        assertTrue(store.stock.isEmpty())
    }

    // --- conflicts, one test per row of the plan's table --------------------

    @Test
    fun `replace against replace - the later commit lands one revision higher`() = runTest {
        // Both read rev 1. A rival commits 2 before this body is replayed.
        val store = FakeStore(
            stored = row(photoRev = 1.0, hasPhoto = true),
            attempts = 2,
            storedOnRetry = mapOf("key" to key, "q" to 7.0, "min" to 2.0, "photoRev" to 2.0, "hasPhoto" to true)
        )
        repository(store).setPhoto(staff, record.copy(hasPhoto = true, photoRev = 1.0), image())

        assertEquals(2, store.bodyRuns)
        // Only the final attempt's writes count, and they are built from the
        // rival's data rather than from the first attempt's.
        assertEquals(3.0, store.stock.single().data!!["photoRev"])
        assertEquals(3.0, store.photos.single().data!!["rev"])
        assertEquals(true, store.stock.single().data!!["hasPhoto"])
    }

    @Test
    fun `replace against remove, remove first - the photo ends up present`() = runTest {
        val store = FakeStore(
            stored = row(photoRev = 1.0, hasPhoto = true),
            attempts = 2,
            storedOnRetry = mapOf("key" to key, "q" to 7.0, "min" to 2.0, "photoRev" to 2.0, "hasPhoto" to false)
        )
        repository(store).setPhoto(staff, record.copy(hasPhoto = true, photoRev = 1.0), image())

        // The later action wins: a photo exists again, at rev 3.
        assertEquals(true, store.stock.single().data!!["hasPhoto"])
        assertEquals(3.0, store.stock.single().data!!["photoRev"])
        assertEquals(3.0, store.photos.single().data!!["rev"])
    }

    @Test
    fun `remove against replace, replace first - the photo ends up gone`() = runTest {
        val store = FakeStore(
            stored = row(photoRev = 1.0, hasPhoto = true),
            attempts = 2,
            storedOnRetry = mapOf("key" to key, "q" to 7.0, "min" to 2.0, "photoRev" to 2.0, "hasPhoto" to true)
        )
        repository(store).removePhoto(admin, record.copy(hasPhoto = true, photoRev = 1.0))

        assertEquals(false, store.stock.single().data!!["hasPhoto"])
        assertEquals(3.0, store.stock.single().data!!["photoRev"])
        assertNull(store.photos.single().data)
    }

    @Test
    fun `a photo change racing a quantity change re-asserts the stored quantity`() = runTest {
        val store = FakeStore(
            stored = row(q = 7.0, photoRev = 0.0),
            attempts = 2,
            // A rival moved the count to 12 between the attempts.
            storedOnRetry = mapOf("key" to key, "q" to 12.0, "min" to 2.0, "photoRev" to 0.0)
        )
        repository(store).setPhoto(staff, record, image())

        // The screen believed 7; the transaction writes back what it read.
        assertEquals(12.0, store.stock.single().data!!["q"])
    }

    // --- refusals ----------------------------------------------------------

    @Test
    fun `a Worker is refused before the transaction opens`() = runTest {
        val store = FakeStore(stored = row())
        val failure = runCatching { repository(store).setPhoto(worker, record, image()) }

        assertTrue(failure.isFailure)
        assertEquals(0, store.bodyRuns)
        assertTrue(store.photos.isEmpty())
    }

    @Test
    fun `a Worker cannot remove one either`() = runTest {
        val store = FakeStore(stored = row(photoRev = 1.0, hasPhoto = true))
        assertTrue(runCatching { repository(store).removePhoto(worker, record) }.isFailure)
        assertEquals(0, store.bodyRuns)
    }

    @Test
    fun `an image that will not fit is refused and nothing is written`() = runTest {
        val store = FakeStore(stored = row())
        val failure = runCatching {
            repository(store).setPhoto(staff, record, StockPhotoImage(webp(StockPhoto.MAX_BYTES), 800, 800))
        }

        assertTrue(failure.isFailure)
        assertEquals(StockPhoto.WILL_NOT_FIT, failure.exceptionOrNull()?.message)
        assertTrue(store.photos.isEmpty())
        assertTrue(store.stock.isEmpty())
    }

    @Test
    fun `something that is not a picture is refused with a different sentence`() = runTest {
        val store = FakeStore(stored = row())
        val failure = runCatching {
            repository(store).setPhoto(staff, record, StockPhotoImage(ByteArray(64), 10, 10))
        }

        assertEquals(StockPhoto.NOT_AN_IMAGE, failure.exceptionOrNull()?.message)
        assertTrue(store.stock.isEmpty())
    }

    // --- legacy rows -------------------------------------------------------

    @Test
    fun `an imported row holding its numbers as strings still works`() = runTest {
        val store = FakeStore(stored = row(q = "9", min = "3", photoRev = "4", hasPhoto = 1))
        repository(store).setPhoto(staff, record, image())

        val stock = store.stock.single().data!!
        assertEquals(9.0, stock["q"])
        assertEquals(3.0, stock["min"])
        assertEquals(5.0, stock["photoRev"])
    }

    @Test
    fun `a row that is not stored yet falls back to the record in hand`() = runTest {
        val store = FakeStore(stored = null)
        repository(store).setPhoto(staff, record.copy(quantity = 4.0, reorderLevel = 1.0), image())

        val stock = store.stock.single().data!!
        assertEquals(4.0, stock["q"])
        assertEquals(1.0, stock["min"])
        assertEquals(1.0, stock["photoRev"])
    }
}
