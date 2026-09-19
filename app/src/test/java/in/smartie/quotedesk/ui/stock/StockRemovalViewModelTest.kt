package `in`.smartie.quotedesk.ui.stock

import `in`.smartie.quotedesk.core.StockPendingStore
import `in`.smartie.quotedesk.data.mapping.Keys
import `in`.smartie.quotedesk.data.model.StockRecord
import `in`.smartie.quotedesk.data.model.StoppedStockRecord
import `in`.smartie.quotedesk.data.repository.StockStore
import `in`.smartie.quotedesk.data.repository.StockTransaction
import `in`.smartie.quotedesk.data.repository.StockWriteRepository
import `in`.smartie.quotedesk.data.repository.StoppedStockRepository
import `in`.smartie.quotedesk.data.repository.StoppedStockStore
import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.domain.Role
import `in`.smartie.quotedesk.domain.StockRemoval
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Removing an item, and clearing what is left, from the screen's side.
 *
 * Its own class: `StockViewModelTest` is already near the size where
 * Robolectric's native-object registry starts to matter, and these have their
 * own fakes anyway.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StockRemovalViewModelTest {

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    // --- fakes ---------------------------------------------------------------

    private class Drafts : StockPendingStore {
        val saved = MutableStateFlow<Map<String, Double>>(emptyMap())
        override val pending = saved
        override suspend fun setPending(pending: Map<String, Double>) { saved.value = pending }
    }

    private class Store(
        private val present: Set<String>,
        private val failing: Boolean = false
    ) : StockStore {
        val stoppedWrites = mutableListOf<String>()
        val stockDeletes = mutableListOf<String>()
        val movements = mutableListOf<String>()

        override suspend fun <T> transaction(body: (StockTransaction) -> T): T {
            val outcome = runCatching {
                body(object : StockTransaction {
                    override fun readStock(docId: String): Map<String, Any?>? =
                        if (docId in present) mapOf("q" to 7.0) else null
                    override fun writeStock(docId: String, data: Map<String, Any?>, merge: Boolean) =
                        throw AssertionError("a removal must not write the row")
                    override fun writeMovement(docId: String, data: Map<String, Any?>) {
                        movements += docId
                    }
                    override fun writePhoto(docId: String, data: Map<String, Any?>) =
                        throw AssertionError("a removal must not write a photo")
                    override fun deletePhoto(docId: String) = Unit
                    override fun deleteStock(docId: String) {
                        if (failing) throw IllegalStateException("Network unavailable")
                        stockDeletes += docId
                    }
                    override fun writeStopped(docId: String, data: Map<String, Any?>) {
                        stoppedWrites += docId
                    }
                    override fun stoppedExists(docId: String) = false
                })
            }
            if (outcome.isFailure) {
                stoppedWrites.clear(); stockDeletes.clear()
                throw outcome.exceptionOrNull()!!
            }
            return outcome.getOrThrow()
        }
    }

    private class History : StoppedStockStore {
        val deleted = mutableListOf<String>()
        override suspend fun deleteBatch(ids: List<String>) { deleted += ids }
    }

    // --- fixtures ------------------------------------------------------------

    private val admin = Member(uid = "uid_admin", name = "Asha", role = Role.ADMIN)
    private val staff = Member(uid = "uid_staff", name = "Sam", role = Role.STAFF)
    private val worker = Member(uid = "uid_worker", name = "Wes", role = Role.WORKER)

    private fun record(model: String, archived: Boolean = false): StockRecord {
        val key = Keys.productKey("gateMotors", model)
        return StockRecord(
            documentId = Keys.stockDocId(key),
            key = key,
            quantity = 7.0,
            reorderLevel = 2.0,
            name = "Motor $model",
            model = model,
            group = "gateMotors",
            archived = archived
        )
    }

    private val motor = record("SIE1000")

    private fun entry(id: String) = StoppedStockRecord(id = id, key = "gateMotors|X")

    private fun viewModel(
        member: Member = admin,
        store: Store = Store(setOf(motor.documentId)),
        history: History = History(),
        online: MutableStateFlow<Boolean> = MutableStateFlow(true)
    ) = StockViewModel(
        member = member,
        writes = StockWriteRepository(store, now = { 1_700_000_000_000L }, newMovementId = { "mv_1" }),
        drafts = Drafts(),
        onlineFlow = online,
        history = StoppedStockRepository(history)
    )

    private fun TestScope.messagesOf(model: StockViewModel): List<String> {
        val received = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            model.messages.collect { received += it }
        }
        return received
    }

    // --- asking --------------------------------------------------------------

    @Test
    fun `asking opens the confirmation and writes nothing`() = runTest {
        val store = Store(setOf(motor.documentId))
        val model = viewModel(store = store)

        model.askRemove(motor)

        assertEquals(motor, model.removing.value)
        assertTrue("asking is not removing", store.stockDeletes.isEmpty())
        assertTrue(store.stoppedWrites.isEmpty())
    }

    @Test
    fun `cancelling closes it and writes nothing`() = runTest {
        val store = Store(setOf(motor.documentId))
        val model = viewModel(store = store)
        model.askRemove(motor)

        model.cancelRemove()

        assertNull(model.removing.value)
        assertTrue(store.stockDeletes.isEmpty())
        assertTrue(store.stoppedWrites.isEmpty())
    }

    // --- removing ------------------------------------------------------------

    @Test
    fun `confirming removes the row, records it, and says so`() = runTest {
        val store = Store(setOf(motor.documentId))
        val model = viewModel(store = store)
        val messages = messagesOf(model)
        model.askRemove(motor)

        model.removeFromStock()

        assertEquals(listOf(motor.documentId), store.stockDeletes)
        assertEquals(1, store.stoppedWrites.size)
        assertEquals(listOf(REMOVED), messages)
        assertEquals("Removed from stock", REMOVED)
        assertNull("and the sheet closes", model.removing.value)
    }

    @Test
    fun `a removal is never a movement`() = runTest {
        val store = Store(setOf(motor.documentId))
        val model = viewModel(store = store)
        model.askRemove(motor)

        model.removeFromStock()

        assertTrue("nothing moved; the row ceased to exist", store.movements.isEmpty())
    }

    @Test
    fun `a failed removal leaves the sheet open so it can be tried again`() = runTest {
        val store = Store(setOf(motor.documentId), failing = true)
        val model = viewModel(store = store)
        val messages = messagesOf(model)
        model.askRemove(motor)

        model.removeFromStock()

        assertEquals(motor, model.removing.value)
        assertTrue(store.stockDeletes.isEmpty())
        assertEquals(listOf("Network unavailable"), messages)
    }

    @Test
    fun `offline it refuses before anything leaves the device`() = runTest {
        val store = Store(setOf(motor.documentId))
        val model = viewModel(store = store, online = MutableStateFlow(false))
        val messages = messagesOf(model)
        model.askRemove(motor)

        model.removeFromStock()

        assertEquals(listOf(StockViewModel.OFFLINE), messages)
        assertTrue(store.stockDeletes.isEmpty())
    }

    @Test
    fun `the displayed Manager and Staff are refused and see no confirmation`() = runTest {
        for (member in listOf(staff, worker)) {
            val store = Store(setOf(motor.documentId))
            val model = viewModel(member = member, store = store)
            val messages = messagesOf(model)

            model.askRemove(motor)
            model.removeFromStock()

            assertEquals(listOf(StockViewModel.NOT_ALLOWED), messages)
            assertNull(model.removing.value)
            assertTrue(store.stockDeletes.isEmpty())
        }
    }

    // --- the history ---------------------------------------------------------

    @Test
    fun `history is collapsed until somebody opens it`() = runTest {
        val model = viewModel()
        assertFalse(model.historyExpanded.value)
        model.toggleHistory()
        assertTrue(model.historyExpanded.value)
        model.toggleHistory()
        assertFalse(model.historyExpanded.value)
    }

    @Test
    fun `clearing deletes exactly the entries it was given`() = runTest {
        val history = History()
        val model = viewModel(history = history)
        val messages = messagesOf(model)

        model.clearHistory(listOf(entry("sr_1"), entry("sr_2")))

        assertEquals(listOf("sr_1", "sr_2"), history.deleted)
        assertEquals(listOf(HISTORY_CLEARED), messages)
        assertEquals("Stopped-item history cleared.", HISTORY_CLEARED)
    }

    @Test
    fun `cancelling a clear deletes nothing`() = runTest {
        val history = History()
        val model = viewModel(history = history)
        model.askClearHistory()
        assertTrue(model.clearing.value)

        model.cancelClearHistory()

        assertFalse(model.clearing.value)
        assertTrue(history.deleted.isEmpty())
    }

    @Test
    fun `the displayed Manager and Staff cannot clear`() = runTest {
        for (member in listOf(staff, worker)) {
            val history = History()
            val model = viewModel(member = member, history = history)
            val messages = messagesOf(model)

            model.askClearHistory()
            model.clearHistory(listOf(entry("sr_1")))

            assertFalse(model.clearing.value)
            assertTrue(history.deleted.isEmpty())
            assertEquals(
                listOf(StockViewModel.NOT_ALLOWED, StockViewModel.NOT_ALLOWED),
                messages
            )
        }
    }

    // --- rows the old stop-tracking left behind -------------------------------

    @Test
    fun `a legacy row converts under its deterministic id and goes`() = runTest {
        val legacy = record("SIE-EXTRECEIVER", archived = true)
        val store = Store(setOf(legacy.documentId))
        val model = viewModel(store = store)

        model.convertLegacyStopped(listOf(legacy))

        assertEquals(
            listOf(StockRemoval.legacyEventId(legacy.documentId)),
            store.stoppedWrites
        )
        assertEquals(listOf(legacy.documentId), store.stockDeletes)
    }

    @Test
    fun `an ordinary active row is never swept`() = runTest {
        val store = Store(setOf(motor.documentId))
        val model = viewModel(store = store)

        model.convertLegacyStopped(listOf(motor))

        assertTrue("an item nobody stopped must not be deleted", store.stockDeletes.isEmpty())
        assertTrue(store.stoppedWrites.isEmpty())
    }

    @Test
    fun `a second sweep of the same row does nothing`() = runTest {
        val legacy = record("SIE-EXTRECEIVER", archived = true)
        val store = Store(setOf(legacy.documentId))
        val model = viewModel(store = store)

        model.convertLegacyStopped(listOf(legacy))
        model.convertLegacyStopped(listOf(legacy))

        assertEquals(1, store.stockDeletes.size)
    }

    @Test
    fun `nobody but an Owner or Administrator sweeps, and nobody sweeps offline`() = runTest {
        val legacy = record("SIE-EXTRECEIVER", archived = true)

        val refused = Store(setOf(legacy.documentId))
        viewModel(member = staff, store = refused).convertLegacyStopped(listOf(legacy))
        assertTrue(refused.stockDeletes.isEmpty())

        val offline = Store(setOf(legacy.documentId))
        viewModel(store = offline, online = MutableStateFlow(false))
            .convertLegacyStopped(listOf(legacy))
        assertTrue(offline.stockDeletes.isEmpty())
    }

    @Test
    fun `a failed sweep leaves the row recoverable and tries again`() = runTest {
        val legacy = record("SIE-EXTRECEIVER", archived = true)
        val store = Store(setOf(legacy.documentId), failing = true)
        val model = viewModel(store = store)

        model.convertLegacyStopped(listOf(legacy))
        assertTrue("still there to try again", store.stockDeletes.isEmpty())

        // And the next sweep does try: a failure is not remembered as done.
        val second = Store(setOf(legacy.documentId))
        viewModel(store = second).convertLegacyStopped(listOf(legacy))
        assertEquals(listOf(legacy.documentId), second.stockDeletes)
    }
}
