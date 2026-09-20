package `in`.smartie.quotedesk.ui.purchase

import com.google.firebase.firestore.FirebaseFirestoreException
import `in`.smartie.quotedesk.data.mapping.DocData
import `in`.smartie.quotedesk.data.model.PurchaseRecord
import `in`.smartie.quotedesk.data.model.UrgencyV2
import `in`.smartie.quotedesk.data.repository.FirestoreFailures
import `in`.smartie.quotedesk.data.repository.PurchaseStore
import `in`.smartie.quotedesk.data.repository.PurchaseTransaction
import `in`.smartie.quotedesk.data.ListenerRetry
import `in`.smartie.quotedesk.data.repository.PurchaseWriteRepository
import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.domain.PurchaseDraft
import `in`.smartie.quotedesk.domain.PurchaseWrite
import `in`.smartie.quotedesk.domain.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

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
        var stored: Map<String, Any?>? = null,
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
        receivedBy: String? = null,
        rcvQty: Any? = null
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
        if (rcvQty != null) put("rcvQty", rcvQty)
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

    /** Short waits, so a retry test does not have to sit through a minute. */
    private val fastRetry = ListenerRetry(firstDelayMillis = 10L, maxDelayMillis = 80L)

    private fun viewModel(
        member: Member = admin,
        store: Store = Store(),
        requirements: Flow<List<PurchaseRecord>> = MutableStateFlow(emptyList()),
        online: MutableStateFlow<Boolean> = MutableStateFlow(true),
        report: (Throwable) -> Unit = {}
    ) = PurchaseViewModel(
        member = member,
        writes = PurchaseWriteRepository(
            store,
            now = { 1_700_000_000_000L },
            newId = { "pr_generated" }
        ),
        requirements = requirements,
        onlineFlow = online,
        report = report,
        retry = fastRetry
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

    // --- part deliveries ------------------------------------------------------

    @Test
    fun `a part delivery says what is still to come, and the row stays open`() = runTest {
        val store = Store(stored = row(qty = 10.0))
        val model = viewModel(store = store)
        val messages = messagesOf(model)
        model.open(PurchaseSheet.RECEIVE, record())

        model.markReceived(record(), 4.0)

        // Not "Marked as received": the defect this batch fixes is precisely a
        // first delivery being reported, and treated, as the whole order.
        assertEquals(listOf(PurchaseViewModel.partlyReceived(6.0)), messages)
        assertEquals(4.0, store.writes.single()["rcvQty"])
        assertEquals(false, store.writes.single()["received"])
        assertFalse("a successful write still closes the panel", model.sheet.value.isOpen)
    }

    @Test
    fun `the delivery that completes it says so`() = runTest {
        val store = Store(stored = row(qty = 10.0, rcvQty = 6.0))
        val model = viewModel(store = store)
        val messages = messagesOf(model)

        model.markReceived(record(), 4.0)

        assertEquals(listOf(PurchaseViewModel.RECEIVED), messages)
        assertEquals("the running total, not the last delivery", 10.0, store.writes.single()["rcvQty"])
        assertEquals(true, store.writes.single()["received"])
    }

    @Test
    fun `what is said is decided by the stored figures, not the screen's`() = runTest {
        // The record handed in says six are needed and none have arrived. The
        // document says ten, with nine already in — so this delivery finishes
        // it, and only the transaction could have known that.
        val store = Store(stored = row(qty = 10.0, rcvQty = 9.0))
        val model = viewModel(store = store)
        val messages = messagesOf(model)

        model.markReceived(record(), 1.0)

        assertEquals(listOf(PurchaseViewModel.RECEIVED), messages)
    }

    @Test
    fun `more than is outstanding is refused by name and writes nothing`() = runTest {
        val store = Store(stored = row(qty = 10.0, rcvQty = 8.0))
        val model = viewModel(store = store)
        val messages = messagesOf(model)
        model.open(PurchaseSheet.RECEIVE, record())

        model.markReceived(record(), 5.0)

        assertEquals(listOf(PurchaseWrite.moreThanRemaining(2.0)), messages)
        assertEquals(0, store.writes.size)
        assertTrue("the panel stays open so it can be corrected", model.sheet.value.isOpen)
    }

    @Test
    fun `the total needed cannot be edited below what has arrived`() = runTest {
        val store = Store(stored = row(qty = 10.0, rcvQty = 6.0))
        val model = viewModel(store = store)
        val messages = messagesOf(model)
        model.open(PurchaseSheet.EDIT, record())

        model.edit(record(), "Sliding gate rack", 5.0, UrgencyV2.URGENT, "")

        assertEquals(listOf(PurchaseWrite.belowReceived(6.0)), messages)
        assertEquals(0, store.writes.size)
        assertTrue(model.sheet.value.isOpen)
    }

    @Test
    fun `editing the total down to what has arrived closes it`() = runTest {
        val store = Store(stored = row(qty = 10.0, rcvQty = 6.0))
        val model = viewModel(store = store)
        val messages = messagesOf(model)

        model.edit(record(), "Sliding gate rack", 6.0, UrgencyV2.URGENT, "")

        assertEquals(listOf(PurchaseViewModel.SAVED), messages)
        assertEquals(true, store.writes.single()["received"])
        assertEquals(PurchaseWrite.STATUS_RECEIVED, store.writes.single()["status"])
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

    /**
     * A listener that refuses [times] times and then simply waits.
     *
     * It has to **stop scheduling retries** once the assertions are made. A
     * view model's scope is not the test's to cancel, so a flow that failed
     * for ever would leave a timer task queued, and `runTest` drains the
     * virtual clock when it finishes — for ever, at full speed.
     */
    private fun refusing(times: Int, code: FirebaseFirestoreException.Code): Flow<List<PurchaseRecord>> {
        // Counted outside the flow: retryWhen re-collects it, so a counter
        // inside would reset on every attachment and refuse for ever.
        var refusals = 0
        return flow {
            if (refusals < times) {
                refusals += 1
                throw FirebaseFirestoreException(code.name, code)
            }
            awaitCancellation()
        }
    }

    // --- the listener, and the row that has to show at once ---------------------

    @Test
    fun `a created requirement is on the board before the snapshot carries it`() = runTest {
        // A Firestore transaction is applied on the server and is not
        // latency-compensated, so the listener says nothing until the round
        // trip finishes. The board must not wait for it.
        val rows = MutableStateFlow<List<PurchaseRecord>>(emptyList())
        val model = viewModel(store = Store(), requirements = rows)

        model.add(PurchaseDraft(name = "Remote handsets", quantity = 4.0))

        assertEquals(listOf("pr_generated"), model.active.value.map { it.id })
        assertEquals("Remote handsets", model.active.value.single().name)
        // Both sides are `Double` here, so JUnit needs a delta — elsewhere
        // the comparison is against a map value and boxes instead.
        assertEquals(4.0, model.active.value.single().quantity, 0.0)
    }

    @Test
    fun `a created requirement lands at the position its urgency gives it`() = runTest {
        val existing = record("pr_green", createdAt = 9_000).copy(urgency = UrgencyV2.NORMAL)
        val rows = MutableStateFlow(listOf(existing))
        val model = viewModel(requirements = rows)

        model.add(
            PurchaseDraft(name = "Very urgent one", quantity = 1.0, urgency = UrgencyV2.CRITICAL)
        )

        assertEquals(
            "red belongs above green, pending or not",
            listOf("pr_generated", "pr_green"),
            model.active.value.map { it.id }
        )
    }

    @Test
    fun `the snapshot replaces the pending row instead of doubling it`() = runTest {
        val rows = MutableStateFlow<List<PurchaseRecord>>(emptyList())
        val model = viewModel(requirements = rows)
        model.add(PurchaseDraft(name = "Remote handsets", quantity = 4.0))
        assertEquals(1, model.active.value.size)

        // The real document arrives, with the name the server holds.
        rows.value = listOf(record("pr_generated").copy(name = "Remote handsets"))

        assertEquals(
            "the same requirement must never be on the board twice",
            listOf("pr_generated"),
            model.active.value.map { it.id }
        )
    }

    @Test
    fun `a removal takes the pending row with it`() = runTest {
        // The one case the snapshot cannot clear: a soft-deleted document is
        // filtered out of the listener, so it never arrives to displace the
        // optimistic copy.
        val rows = MutableStateFlow<List<PurchaseRecord>>(emptyList())
        val store = Store()
        val model = PurchaseViewModel(
            member = admin,
            writes = PurchaseWriteRepository(store, now = { 1L }, newId = { "pr_one" }),
            requirements = rows,
            onlineFlow = MutableStateFlow(true),
            retry = fastRetry
        )
        model.add(PurchaseDraft(name = "Remote handsets", quantity = 4.0))
        assertEquals(1, model.active.value.size)
        // The document exists now, as it would once the create committed.
        store.stored = row()

        model.remove(record("pr_one"))

        assertTrue("a removed requirement must leave the board", model.active.value.isEmpty())
    }

    @Test
    fun `a remote change still arrives while nothing is pending`() = runTest {
        val rows = MutableStateFlow<List<PurchaseRecord>>(emptyList())
        val model = viewModel(requirements = rows)

        rows.value = listOf(record("pr_elsewhere", createdAt = 5_000))

        assertEquals(listOf("pr_elsewhere"), model.active.value.map { it.id })
    }

    @Test
    fun `a listener that drops comes back on its own`() = runTest {
        var attachments = 0
        val flaky = flow {
            attachments += 1
            if (attachments == 1) {
                emit(listOf(record("pr_first")))
                throw IOException("the connection went")
            }
            emit(listOf(record("pr_first"), record("pr_second", createdAt = 5_000)))
        }
        val model = viewModel(requirements = flaky)

        advanceTimeBy(200)
        runCurrent()

        assertTrue("the listener has to attach again by itself", attachments >= 2)
        assertEquals(
            listOf("pr_second", "pr_first"),
            model.active.value.map { it.id }
        )
    }

    @Test
    fun `a persistent refusal is told to the person, not swallowed`() = runTest {
        val reported = mutableListOf<Throwable>()
        val denied = refusing(4, FirebaseFirestoreException.Code.PERMISSION_DENIED)
        val model = viewModel(requirements = denied, report = { reported += it })
        val messages = messagesOf(model)

        advanceTimeBy(200)
        runCurrent()

        assertTrue("every failure is reported", reported.size >= 3)
        assertTrue(
            "and the person is told once it stops looking like a blip",
            messages.contains(PurchaseViewModel.LISTENER_REFUSED)
        )
        assertEquals(
            "said once, not on every attempt",
            1,
            messages.count { it == PurchaseViewModel.LISTENER_REFUSED }
        )
        assertFalse("a refused list is not a loaded empty one", model.loading.value)
    }

    @Test
    fun `being signed out elsewhere says so rather than showing nothing`() = runTest {
        val gone = refusing(4, FirebaseFirestoreException.Code.UNAUTHENTICATED)
        val model = viewModel(requirements = gone)
        val messages = messagesOf(model)

        advanceTimeBy(200)
        runCurrent()

        assertTrue(messages.contains(PurchaseViewModel.LISTENER_SIGNED_OUT))
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
