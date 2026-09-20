package `in`.smartie.quotedesk.ui.purchase

import com.google.firebase.firestore.FirebaseFirestoreException
import `in`.smartie.quotedesk.data.mapping.DocData
import `in`.smartie.quotedesk.data.model.PurchaseRecord
import `in`.smartie.quotedesk.data.model.UrgencyV2
import `in`.smartie.quotedesk.data.repository.FirestoreFailures
import `in`.smartie.quotedesk.data.repository.PurchaseStore
import `in`.smartie.quotedesk.data.repository.PurchaseTransaction
import `in`.smartie.quotedesk.data.repository.PurchaseWriteRepository
import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.domain.PurchaseDraft
import `in`.smartie.quotedesk.domain.PurchaseWrite
import `in`.smartie.quotedesk.domain.Role
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
 * The Purchase tab's side of the six operations.
 *
 * The repository under test is the **real** one over a fake store, so these
 * exercise the whole path a tap takes — permission, transaction, planner —
 * rather than a stub standing in for most of it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PurchaseViewModelTest {

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    // --- fakes ---------------------------------------------------------------

    private class Store(
        private val stored: Map<String, Any?>? = null,
        private val failWith: Throwable? = null
    ) : PurchaseStore {
        val writes = mutableListOf<Map<String, Any?>>()
        var attempts = 0

        override suspend fun <T> transaction(body: (PurchaseTransaction) -> T): T {
            attempts++
            failWith?.let { throw it }
            return body(object : PurchaseTransaction {
                override fun read(docId: String): DocData? = stored?.let { DocData(docId, it) }
                override fun write(docId: String, data: Map<String, Any?>, merge: Boolean) {
                    writes += data
                }
            })
        }
    }

    // --- fixtures ------------------------------------------------------------

    private val owner = Member(uid = "uid_owner", name = "Omar", role = Role.OWNER)
    private val admin = Member(uid = "uid_admin", name = "Asha", role = Role.ADMIN)
    private val staff = Member(uid = "uid_staff", name = "Sam", role = Role.STAFF)
    private val worker = Member(uid = "uid_worker", name = "Wes", role = Role.WORKER)

    /** The document as Firestore holds it, in the PWA's own field names. */
    private fun row(
        qty: Any = 6.0,
        received: Any? = null,
        receivedBy: String? = null
    ): Map<String, Any?> = buildMap {
        put("id", "pr_one")
        put("name", "Sliding gate rack")
        put("qty", qty)
        put("urgency", "urgent")
        put("status", if (received == null) "Needed" else "Received")
        put("rev", 3)
        put("updated", 1_690_000_000_000L)
        if (received != null) put("received", received)
        if (receivedBy != null) put("rcvBy", receivedBy)
    }

    private fun record(
        id: String = "pr_one",
        createdAt: Long = 1_000,
        received: Boolean = false,
        deleted: Boolean = false
    ) = PurchaseRecord(
        id = id,
        name = "Sliding gate rack",
        quantity = 6.0,
        urgency = UrgencyV2.URGENT,
        status = if (received) "Received" else "Needed",
        createdAt = createdAt,
        received = received,
        deleted = deleted,
        revision = 1
    )

    private val draft = PurchaseDraft(name = "Remote handsets", quantity = 4.0)

    private fun viewModel(
        member: Member = admin,
        store: Store = Store(),
        requirements: MutableStateFlow<List<PurchaseRecord>> = MutableStateFlow(emptyList()),
        online: MutableStateFlow<Boolean> = MutableStateFlow(true)
    ) = PurchaseViewModel(
        member = member,
        writes = PurchaseWriteRepository(
            store,
            now = { 1_700_000_000_000L },
            newId = { "pr_generated" }
        ),
        requirements = requirements,
        onlineFlow = online
    )

    private fun TestScope.messagesOf(model: PurchaseViewModel): List<String> {
        val received = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            model.messages.collect { received += it }
        }
        return received
    }

    // --- what the board shows ---------------------------------------------------

    @Test
    fun `the board stops loading the moment the listener says anything`() = runTest {
        val rows = MutableStateFlow<List<PurchaseRecord>>(emptyList())
        val model = viewModel(requirements = rows)
        // Collected eagerly, so the first emission has already landed — and an
        // empty list is an answer, not a screen still waiting for one.
        assertFalse("an empty list is an answer", model.loading.value)
        assertTrue(model.active.value.isEmpty())
    }

    @Test
    fun `the newest requirement is first and a removed one is in neither list`() = runTest {
        val rows = MutableStateFlow(
            listOf(
                record("pr_old", createdAt = 1_000),
                record("pr_new", createdAt = 3_000),
                record("pr_done", createdAt = 2_000, received = true),
                record("pr_gone", createdAt = 4_000, deleted = true)
            )
        )
        val model = viewModel(requirements = rows)

        assertEquals(listOf("pr_new", "pr_old"), model.active.value.map { it.id })
        assertEquals(listOf("pr_done"), model.closed.value.map { it.id })
    }

    // --- adding ------------------------------------------------------------------

    @Test
    fun `adding writes once and closes the panel`() = runTest {
        val store = Store()
        val model = viewModel(store = store)
        val messages = messagesOf(model)
        model.open(PurchaseSheet.ADD)

        model.add(draft)

        assertEquals(1, store.writes.size)
        assertEquals("pr_generated", store.writes.single()["id"])
        assertEquals(listOf(PurchaseViewModel.ADDED), messages)
        assertFalse("a success closes the panel", model.sheet.value.isOpen)
    }

    @Test
    fun `an unusable draft is refused before anything reaches the wire`() = runTest {
        val store = Store()
        val model = viewModel(store = store)
        val messages = messagesOf(model)

        model.add(PurchaseDraft(name = "  ", quantity = 4.0))
        model.add(PurchaseDraft(name = "Handsets", quantity = 0.0))

        assertEquals(listOf(PurchaseWrite.NO_NAME, PurchaseWrite.NOT_POSITIVE), messages)
        assertEquals(0, store.attempts)
    }

    @Test
    fun `a Worker may add, which is the one thing they may do`() = runTest {
        val store = Store()
        val model = viewModel(member = worker, store = store)
        val messages = messagesOf(model)

        model.add(draft)

        assertEquals(listOf(PurchaseViewModel.ADDED), messages)
        assertEquals("uid_worker", store.writes.single()["byUid"])
    }

    // --- offline and duplicate taps ------------------------------------------------

    @Test
    fun `offline nothing is attempted and the reason is given`() = runTest {
        val store = Store(stored = row())
        val model = viewModel(store = store, online = MutableStateFlow(false))
        val messages = messagesOf(model)

        model.add(draft)
        model.markReceived(record(), 4.0)
        model.remove(record())

        assertEquals(3, messages.size)
        assertTrue(messages.all { it == PurchaseViewModel.OFFLINE })
        assertEquals("nothing may reach the wire offline", 0, store.attempts)
    }

    @Test
    fun `a second tap while a write is in flight does nothing`() = runTest {
        // The store never returns until released, so the first write is still
        // on the wire when the second tap arrives.
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val slow = object : PurchaseStore {
            var attempts = 0
            override suspend fun <T> transaction(body: (PurchaseTransaction) -> T): T {
                attempts++
                gate.await()
                return body(object : PurchaseTransaction {
                    override fun read(docId: String): DocData? = DocData(docId, row())
                    override fun write(docId: String, data: Map<String, Any?>, merge: Boolean) = Unit
                })
            }
        }
        val model = PurchaseViewModel(
            member = admin,
            writes = PurchaseWriteRepository(slow, now = { 1L }, newId = { "pr_generated" }),
            requirements = MutableStateFlow(emptyList()),
            onlineFlow = MutableStateFlow(true)
        )

        model.markReceived(record(), 4.0)
        assertTrue("the id is held while the write is on the wire", "pr_one" in model.saving.value)
        model.markReceived(record(), 4.0)

        assertEquals("the second tap must not open a second transaction", 1, slow.attempts)
        gate.complete(Unit)
    }

    // --- who may do what -------------------------------------------------------------

    @Test
    fun `a Worker is refused every change, and the panel never opens`() = runTest {
        val store = Store(stored = row())
        val model = viewModel(member = worker, store = store)
        val messages = messagesOf(model)

        model.open(PurchaseSheet.EDIT, record())
        model.open(PurchaseSheet.RECEIVE, record())
        model.open(PurchaseSheet.REOPEN, record())
        model.open(PurchaseSheet.REMOVE, record())

        assertFalse(model.sheet.value.isOpen)
        assertEquals(4, messages.size)
        assertEquals(0, store.attempts)
    }

    @Test
    fun `the displayed Manager edits and receives but is refused a reopen`() = runTest {
        val edits = Store(stored = row())
        val editor = viewModel(member = staff, store = edits)
        editor.edit(record(), "Remote handsets", 4.0, UrgencyV2.NORMAL, "")
        assertEquals(1, edits.writes.size)

        val receives = Store(stored = row())
        viewModel(member = staff, store = receives).markReceived(record(), 4.0)
        assertEquals(1, receives.writes.size)

        val reopens = Store(stored = row(received = true))
        val model = viewModel(member = staff, store = reopens)
        val messages = messagesOf(model)

        // The panel will not open, and the operation behind it is refused too —
        // this predicate is the whole of the enforcement, because the v9 rules
        // let any non-Worker update a requirement.
        model.open(PurchaseSheet.REOPEN, record(received = true))
        model.reopen(record(received = true))

        assertFalse(model.sheet.value.isOpen)
        assertEquals(0, reopens.attempts)
        assertEquals(PurchaseViewModel.NOT_ALLOWED_REOPEN, messages.first())
    }

    @Test
    fun `an Owner and an Administrator reopen, and the received fields go`() = runTest {
        for (member in listOf(owner, admin)) {
            val store = Store(stored = row(received = true))
            val model = viewModel(member = member, store = store)
            val messages = messagesOf(model)

            model.reopen(record(received = true))

            val written = store.writes.single()
            assertEquals(member.uid, written["upUid"])
            assertEquals(false, written["received"])
            assertEquals(PurchaseWrite.STATUS_NEEDED, written["status"])
            assertEquals(listOf(PurchaseViewModel.REOPENED), messages)
        }
    }

    @Test
    fun `removing is a soft delete an Administrator may make`() = runTest {
        val store = Store(stored = row())
        val model = viewModel(store = store)
        val messages = messagesOf(model)

        model.remove(record())

        assertEquals(true, store.writes.single()["del"])
        assertEquals(listOf(PurchaseViewModel.REMOVED), messages)
    }

    @Test
    fun `capabilities say what each role may do`() = runTest {
        assertTrue(viewModel(member = worker).capabilities().add)
        assertFalse(viewModel(member = worker).capabilities().anyRowAction)
        assertTrue(viewModel(member = staff).capabilities().receive)
        assertFalse(viewModel(member = staff).capabilities().reopen)
        assertTrue(viewModel(member = admin).capabilities().reopen)
        assertTrue(viewModel(member = owner).capabilities().remove)
    }

    // --- refusals, conflicts and failures ---------------------------------------------

    @Test
    fun `a requirement somebody else received is refused by name`() = runTest {
        val store = Store(stored = row(received = true, receivedBy = "Omar"))
        val model = viewModel(store = store)
        val messages = messagesOf(model)
        model.open(PurchaseSheet.RECEIVE, record())

        model.markReceived(record(), 4.0)

        assertEquals(listOf("Already received by Omar"), messages)
        assertEquals("a refusal writes nothing", 0, store.writes.size)
        assertTrue("the panel stays open so it can be looked at", model.sheet.value.isOpen)
    }

    @Test
    fun `losing a race says so, and is never retried`() = runTest {
        val store = Store(
            stored = row(),
            failWith = FirebaseFirestoreException(
                "aborted",
                FirebaseFirestoreException.Code.ABORTED
            )
        )
        val model = viewModel(store = store)
        val messages = messagesOf(model)

        model.markReceived(record(), 4.0)

        assertEquals(listOf(FirestoreFailures.WRITE_CONFLICT), messages)
        // The whole point: a purchase write carries `rev = stored.rev + 1`, so
        // retrying by itself would be a second opinion about a document
        // somebody else has just changed.
        assertEquals(1, store.attempts)
    }

    @Test
    fun `a rules refusal is reported as one`() = runTest {
        val store = Store(
            stored = row(),
            failWith = FirebaseFirestoreException(
                "denied",
                FirebaseFirestoreException.Code.PERMISSION_DENIED
            )
        )
        val model = viewModel(store = store)
        val messages = messagesOf(model)

        model.setUrgency(record(), UrgencyV2.CRITICAL)

        assertEquals(listOf(FirestoreFailures.REFUSED), messages)
    }

    @Test
    fun `an exhausted daily quota says when it comes back`() = runTest {
        val store = Store(
            stored = row(),
            failWith = FirebaseFirestoreException(
                "quota",
                FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED
            )
        )
        val model = viewModel(store = store)
        val messages = messagesOf(model)

        model.remove(record())

        assertEquals(listOf(FirestoreFailures.QUOTA_EXHAUSTED), messages)
    }

    @Test
    fun `a requirement that has gone is a quiet no-op, not an error`() = runTest {
        val store = Store(stored = null)
        val model = viewModel(store = store)
        val messages = messagesOf(model)

        model.markReceived(record(), 4.0)

        assertEquals(listOf(PurchaseViewModel.NOTHING_CHANGED), messages)
        assertEquals(0, store.writes.size)
    }

    @Test
    fun `an edit that changes nothing says so rather than burning a revision`() = runTest {
        val store = Store(stored = row())
        val model = viewModel(store = store)
        val messages = messagesOf(model)

        model.edit(record(), "Sliding gate rack", 6.0, UrgencyV2.URGENT, "")

        assertEquals(listOf(PurchaseViewModel.NOTHING_CHANGED), messages)
        assertEquals(0, store.writes.size)
    }

    @Test
    fun `a legacy string quantity is rescued by an edit and rewritten as a number`() = runTest {
        // `pr_received_legacy` holds "10". Until a write rewrites it as a
        // number the rules refuse every update to that document.
        val store = Store(stored = row(qty = "10"))
        val model = viewModel(store = store)

        model.edit(record(), "Remote handsets", 4.0, UrgencyV2.NORMAL, "")

        assertEquals(4.0, store.writes.single()["qty"])
        assertNull("no update may add a del key", store.writes.single()["del"])
    }

    @Test
    fun `dismissing writes nothing at all`() = runTest {
        val store = Store(stored = row())
        val model = viewModel(store = store)
        model.open(PurchaseSheet.REMOVE, record())

        model.dismiss()

        assertFalse(model.sheet.value.isOpen)
        assertEquals(0, store.attempts)
    }
}
