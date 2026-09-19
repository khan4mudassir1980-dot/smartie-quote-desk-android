package `in`.smartie.quotedesk.ui.stock

import `in`.smartie.quotedesk.core.StockPendingStore
import `in`.smartie.quotedesk.data.mapping.Keys
import `in`.smartie.quotedesk.data.model.ProductRecord
import `in`.smartie.quotedesk.data.model.StockRecord
import `in`.smartie.quotedesk.data.repository.StockStore
import `in`.smartie.quotedesk.data.repository.StockTransaction
import `in`.smartie.quotedesk.data.repository.StockWriteRepository
import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.domain.Role
import `in`.smartie.quotedesk.domain.StockEntry
import `in`.smartie.quotedesk.domain.StockFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StockViewModelTest {

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    // --- fakes -------------------------------------------------------------

    private class Drafts(initial: Map<String, Double> = emptyMap()) : StockPendingStore {
        val saved = MutableStateFlow(initial)
        override val pending = saved
        override suspend fun setPending(pending: Map<String, Double>) { saved.value = pending }
    }

    /** Fails for any key in [failFor], so a mixed result can be driven. */
    private class Store(
        private val stored: Map<String, Double> = emptyMap(),
        private val failFor: Set<String> = emptySet()
    ) : StockStore {
        val stockWrites = mutableListOf<String>()
        val movementWrites = mutableListOf<String>()
        var transactions = 0

        override suspend fun <T> transaction(body: (StockTransaction) -> T): T {
            transactions++
            return body(object : StockTransaction {
                override fun readStock(docId: String): Map<String, Any?>? =
                    stored[docId]?.let { mapOf("q" to it) }

                override fun writeStock(docId: String, data: Map<String, Any?>, merge: Boolean) {
                    if (docId in failFor) throw IllegalStateException("Network unavailable")
                    stockWrites += docId
                }

                override fun writeMovement(docId: String, data: Map<String, Any?>) {
                    movementWrites += docId
                }

                // The view model's quantity paths must never reach a photo.
                override fun writePhoto(docId: String, data: Map<String, Any?>) =
                    throw AssertionError("a quantity write must not touch a photo")

                override fun deletePhoto(docId: String) =
                    throw AssertionError("a quantity write must not delete a photo")
            })
        }
    }

    // --- fixtures ----------------------------------------------------------

    private val admin = Member(uid = "uid_admin", name = "Asha", role = Role.ADMIN)
    private val staff = Member(uid = "uid_staff", name = "Sam", role = Role.STAFF)
    private val worker = Member(uid = "uid_worker", name = "Wes", role = Role.WORKER)

    private fun record(model: String, quantity: Double = 7.0, reorder: Double = 2.0): StockRecord {
        val key = Keys.productKey("gateMotors", model)
        return StockRecord(
            documentId = Keys.stockDocId(key),
            key = key,
            quantity = quantity,
            reorderLevel = reorder,
            name = "Motor $model",
            model = model,
            group = "gateMotors"
        )
    }

    private val motor = record("SIE1000")
    private val other = record("SIE2000")

    private fun viewModel(
        member: Member = admin,
        store: Store = Store(mapOf(motor.documentId to 7.0, other.documentId to 7.0)),
        drafts: Drafts = Drafts(),
        online: MutableStateFlow<Boolean> = MutableStateFlow(true)
    ) = StockViewModel(
        member = member,
        writes = StockWriteRepository(store, now = { 1_700_000_000_000L }, newMovementId = { "mv_1" }),
        drafts = drafts,
        onlineFlow = online
    )

    /**
     * `messages` has no replay, so nothing emitted before a collector arrives
     * is ever seen. Every test that asserts on a message collects first.
     */
    private fun TestScope.messagesOf(model: StockViewModel): List<String> {
        val received = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            model.messages.collect { received += it }
        }
        return received
    }

    // --- looking at the board ----------------------------------------------

    @Test
    fun `the filter toggles off when the selected tile is tapped again`() = runTest {
        val model = viewModel()
        model.toggleFilter(StockFilter.LOW)
        assertEquals(StockFilter.LOW, model.filter.value)
        model.toggleFilter(StockFilter.LOW)
        assertEquals(StockFilter.ALL, model.filter.value)
        model.toggleFilter(StockFilter.OUT)
        assertEquals(StockFilter.OUT, model.filter.value)
    }

    @Test
    fun `the query is held as typed`() = runTest {
        val model = viewModel()
        model.setQuery("  padlock ")
        assertEquals("  padlock ", model.query.value)
    }

    // --- pending counts -----------------------------------------------------

    @Test
    fun `plus and minus accumulate and persist to the device`() = runTest {
        val drafts = Drafts()
        val model = viewModel(drafts = drafts)
        model.changePending(motor.key, 1.0)
        model.changePending(motor.key, 1.0)
        model.changePending(motor.key, -1.0)

        assertEquals(mapOf(motor.key to 1.0), model.pending.value)
        assertEquals(mapOf(motor.key to 1.0), drafts.saved.value)
    }

    @Test
    fun `stepping back to zero leaves no draft behind`() = runTest {
        val drafts = Drafts()
        val model = viewModel(drafts = drafts)
        model.changePending(motor.key, 1.0)
        model.changePending(motor.key, -1.0)
        assertTrue(model.pending.value.isEmpty())
        assertTrue(drafts.saved.value.isEmpty())
    }

    /** Process recreation: a new view model finds what the old one left. */
    @Test
    fun `pending counts survive the process being recreated`() = runTest {
        val drafts = Drafts(mapOf(motor.key to 4.0, other.key to -1.0))
        val revived = viewModel(drafts = drafts)
        assertEquals(mapOf(motor.key to 4.0, other.key to -1.0), revived.pending.value)
    }

    @Test
    fun `clearing is explicit and clears the device too`() = runTest {
        val drafts = Drafts()
        val model = viewModel(drafts = drafts)
        model.changePending(motor.key, 3.0)
        model.clearPending(motor.key)
        assertTrue(model.pending.value.isEmpty())
        assertTrue(drafts.saved.value.isEmpty())

        model.changePending(motor.key, 3.0)
        model.changePending(other.key, 2.0)
        model.clearAllPending()
        assertTrue(model.pending.value.isEmpty())
    }

    @Test
    fun `a Worker cannot even build a pending count`() = runTest {
        val model = viewModel(member = worker)
        val messages = messagesOf(model)
        model.changePending(motor.key, 1.0)
        assertTrue(model.pending.value.isEmpty())
        assertEquals(listOf(StockViewModel.NOT_ALLOWED), messages)
    }

    // --- offline -------------------------------------------------------------

    @Test
    fun `Done offline saves nothing and says why`() = runTest {
        val store = Store(mapOf(motor.documentId to 7.0))
        val model = viewModel(store = store, online = MutableStateFlow(false))
        val messages = messagesOf(model)
        model.changePending(motor.key, 2.0)
        model.save(motor)

        assertEquals(listOf(StockViewModel.OFFLINE), messages)
        assertEquals("no transaction may open offline", 0, store.transactions)
        assertEquals("the draft is kept", mapOf(motor.key to 2.0), model.pending.value)
    }

    /** The whole point of online-only: reconnecting is not a trigger. */
    @Test
    fun `coming back online never saves by itself`() = runTest {
        val store = Store(mapOf(motor.documentId to 7.0))
        val online = MutableStateFlow(false)
        val model = viewModel(store = store, online = online)
        model.changePending(motor.key, 2.0)

        online.value = true

        assertEquals("nothing may commit itself", 0, store.transactions)
        assertEquals(mapOf(motor.key to 2.0), model.pending.value)
    }

    // --- saving --------------------------------------------------------------

    @Test
    fun `a successful Done clears only that row`() = runTest {
        val store = Store(mapOf(motor.documentId to 7.0, other.documentId to 7.0))
        val model = viewModel(store = store)
        model.changePending(motor.key, 2.0)
        model.changePending(other.key, 3.0)

        model.save(motor)

        assertEquals(listOf(motor.documentId), store.stockWrites)
        assertEquals(mapOf(other.key to 3.0), model.pending.value)
    }

    @Test
    fun `a failed Done keeps the draft and never reports saved`() = runTest {
        val store = Store(mapOf(motor.documentId to 7.0), failFor = setOf(motor.documentId))
        val model = viewModel(store = store)
        val messages = messagesOf(model)
        model.changePending(motor.key, 2.0)

        model.save(motor)

        assertEquals(mapOf(motor.key to 2.0), model.pending.value)
        val message = messages.single()
        assertFalse("never say saved when the write failed", message.contains("Saved"))
        assertTrue(message, message.contains("Network unavailable"))
    }

    @Test
    fun `saving all reports honestly when some succeed and some fail`() = runTest {
        val store = Store(
            stored = mapOf(motor.documentId to 7.0, other.documentId to 7.0),
            failFor = setOf(other.documentId)
        )
        val model = viewModel(store = store)
        val messages = messagesOf(model)
        model.changePending(motor.key, 2.0)
        model.changePending(other.key, 5.0)

        model.saveAll(listOf(motor, other))

        // The one that saved is cleared; the one that failed is kept to retry.
        assertEquals(mapOf(other.key to 5.0), model.pending.value)
        val message = messages.single()
        assertTrue(message, message.startsWith("1 saved, 1 still to save"))
    }

    @Test
    fun `saving all when every row succeeds clears them all`() = runTest {
        val store = Store(mapOf(motor.documentId to 7.0, other.documentId to 7.0))
        val model = viewModel(store = store)
        val messages = messagesOf(model)
        model.changePending(motor.key, 2.0)
        model.changePending(other.key, 5.0)

        model.saveAll(listOf(motor, other))

        assertTrue(model.pending.value.isEmpty())
        assertEquals(listOf("2 items saved"), messages)
    }

    @Test
    fun `a second Done for a row already saving does nothing`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val blocking = object : StockStore {
            var started = 0
            override suspend fun <T> transaction(body: (StockTransaction) -> T): T {
                started++
                gate.await()
                return body(object : StockTransaction {
                    override fun readStock(docId: String) = mapOf<String, Any?>("q" to 7.0)
                    override fun writeStock(docId: String, data: Map<String, Any?>, merge: Boolean) = Unit
                    override fun writeMovement(docId: String, data: Map<String, Any?>) = Unit
                    override fun writePhoto(docId: String, data: Map<String, Any?>) = Unit
                    override fun deletePhoto(docId: String) = Unit
                })
            }
        }
        val model = StockViewModel(
            member = admin,
            writes = StockWriteRepository(blocking, now = { 1L }, newMovementId = { "mv_1" }),
            drafts = Drafts(),
            onlineFlow = MutableStateFlow(true)
        )
        model.changePending(motor.key, 2.0)

        model.save(motor)
        model.save(motor)
        model.save(motor)

        assertEquals("double taps must not open a second transaction", 1, blocking.started)
        assertTrue("the row is marked in flight", motor.key in model.saving.value)

        // Let it finish, so nothing is left running and the guard clears.
        gate.complete(Unit)
        assertTrue(model.saving.value.isEmpty())
        assertTrue(model.pending.value.isEmpty())
    }

    // --- edit, add, pin ------------------------------------------------------

    @Test
    fun `Staff may edit the reorder level and a Worker may not`() = runTest {
        val staffModel = viewModel(member = staff)
        assertTrue(staffModel.canSetReorderLevel())
        assertFalse(staffModel.canSetExactQuantity())

        val workerModel = viewModel(member = worker)
        assertFalse(workerModel.canSetReorderLevel())
        assertFalse(workerModel.canAdjust())
        assertFalse(workerModel.canPin())
        assertFalse(workerModel.canStopTracking())
        assertFalse(workerModel.canSetExactQuantity())

        val adminModel = viewModel()
        assertTrue(adminModel.canSetExactQuantity())
        assertTrue(adminModel.canStopTracking())
    }

    @Test
    fun `a Worker's edit is refused before any transaction opens`() = runTest {
        val store = Store(mapOf(motor.documentId to 7.0))
        val model = viewModel(member = worker, store = store)
        val messages = messagesOf(model)
        model.edit(motor, quantity = 99.0, reorderLevel = 2.0, note = "")
        assertEquals(listOf(StockViewModel.NOT_ALLOWED), messages)
        assertEquals(0, store.transactions)
    }

    @Test
    fun `a note-only edit saves the note and writes no movement`() = runTest {
        val store = Store(mapOf(motor.documentId to 7.0))
        val model = viewModel(store = store)
        val messages = messagesOf(model)
        model.edit(motor, quantity = 7.0, reorderLevel = 2.0, note = "Top shelf")

        assertEquals(listOf(motor.documentId), store.stockWrites)
        assertTrue("a note-only edit logs nothing", store.movementWrites.isEmpty())
        assertEquals(listOf(StockViewModel.SAVED), messages)
    }

    @Test
    fun `an edit that changes nothing says so rather than claiming a save`() = runTest {
        val store = Store(mapOf(motor.documentId to 7.0))
        val model = viewModel(store = store)
        val messages = messagesOf(model)
        model.edit(motor, quantity = 7.0, reorderLevel = 2.0, note = "")
        assertEquals(listOf(StockViewModel.NOTHING_CHANGED), messages)
        assertTrue(store.stockWrites.isEmpty())
    }

    @Test
    fun `adding a catalogue product uses its immutable identity`() = runTest {
        val store = Store()
        val model = viewModel(store = store)
        val renamed = ProductRecord(
            documentId = Keys.productDocId("gateMotors", "SIE1000"),
            key = Keys.productKey("gateMotors", "SIE1000"),
            group = "gateMotors",
            seedModel = "SIE1000",
            // Renamed since it was seeded; identity must not follow.
            model = "SIE-1000 PRO",
            name = "Sliding gate motor"
        )
        val messages = messagesOf(model)
        model.addFromProduct(renamed, quantity = 4.0, reorderLevel = 1.0, note = "")

        assertEquals(listOf("gateMotors|SIE1000"), store.stockWrites)
        assertEquals(listOf(StockViewModel.ADDED), messages)
    }

    @Test
    fun `adding a manual item slugs what was typed`() = runTest {
        val store = Store()
        val model = viewModel(store = store)
        model.addManual("Shed Padlock", "Brass padlock", "", "each", 2.0, 0.0, "")
        assertEquals(listOf("manualstock|shed_padlock"), store.stockWrites)
    }

    @Test
    fun `a blank manual item is refused before any transaction opens`() = runTest {
        val store = Store()
        val model = viewModel(store = store)
        val messages = messagesOf(model)
        model.addManual("", "", "", "each", 1.0, 0.0, "")
        assertEquals(0, store.transactions)
        assertEquals(listOf(StockEntry.BLANK), messages)
    }

    @Test
    fun `pinning writes stock and no movement`() = runTest {
        val store = Store(mapOf(motor.documentId to 7.0))
        val model = viewModel(member = staff, store = store)
        val messages = messagesOf(model)
        model.togglePin(motor)
        assertEquals(listOf(motor.documentId), store.stockWrites)
        assertTrue(store.movementWrites.isEmpty())
        assertEquals(listOf(StockViewModel.PINNED), messages)
    }

    @Test
    fun `a Worker cannot pin or stop tracking`() = runTest {
        val store = Store(mapOf(motor.documentId to 7.0))
        val model = viewModel(member = worker, store = store)
        val messages = messagesOf(model)
        model.togglePin(motor)
        assertEquals(listOf(StockViewModel.NOT_ALLOWED), messages)
        assertEquals(0, store.transactions)
    }

    @Test
    fun `Staff cannot stop tracking`() = runTest {
        val store = Store(mapOf(motor.documentId to 7.0))
        val model = viewModel(member = staff, store = store)
        val messages = messagesOf(model)
        model.stopTracking(motor)
        assertEquals(listOf(StockViewModel.NOT_ALLOWED), messages)
        assertEquals(0, store.transactions)
    }
}
