package `in`.smartie.quotedesk.data.repository

import `in`.smartie.quotedesk.data.mapping.DocData
import `in`.smartie.quotedesk.data.model.PurchaseRecord
import `in`.smartie.quotedesk.data.model.UrgencyV2
import `in`.smartie.quotedesk.domain.DeleteField
import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.domain.PurchaseDraft
import `in`.smartie.quotedesk.domain.PurchaseWrite
import `in`.smartie.quotedesk.domain.Role
import `in`.smartie.quotedesk.domain.ServerTimestamp
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The purchase writer, through the transaction.
 *
 * The two that matter most are the ones an emulator cannot stage on demand: a
 * **replayed** body must reuse the id and the timestamp it generated the first
 * time, and every plan must be built from the document read **inside** the
 * transaction rather than from the record the screen was holding.
 */
class PurchaseWriteRepositoryTest {

    /**
     * A store that replays the body [attempts] times, as Firestore does under
     * contention, and records every attempt separately.
     *
     * The stored document can also be swapped between attempts, which is how
     * "the plan is built from what the transaction read" is asserted rather
     * than assumed.
     */
    private class FakeStore(
        private val stored: Map<String, Any?>? = null,
        private val attempts: Int = 1,
        private val docId: String = "pr_one"
    ) : PurchaseStore {
        val writes = mutableListOf<Written>()
        val reads = mutableListOf<String>()
        var bodyRuns = 0

        class Written(val docId: String, val data: Map<String, Any?>, val merge: Boolean)

        override suspend fun <T> transaction(body: (PurchaseTransaction) -> T): T {
            var last: T? = null
            repeat(attempts) {
                bodyRuns++
                // Firestore discards what a replayed attempt recorded.
                writes.clear()
                last = body(object : PurchaseTransaction {
                    override fun read(id: String): DocData? {
                        reads += id
                        return stored?.let { DocData(docId, it) }
                    }

                    override fun write(id: String, data: Map<String, Any?>, merge: Boolean) {
                        writes += Written(id, data, merge)
                    }
                })
            }
            @Suppress("UNCHECKED_CAST")
            return last as T
        }
    }

    private val owner = Member(uid = "uid_owner", name = "Omar", role = Role.OWNER)
    private val admin = Member(uid = "uid_admin", name = "Asha", role = Role.ADMIN)
    private val staff = Member(uid = "uid_staff", name = "Sam", role = Role.STAFF)
    private val worker = Member(uid = "uid_worker", name = "Wes", role = Role.WORKER)

    private val at = 1_700_000_000_000L

    private fun repository(store: PurchaseStore) =
        PurchaseWriteRepository(store, now = { at }, newId = { "pr_generated" })

    /** The document as Firestore holds it, in the PWA's own field names. */
    private fun row(
        qty: Any = 6.0,
        rev: Any = 3,
        received: Any? = null,
        receivedBy: String? = null,
        del: Any? = null,
        id: Any? = "pr_one"
    ): Map<String, Any?> = buildMap {
        put("name", "Sliding gate rack")
        put("qty", qty)
        put("urgency", "urgent")
        put("status", if (received == null) "Needed" else "Received")
        put("rev", rev)
        put("updated", 1_690_000_000_000L)
        if (id != null) put("id", id)
        if (received != null) put("received", received)
        if (receivedBy != null) put("rcvBy", receivedBy)
        if (del != null) put("del", del)
    }

    /** The record the screen was holding. Deliberately stale everywhere. */
    private val onScreen = PurchaseRecord(
        id = "pr_one",
        name = "Something older",
        quantity = 99.0,
        urgency = UrgencyV2.NORMAL,
        revision = 1
    )

    private val draft = PurchaseDraft(name = "Remote handsets", quantity = 4.0)

    // --- the transaction contract ---------------------------------------------

    @Test
    fun `a replayed body reuses the id and the timestamp it already generated`() = runTest {
        // Contract points 1 and 2: generated once, before the transaction.
        val store = FakeStore(attempts = 3)

        repository(store).create(admin, draft)

        assertEquals(3, store.bodyRuns)
        val written = store.writes.single()
        assertEquals("pr_generated", written.docId)
        assertEquals("pr_generated", written.data["id"])
        assertEquals(at, written.data["t"])
        assertEquals(at, written.data["updated"])
    }

    @Test
    fun `the plan is built from the document the transaction read`() = runTest {
        // The screen is holding quantity 99 and revision 1; the stored row
        // says 6 and 3. Only the stored one may reach the wire.
        val store = FakeStore(stored = row(qty = 6.0, rev = 3))

        repository(store).setUrgency(admin, onScreen, UrgencyV2.CRITICAL)

        val data = store.writes.single().data
        assertEquals(6.0, data["qty"])
        assertEquals(4, data["rev"])
    }

    @Test
    fun `the revision comes from the transaction, not from the caller's copy`() = runTest {
        val store = FakeStore(stored = row(rev = 7))
        repository(store).setUrgency(admin, onScreen, UrgencyV2.CRITICAL)
        assertEquals(8, store.writes.single().data["rev"])
    }

    @Test
    fun `a requirement that has gone writes nothing and says nothing happened`() = runTest {
        val store = FakeStore(stored = null)

        val result = repository(store).markReceived(admin, onScreen, 4.0)

        assertEquals(PurchaseWriteResult.NO_CHANGE, result)
        assertTrue(store.writes.isEmpty())
    }

    @Test
    fun `creating reads the id back before it uses it`() = runTest {
        // A `set` on a taken id is an update to the rules, so a collision
        // would overwrite somebody else's requirement rather than fail.
        val store = FakeStore(stored = row(), docId = "pr_generated")

        val failure = runCatching { repository(store).create(admin, draft) }.exceptionOrNull()

        assertEquals(PurchaseWrite.ALREADY_EXISTS, failure?.message)
        assertEquals(listOf("pr_generated"), store.reads)
        assertTrue(store.writes.isEmpty())
    }

    // --- legacy documents -------------------------------------------------------

    @Test
    fun `a legacy string quantity is rewritten as a number`() = runTest {
        // `pr_received_legacy` holds "10". The rules check the merged
        // post-state, so the row is unupdatable until this happens.
        val store = FakeStore(stored = row(qty = "10"))

        repository(store).setUrgency(admin, onScreen, UrgencyV2.CRITICAL)

        val qty = store.writes.single().data["qty"]
        assertTrue("qty must be a number, was $qty", qty is Double)
        assertEquals(10.0, qty)
    }

    @Test
    fun `a legacy row with no id of its own still gets one written`() = runTest {
        val store = FakeStore(stored = row(id = null))

        repository(store).setUrgency(admin, onScreen, UrgencyV2.CRITICAL)

        // `toPurchaseRecord` falls back to the document id, and the write
        // re-asserts it so the rules' `id == docId` holds afterwards.
        assertEquals("pr_one", store.writes.single().data["id"])
    }

    @Test
    fun `a legacy numeric received is understood as closed`() = runTest {
        // The PWA writes `received: 1`, not `true`.
        val store = FakeStore(stored = row(received = 1))

        val failure = runCatching { repository(store).markReceived(admin, onScreen, 4.0) }
            .exceptionOrNull()

        assertNotNull("a received row must not be received twice", failure)
        assertTrue(store.writes.isEmpty())
    }

    @Test
    fun `a legacy numeric del is understood as removed`() = runTest {
        val store = FakeStore(stored = row(del = 1))

        val failure = runCatching { repository(store).edit(admin, onScreen, "X", 2.0, UrgencyV2.NORMAL, "") }
            .exceptionOrNull()

        assertEquals(PurchaseWrite.ALREADY_DELETED, failure?.message)
    }

    @Test
    fun `no update ever carries a del key except a soft delete`() = runTest {
        // `touched()` reports keys ADDED, so a stray `del: false` on a row
        // without the field refuses an ordinary Manager's save.
        val edits = FakeStore(stored = row())
        repository(edits).edit(admin, onScreen, "Remote handsets", 4.0, UrgencyV2.NORMAL, "")
        assertFalse(edits.writes.single().data.containsKey("del"))

        val removes = FakeStore(stored = row())
        repository(removes).softDelete(admin, onScreen)
        assertEquals(true, removes.writes.single().data["del"])
    }

    // --- the markers reach the store unresolved ----------------------------------

    @Test
    fun `reopening hands the store a deletion marker for every received field`() = runTest {
        val store = FakeStore(stored = row(received = true))

        repository(store).reopen(admin, onScreen)

        val written = store.writes.single()
        assertTrue("a deletion is only legal in a merged write", written.merge)
        for (field in listOf("rcvQty", "rcvBy", "rcvUid", "rcvAt")) {
            assertSame(DeleteField, written.data[field])
        }
        assertSame(ServerTimestamp, written.data["serverAt"])
    }

    @Test
    fun `a new requirement is written whole and an update is merged`() = runTest {
        val created = FakeStore()
        repository(created).create(admin, draft)
        assertFalse(created.writes.single().merge)

        val edited = FakeStore(stored = row())
        repository(edited).edit(admin, onScreen, "Remote handsets", 4.0, UrgencyV2.NORMAL, "")
        assertTrue(edited.writes.single().merge)
    }

    // --- who may ------------------------------------------------------------------

    @Test
    fun `everyone active may add a requirement, Workers included`() = runTest {
        for (member in listOf(owner, admin, staff, worker)) {
            val store = FakeStore()
            assertEquals(
                PurchaseWriteResult.WRITTEN,
                repository(store).create(member, draft)
            )
            assertEquals(member.uid, store.writes.single().data["byUid"])
        }
    }

    @Test
    fun `a Worker may not change, receive, reopen or remove one`() = runTest {
        for (attempt in writeAttempts()) {
            val store = FakeStore(stored = row(received = true))
            val failure = runCatching { attempt(repository(store), worker) }.exceptionOrNull()

            assertTrue("a Worker must be refused", failure is IllegalArgumentException)
            assertEquals("and nothing may reach the transaction", 0, store.bodyRuns)
        }
    }

    @Test
    fun `the displayed Manager may change and receive but never reopen or remove`() = runTest {
        val edits = FakeStore(stored = row())
        assertEquals(
            PurchaseWriteResult.WRITTEN,
            repository(edits).edit(staff, onScreen, "Remote handsets", 4.0, UrgencyV2.NORMAL, "")
        )

        val receives = FakeStore(stored = row())
        assertEquals(
            PurchaseWriteResult.WRITTEN,
            repository(receives).markReceived(staff, onScreen, 4.0)
        )

        // Reopen is the one restriction the rules cannot express, so this
        // predicate is the whole of the enforcement.
        val reopens = FakeStore(stored = row(received = true))
        assertTrue(
            runCatching { repository(reopens).reopen(staff, onScreen) }
                .exceptionOrNull() is IllegalArgumentException
        )
        assertEquals(0, reopens.bodyRuns)

        val removes = FakeStore(stored = row())
        assertTrue(
            runCatching { repository(removes).softDelete(staff, onScreen) }
                .exceptionOrNull() is IllegalArgumentException
        )
        assertEquals(0, removes.bodyRuns)
    }

    @Test
    fun `an Owner and an Administrator may reopen and remove`() = runTest {
        for (member in listOf(owner, admin)) {
            val reopens = FakeStore(stored = row(received = true))
            assertEquals(
                PurchaseWriteResult.WRITTEN,
                repository(reopens).reopen(member, onScreen)
            )

            val removes = FakeStore(stored = row())
            assertEquals(
                PurchaseWriteResult.WRITTEN,
                repository(removes).softDelete(member, onScreen)
            )
        }
    }

    @Test
    fun `a refusal names who has it, rather than showing an error code`() = runTest {
        // A second device still showing the requirement as open gets a
        // sentence, not the stale-revision failure the rules would have given.
        val store = FakeStore(stored = row(received = true, receivedBy = "Omar"))

        val failure = runCatching { repository(store).markReceived(admin, onScreen, 4.0) }
            .exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("Already received by Omar", failure?.message)
    }

    /** Every call a Worker must be refused, so the matrix is asserted on all of them. */
    private fun writeAttempts(): List<suspend (PurchaseWriteRepository, Member) -> Unit> = listOf(
        { repo, member -> repo.edit(member, onScreen, "X", 2.0, UrgencyV2.NORMAL, "") },
        { repo, member -> repo.setUrgency(member, onScreen, UrgencyV2.CRITICAL) },
        { repo, member -> repo.markReceived(member, onScreen, 2.0) },
        { repo, member -> repo.reopen(member, onScreen) },
        { repo, member -> repo.softDelete(member, onScreen) }
    )
}
