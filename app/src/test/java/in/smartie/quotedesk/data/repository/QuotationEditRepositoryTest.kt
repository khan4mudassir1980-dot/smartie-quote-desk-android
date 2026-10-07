package `in`.smartie.quotedesk.data.repository

import `in`.smartie.quotedesk.data.mapping.DocData
import `in`.smartie.quotedesk.data.mapping.toQuotationRecord
import `in`.smartie.quotedesk.data.model.QuotationPartySnapshot
import `in`.smartie.quotedesk.data.model.QuotationRecord
import `in`.smartie.quotedesk.domain.EditConflict
import `in`.smartie.quotedesk.domain.Installation
import `in`.smartie.quotedesk.domain.InstallationMode
import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.domain.QuotationCancel
import `in`.smartie.quotedesk.domain.QuotationEdit
import `in`.smartie.quotedesk.domain.QuoteDraft
import `in`.smartie.quotedesk.domain.Role
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Editing and cancelling a finalised quotation (N5.10 commit 4), through the
 * real repository over the fake store.
 *
 * **Hazard 1 is the reason this file exists.** An edit sent through
 * `finalise` would be answered "already issued" by the read-first, and the
 * edits discarded without a word. So the first test here saves an edit and
 * reads the **stored** lines back.
 *
 * Where a test needs the rules to refuse a commit, the fake models that one
 * rule and **names the emulator test it rests on** — the lesson of the
 * Owner's 9b review, A1. `runBlocking`, so it runs in the local JVM sweep.
 */
class QuotationEditRepositoryTest {

    private val manager = Member(uid = "u_m", name = "Manager Person", role = Role.STAFF)
    private val otherManager = Member(uid = "u_m2", name = "Other Manager", role = Role.STAFF)
    private val owner = Member(uid = "u_o", name = "Owner Person", role = Role.OWNER)

    private val counter = mapOf<String, Any?>("prefix" to "SIE/QD", "fy" to "2025-26", "next" to 9, "pad" to 3)

    private val draft = QuoteDraft(
        id = "qd_1",
        party = QuotationPartySnapshot(name = "Walk-in Builders"),
        installation = Installation(InstallationMode.FIXED, 1_500.0),
        gstPercent = 18.0
    ).addManual(id = "ln_1", title = "Site visit", rate = 1_000.0)

    private var clock = 1_760_000_000_000L
    private fun repository(store: FakeQuotationStore) = QuotationWriteRepository(store, now = { clock })

    /** A store holding the counter and [draft] issued by the Manager. */
    private fun issued(): FakeQuotationStore = runBlocking {
        val store = FakeQuotationStore(mutableMapOf(NUMBERING to counter))
        repository(store).finalise(manager, draft, emptyList(), null)
        store
    }

    private fun FakeQuotationStore.stored(): QuotationRecord =
        DocData("qd_1", docs.getValue("quotations/qd_1")).toQuotationRecord()

    private fun FakeQuotationStore.opened(): QuoteDraft =
        QuotationEdit.draftFrom(stored(), clock) { "ln_e_${reads.size}" }

    @Suppress("UNCHECKED_CAST")
    private fun FakeQuotationStore.storedLines(): List<Map<String, Any?>> =
        docs.getValue("quotations/qd_1")["lines"] as List<Map<String, Any?>>

    // --- hazard 1: an edit really changes the stored quotation ---------------------

    @Test
    fun `an edit saved really changes the stored lines - and the number and the counter do not move`() =
        runBlocking {
            // R11: sent through `finalise`, this edit is answered "already
            // issued" and the stored line keeps its quantity of 1.
            val store = issued()
            val edit = store.opened().let { d -> d.setQuantity(d.lines.single().id, 3.0) }
            store.reads.clear()
            clock += 60_000

            val outcome = repository(store).edit(manager, edit, emptyList())

            assertEquals(EditOutcome.Saved("SIE/QD/2025-26/009"), outcome)
            assertEquals(3.0, store.storedLines().single()["qty"])
            assertEquals("SIE/QD/2025-26/009", store.docs.getValue("quotations/qd_1")["no"])
            assertEquals(10, store.docs.getValue(NUMBERING)["next"])
            assertFalse("the counter is never read", NUMBERING in store.reads)
            assertEquals(1, store.stored().revision)
            assertEquals("Manager Person", store.stored().lastEditedBy)
            // The server's time, never the phone's (N5.11).
            assertEquals(SERVER_TIME, store.stored().lastEditedAt)
            assertNotEquals(clock, store.stored().lastEditedAt)
        }

    @Test
    fun `an edit leaves every key it may not touch exactly as it was`() = runBlocking {
        val store = issued()
        val before = store.docs.getValue("quotations/qd_1")

        repository(store).edit(manager, store.opened().let { d -> d.setQuantity(d.lines.single().id, 2.0) }, emptyList())

        val after = store.docs.getValue("quotations/qd_1")
        for (key in before.keys - QuotationEdit.EDITABLE.toSet()) {
            assertEquals(key, before[key], after[key])
        }
    }

    @Test
    fun `an installation taken off is deleted from the stored quotation`() = runBlocking {
        val store = issued()
        assertTrue("install" in store.docs.getValue("quotations/qd_1"))

        repository(store).edit(manager, store.opened().copy(installation = null), emptyList())

        assertFalse("install" in store.docs.getValue("quotations/qd_1"))
    }

    // --- hazard 3: two editors ---------------------------------------------------------

    /**
     * The rule's `rev == stored rev + 1`, modelled. Rests on the emulator
     * test "an edit from an opening two edits behind is refused" in
     * `firestore/tests/quotation.test.js`.
     */
    private fun FakeQuotationStore.withTheRevisionRule(): FakeQuotationStore = apply {
        refuseCommitWhen = { staged ->
            staged["quotations/qd_1"]?.let { next ->
                val storedRev = (docs.getValue("quotations/qd_1")["rev"] as? Number)?.toInt() ?: 0
                (next["rev"] as? Number)?.toInt() != storedRev + 1
            } ?: false
        }
    }

    @Test
    fun `the second editor is told who and when, and the first's lines stand`() = runBlocking {
        // R12: without the in-transaction compare, the second save reaches
        // the rule and comes back as a bare refusal instead of this sentence.
        val store = issued().withTheRevisionRule()
        val first = store.opened().let { d -> d.setQuantity(d.lines.single().id, 2.0) }
        val second = store.opened().let { d -> d.setQuantity(d.lines.single().id, 5.0) }
        clock += 60_000
        // The "when" is the first edit's stored stamp, which the server now
        // sets (N5.11) — never either phone's clock.
        val firstAt = SERVER_TIME

        assertTrue(repository(store).edit(manager, first, emptyList()) is EditOutcome.Saved)
        clock += 60_000
        val outcome = repository(store).edit(owner, second, emptyList())

        assertEquals(
            EditOutcome.Conflict(EditConflict.Changed("SIE/QD/2025-26/009", "Manager Person", firstAt)),
            outcome
        )
        assertNotEquals(clock - 60_000, firstAt)
        assertEquals(2.0, store.storedLines().single()["qty"])
    }

    @Test
    fun `a rules refusal is reported as it is, and never retried`() = runBlocking {
        val store = issued()
        store.refuseNext = 1
        val before = store.transactions

        val outcome = repository(store).edit(manager, store.opened(), emptyList())

        assertEquals(QuotationWriteRepository.EDIT_REFUSED, (outcome as EditOutcome.Refused).message)
        assertEquals(1, store.transactions - before)
    }

    @Test
    fun `the discount limit is read only when the edit carries a discount`() = runBlocking {
        val store = issued()
        store.reads.clear()

        repository(store).edit(manager, store.opened(), emptyList())

        assertEquals(listOf("quotations/qd_1"), store.reads)
    }

    @Test
    fun `a quotation that has gone is a conflict, and nothing is written`() = runBlocking {
        val store = issued()
        val edit = store.opened()
        store.docs.remove("quotations/qd_1")

        val outcome = repository(store).edit(manager, edit, emptyList())

        assertEquals(EditOutcome.Conflict(EditConflict.Gone("SIE/QD/2025-26/009")), outcome)
        assertFalse("quotations/qd_1" in store.docs)
    }

    @Test
    fun `another Manager's edit is refused before anything is written`() = runBlocking {
        val store = issued()
        val before = store.docs.getValue("quotations/qd_1")

        val outcome = repository(store).edit(otherManager, store.opened(), emptyList())

        assertEquals(EditOutcome.Refused(QuotationEdit.NOT_YOURS), outcome)
        assertEquals(before, store.docs.getValue("quotations/qd_1"))
    }

    // --- cancelling -----------------------------------------------------------------------

    @Test
    fun `cancel writes exactly V8C4's three keys`() = runBlocking {
        // R13. The three fields — `status`, `cancelledBy`, `cancelledAt` — and
        // not a key more: the rule's `hasOnly`, and what V8C4 itself sends.
        val store = issued()
        val before = store.docs.getValue("quotations/qd_1")
        clock += 120_000

        val outcome = repository(store).cancel(manager, "qd_1", "SIE/QD/2025-26/009")

        assertEquals(CancelOutcome.Cancelled("SIE/QD/2025-26/009"), outcome)
        val after = store.docs.getValue("quotations/qd_1")
        val changed = (after.keys + before.keys).filter { before[it] != after[it] }.toSet()
        assertEquals(setOf("status", "cancelledBy", "cancelledAt"), changed)
        assertEquals("Cancelled", after["status"])
        assertEquals("Manager Person", after["cancelledBy"])
        assertEquals(clock, after["cancelledAt"])
    }

    @Test
    fun `a second cancel is refused, and the first cancellation stands`() = runBlocking {
        val store = issued()
        repository(store).cancel(owner, "qd_1", "SIE/QD/2025-26/009")
        val first = store.docs.getValue("quotations/qd_1")
        clock += 60_000

        val outcome = repository(store).cancel(manager, "qd_1", "SIE/QD/2025-26/009")

        assertEquals(
            CancelOutcome.Refused(QuotationCancel.alreadyCancelled("SIE/QD/2025-26/009", "Owner Person")),
            outcome
        )
        assertEquals(first, store.docs.getValue("quotations/qd_1"))
    }

    @Test
    fun `a Manager cancels their own, never another's, and an Owner cancels a Manager's`() = runBlocking {
        val store = issued()
        assertEquals(
            CancelOutcome.Refused(QuotationCancel.NOT_YOURS),
            repository(store).cancel(otherManager, "qd_1", "SIE/QD/2025-26/009")
        )
        assertEquals("Finalised", store.docs.getValue("quotations/qd_1")["status"])

        assertTrue(repository(store).cancel(manager, "qd_1", "SIE/QD/2025-26/009") is CancelOutcome.Cancelled)

        val another = issued()
        assertTrue(repository(another).cancel(owner, "qd_1", "SIE/QD/2025-26/009") is CancelOutcome.Cancelled)
    }

    @Test
    fun `a cancelled quotation cannot be edited`() = runBlocking {
        val store = issued()
        val edit = store.opened()
        repository(store).cancel(owner, "qd_1", "SIE/QD/2025-26/009")

        val outcome = repository(store).edit(manager, edit, emptyList())

        assertEquals(EditOutcome.Conflict(EditConflict.Cancelled("SIE/QD/2025-26/009", "Owner Person")), outcome)
    }

    @Test
    fun `V8C4's confirm and toast, word for word`() {
        assertEquals(
            "Cancel SIE/QD/2025-26/009?\n\nThe record is kept and marked cancelled. " +
                "The number is never released or re-used.",
            QuotationCancel.confirmText("SIE/QD/2025-26/009")
        )
        assertEquals("SIE/QD/2025-26/009 cancelled", QuotationCancel.cancelledText("SIE/QD/2025-26/009"))
    }
}
