package `in`.smartie.quotedesk.ui.stock

import `in`.smartie.quotedesk.core.StockPendingStore
import `in`.smartie.quotedesk.data.mapping.Keys
import `in`.smartie.quotedesk.data.model.StockPhotoRecord
import `in`.smartie.quotedesk.data.model.StockRecord
import `in`.smartie.quotedesk.data.repository.StockPhotoRepository
import `in`.smartie.quotedesk.data.repository.StockPhotoStore
import `in`.smartie.quotedesk.data.repository.StockStore
import `in`.smartie.quotedesk.data.repository.StockTransaction
import `in`.smartie.quotedesk.data.repository.StockWriteRepository
import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.domain.Role
import `in`.smartie.quotedesk.domain.StockPhotoCache
import `in`.smartie.quotedesk.domain.StockPhotoImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The view model's half of the photo flow: who may write one, when, and what
 * happens to the cache afterwards.
 *
 * The transaction itself is `StockPhotoWriteTest`'s subject. What is proved
 * here is the layer in front of it — that a Worker's confirm never reaches
 * it, that an offline one never reaches it, that a failure leaves the picture
 * where it was, and that a write invalidates what was cached for the row.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StockPhotoViewModelTest {

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    // --- fakes -------------------------------------------------------------

    private class Drafts : StockPendingStore {
        val saved = MutableStateFlow<Map<String, Double>>(emptyMap())
        override val pending = saved
        override suspend fun setPending(pending: Map<String, Double>) { saved.value = pending }
    }

    private class Store(private val stored: Map<String, Any?>?) : StockStore {
        val photoWrites = mutableListOf<String>()
        val photoDeletes = mutableListOf<String>()
        val movementWrites = mutableListOf<String>()
        var fail = false

        override suspend fun <T> transaction(body: (StockTransaction) -> T): T =
            body(object : StockTransaction {
                override fun readStock(docId: String) = stored
                override fun writeStock(docId: String, data: Map<String, Any?>, merge: Boolean) {
                    if (fail) throw IllegalStateException("Network unavailable")
                }
                override fun writeMovement(docId: String, data: Map<String, Any?>) {
                    movementWrites += docId
                }
                override fun writePhoto(docId: String, data: Map<String, Any?>) {
                    photoWrites += docId
                }
                override fun deletePhoto(docId: String) { photoDeletes += docId }
                override fun deleteStock(docId: String) =
                    throw AssertionError("a quantity or photo write must not delete the row")
                override fun writeStopped(docId: String, data: Map<String, Any?>) =
                    throw AssertionError("a quantity or photo write must not write history")
                override fun stoppedExists(docId: String) = false
            })
    }

    private class Photos(private var bytes: ByteArray?) : StockPhotoStore {
        var reads = 0
        override suspend fun read(documentId: String): StockPhotoRecord? {
            reads++
            return bytes?.let {
                StockPhotoRecord(documentId = documentId, key = documentId, bytes = it, rev = 1.0)
            }
        }
    }

    // --- fixtures ----------------------------------------------------------

    private val admin = Member(uid = "uid_admin", name = "Asha", role = Role.ADMIN)
    private val staff = Member(uid = "uid_staff", name = "Sam", role = Role.STAFF)
    private val worker = Member(uid = "uid_worker", name = "Wes", role = Role.WORKER)

    private val key = Keys.productKey("gateMotors", "SIE1000")

    private fun record(hasPhoto: Boolean = false, photoRev: Double = 0.0) = StockRecord(
        documentId = Keys.stockDocId(key),
        key = key,
        quantity = 7.0,
        reorderLevel = 2.0,
        name = "Sliding gate motor",
        model = "SIE1000",
        group = "gateMotors",
        hasPhoto = hasPhoto,
        photoRev = photoRev
    )

    private fun webp(payload: Int = 256): ByteArray =
        "RIFF".toByteArray() + ByteArray(4) + "WEBP".toByteArray() + ByteArray(payload)

    private fun image() = StockPhotoImage(webp(), 800, 600)

    private fun viewModel(
        member: Member = admin,
        store: Store = Store(mapOf("q" to 7.0, "min" to 2.0)),
        photos: StockPhotoRepository? = null,
        online: MutableStateFlow<Boolean> = MutableStateFlow(true)
    ) = StockViewModel(
        member = member,
        writes = StockWriteRepository(store, now = { 1L }, newMovementId = { "mv_1" }),
        drafts = Drafts(),
        onlineFlow = online,
        photos = photos
    )

    /** Walks the sheet to a confirmable preview, writing nothing. */
    private fun StockViewModel.readyToSave(record: StockRecord) {
        openPhoto(record)
        awaitingPhoto()
        photoPrepared(image())
    }

    // --- who may ------------------------------------------------------------

    @Test
    fun `a Worker opening a bare row gets no sheet`() = runTest {
        val model = viewModel(member = worker)
        model.openPhoto(record())
        assertFalse(model.photo.value.isOpen)
    }

    @Test
    fun `a Worker opening a photographed row gets the picture`() = runTest {
        val model = viewModel(member = worker)
        model.openPhoto(record(hasPhoto = true, photoRev = 1.0))
        assertEquals(PhotoStage.VIEW, model.photo.value.stage)
    }

    @Test
    fun `Staff may manage photos and a Worker may only see them`() = runTest {
        assertTrue(viewModel(member = staff).canManagePhoto())
        assertFalse(viewModel(member = worker).canManagePhoto())
        assertTrue("a picture is exactly what a Worker needs", viewModel(member = worker).canViewPhoto())
    }

    @Test
    fun `a Worker's confirm never reaches the transaction`() = runTest {
        val store = Store(mapOf("q" to 7.0))
        val model = viewModel(member = worker, store = store)
        // A Worker can legitimately reach the picture of a photographed row,
        // so this walks that far and then pushes past the sheet's own
        // buttons. The guard behind them is what has to stop it.
        model.readyToSave(record(hasPhoto = true, photoRev = 1.0))
        assertEquals(PhotoStage.PREVIEW, model.photo.value.stage)

        model.confirmPhoto()

        assertTrue("nothing was written", store.photoWrites.isEmpty())
        assertFalse("and nothing is left spinning", model.photo.value.saving)
    }

    @Test
    fun `a Worker's removal never reaches the transaction either`() = runTest {
        val store = Store(mapOf("q" to 7.0, "hasPhoto" to true, "photoRev" to 1.0))
        val model = viewModel(member = worker, store = store)
        model.openPhoto(record(hasPhoto = true, photoRev = 1.0))

        model.removePhoto()

        assertTrue(store.photoDeletes.isEmpty())
    }

    // --- offline ------------------------------------------------------------

    @Test
    fun `offline a confirm writes nothing`() = runTest {
        val store = Store(mapOf("q" to 7.0))
        val model = viewModel(store = store, online = MutableStateFlow(false))
        model.readyToSave(record())

        model.confirmPhoto()

        assertTrue(store.photoWrites.isEmpty())
        assertEquals("the sheet stays put so it can be retried", PhotoStage.PREVIEW, model.photo.value.stage)
    }

    @Test
    fun `offline a removal writes nothing`() = runTest {
        val store = Store(mapOf("q" to 7.0, "hasPhoto" to true, "photoRev" to 1.0))
        val model = viewModel(store = store, online = MutableStateFlow(false))
        model.openPhoto(record(hasPhoto = true, photoRev = 1.0))

        model.removePhoto()

        assertTrue(store.photoDeletes.isEmpty())
    }

    // --- writing ------------------------------------------------------------

    @Test
    fun `confirming writes the photo, closes the sheet and logs no movement`() = runTest {
        val store = Store(mapOf("q" to 7.0, "min" to 2.0))
        val model = viewModel(store = store)
        model.readyToSave(record())

        model.confirmPhoto()

        assertEquals(1, store.photoWrites.size)
        assertTrue("a photograph is not a movement", store.movementWrites.isEmpty())
        assertEquals(PhotoStage.CLOSED, model.photo.value.stage)
    }

    @Test
    fun `a failed save keeps the picture so it can be tried again`() = runTest {
        val store = Store(mapOf("q" to 7.0, "min" to 2.0)).apply { fail = true }
        val model = viewModel(store = store)
        model.readyToSave(record())

        model.confirmPhoto()

        assertEquals(PhotoStage.PREVIEW, model.photo.value.stage)
        assertFalse("and the spinner is gone", model.photo.value.saving)
        assertTrue(model.photo.value.prepared != null)
    }

    @Test
    fun `removing deletes the photo document and logs no movement`() = runTest {
        val store = Store(mapOf("q" to 7.0, "min" to 2.0, "hasPhoto" to true, "photoRev" to 1.0))
        val model = viewModel(store = store)
        model.openPhoto(record(hasPhoto = true, photoRev = 1.0))

        model.removePhoto()

        assertEquals(1, store.photoDeletes.size)
        assertTrue(store.movementWrites.isEmpty())
        assertEquals(PhotoStage.CLOSED, model.photo.value.stage)
    }

    @Test
    fun `a confirm with nothing prepared does nothing`() = runTest {
        val store = Store(mapOf("q" to 7.0))
        val model = viewModel(store = store)
        model.openPhoto(record())

        model.confirmPhoto()

        assertTrue(store.photoWrites.isEmpty())
    }

    // --- the cache ----------------------------------------------------------

    @Test
    fun `saving a photo drops what was cached for that row`() = runTest {
        val photos = Photos(webp())
        val repository = StockPhotoRepository(photos, StockPhotoCache())
        val store = Store(mapOf("q" to 7.0, "min" to 2.0))
        val model = viewModel(store = store, photos = repository)
        val row = record(hasPhoto = true, photoRev = 1.0)

        // Cached once.
        model.loadPhoto(row)
        model.loadPhoto(row)
        assertEquals(1, photos.reads)

        model.readyToSave(row)
        model.confirmPhoto()

        // The revision has moved, so the old bytes must not answer for it.
        model.loadPhoto(row)
        assertEquals("the replaced picture is not reused", 2, photos.reads)
    }

    @Test
    fun `a row with no photo is never asked for one`() = runTest {
        val photos = Photos(webp())
        val model = viewModel(photos = StockPhotoRepository(photos, StockPhotoCache()))

        assertNull(model.loadPhoto(record()))
        assertEquals(0, photos.reads)
    }

    @Test
    fun `a read that fails is nothing to show, not a crash`() = runTest {
        val failing = object : StockPhotoStore {
            override suspend fun read(documentId: String): StockPhotoRecord =
                throw IllegalStateException("offline")
        }
        val model = viewModel(photos = StockPhotoRepository(failing, StockPhotoCache()))

        assertNull(model.loadPhoto(record(hasPhoto = true, photoRev = 1.0)))
    }

    @Test
    fun `with no repository at all a photo is simply not shown`() = runTest {
        assertNull(viewModel().loadPhoto(record(hasPhoto = true, photoRev = 1.0)))
    }
}
