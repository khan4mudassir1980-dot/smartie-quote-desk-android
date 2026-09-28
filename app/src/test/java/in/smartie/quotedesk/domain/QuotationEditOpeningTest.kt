package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.mapping.DocData
import `in`.smartie.quotedesk.data.mapping.toQuotationRecord
import `in`.smartie.quotedesk.data.model.NumberingRecord
import `in`.smartie.quotedesk.data.model.QuotationPartySnapshot
import `in`.smartie.quotedesk.data.model.QuotationRecord
import `in`.smartie.quotedesk.data.model.QuotingRecord
import `in`.smartie.quotedesk.data.model.RateTierV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Opening an issued quotation for editing, and the cap the builder shows on
 * it (N5.10 commit 6). `ProductsViewModel.openEdit` stores what [QuotationEdit.opening]
 * answers, under `DraftWrites`' lock; it cannot be unit-tested, so the
 * decision is here.
 *
 * The quotation is issued by `QuotationWrite.plan` and read back through the
 * app's reader, as in `QuotationEditTest`.
 */
class QuotationEditOpeningTest {

    private val manager = Member(uid = "u_m", name = "Manager Person", role = Role.STAFF)
    private val otherManager = Member(uid = "u_m2", name = "Other Manager", role = Role.STAFF)
    private val owner = Member(uid = "u_o", name = "Owner Person", role = Role.OWNER)
    private val staff = Member(uid = "u_m", name = "Manager Person", role = Role.WORKER)

    private val counter = NumberingRecord(prefix = "SIE/QD", financialYear = "2025-26", next = 9, pad = 3)
    private val at = 1_760_500_000_000L

    private val finalised: QuoteDraft = QuoteDraft(
        id = "qd_1",
        tier = RateTierV2.CLIENT,
        party = QuotationPartySnapshot(name = "Walk-in Builders"),
        discount = Discount(DiscountKind.PERCENT, 5.0),
        gstPercent = 18.0
    ).addManual(id = "ln_1", title = "Site visit", rate = 10_000.0)

    private fun issue(draft: QuoteDraft = finalised): QuotationRecord {
        val plan = QuotationWrite.plan(
            draft, manager, QuotingRecord(managerDiscountPct = 10.0), counter, null, emptyList(), null, 1_760_000_000_000L
        )
        val stored = (plan as QuotationPlan.Write).quotation
        return DocData(stored["id"] as String, stored).toQuotationRecord()
    }

    private val record = issue()
    private val inProgress = QuoteDraft(id = "qd_now", updatedAt = 1_760_400_000_000L)
        .addManual(id = "ln_now", title = "Gate survey", rate = 500.0)

    private var ids = 0
    private fun open(
        member: Member = manager,
        of: QuotationRecord = record,
        stored: QuoteDrafts = QuoteDrafts(listOf(inProgress), currentId = inProgress.id),
        onScreen: QuoteDraft = inProgress
    ): EditOpening = QuotationEdit.opening(member, of, stored, onScreen, at) { "ln_e${ids++}" }

    private fun shown(opening: EditOpening): EditOpening.Show {
        assertTrue(opening.toString(), opening is EditOpening.Show)
        return opening as EditOpening.Show
    }

    // --- who, and what ----------------------------------------------------------

    @Test
    fun `the creator opens their own quotation as an edit of it, under its own id`() {
        val show = shown(open())

        assertEquals("qd_1", show.draft.id)
        assertEquals(EditOrigin("qd_1", "SIE/QD/2025-26/009", 0, RateTierV2.CLIENT, 500.0, 10_000.0), show.draft.editOf)
        assertNull(show.unfinished)
    }

    @Test
    fun `an Owner opens a Manager's quotation - another Manager, and Staff, may not`() {
        assertTrue(open(member = owner) is EditOpening.Show)
        assertEquals(EditOpening.Refused(QuotationEdit.NOT_YOURS), open(member = otherManager))
        assertEquals(EditOpening.Refused(QuotationEdit.NOT_YOURS), open(member = staff))
    }

    @Test
    fun `a cancelled quotation is never opened`() {
        assertEquals(
            EditOpening.Refused("SIE/QD/2025-26/009 is cancelled — a cancelled quotation cannot be edited"),
            open(of = record.copy(status = "Cancelled"))
        )
    }

    @Test
    fun `a beta record is never opened`() {
        assertEquals(
            EditOpening.Refused("SIE/QD/2025-26/009 was written by the beta app — it cannot be edited here"),
            open(of = record.copy(legacyBetaShape = true))
        )
    }

    @Test
    fun `the detail offers Edit exactly when opening would not refuse`() {
        assertNull(QuotationEdit.refusalToOpen(manager, record))
        assertNull(QuotationEdit.refusalToOpen(owner, record))
        assertEquals(QuotationEdit.NOT_YOURS, QuotationEdit.refusalToOpen(otherManager, record))
    }

    // --- one edit at a time (hazard 2) -------------------------------------------

    @Test
    fun `an edit opens beside the draft in progress, which comes back untouched when the edit closes`() {
        val stored = QuoteDrafts(listOf(inProgress), currentId = inProgress.id)
        val edit = shown(open(stored = stored)).draft

        val opened = stored.save(edit)
        assertEquals(edit, opened.current)
        assertSame(inProgress, opened["qd_now"])

        val closed = opened.retire(edit.id, freshId = "qd_fresh")
        assertSame(inProgress, closed.current)
        assertNull(closed["qd_1"])
    }

    @Test
    fun `asked again for the quotation being edited, the edit is shown with its changes - the copy on screen first`() {
        val edit = shown(open()).draft
        val changedOnScreen = edit.setQuantity(edit.lines.single().id, 3.0)
        val stored = QuoteDrafts(listOf(inProgress, edit), currentId = edit.id)

        assertSame(changedOnScreen, shown(open(stored = stored, onScreen = changedOnScreen)).draft)
        assertSame("the screen shows another draft", edit, shown(open(stored = stored, onScreen = inProgress)).draft)
        assertNull(shown(open(stored = stored, onScreen = changedOnScreen)).unfinished)
    }

    @Test
    fun `another quotation's edit left open is what is shown, and its number is named`() {
        val ten = issue(finalised.copy(id = "qd_10")).let { it.copy(number = "SIE/QD/2025-26/010") }
        val editOfTen = shown(open(of = ten)).draft
        val stored = QuoteDrafts(listOf(inProgress, editOfTen), currentId = editOfTen.id)

        val show = shown(open(stored = stored, onScreen = editOfTen))

        assertSame(editOfTen, show.draft)
        assertEquals("SIE/QD/2025-26/010", show.unfinished)
    }

    @Test
    fun `an edit takes the slot of a finalised draft whose retire failed - never a twin`() {
        // C11: 9b's recorded case. The draft that issued qd_1 is still on the
        // device under the quotation's id; it is not an edit.
        val stored = QuoteDrafts(listOf(inProgress, finalised), currentId = finalised.id)

        val edit = shown(open(stored = stored, onScreen = finalised)).draft
        val opened = stored.save(edit)

        assertTrue(opened["qd_1"]!!.isEdit)
        assertEquals(1, opened.drafts.count { it.id == "qd_1" })
        assertSame(inProgress, opened["qd_now"])
    }

    // --- the cap the builder shows (amendment A, as a courtesy) ---------------------

    @Test
    fun `a new quotation shows the cap as it stands`() {
        assertEquals(10.0, QuotationEdit.screenCap(finalised, 10.0))
        assertNull(QuotationEdit.screenCap(finalised, null))
    }

    @Test
    fun `an edit whose discount did not go up shows no cap - lowered, removed or untouched`() {
        val edit = shown(open()).draft

        assertEquals(QuoteMath.NO_CAP, QuotationEdit.screenCap(edit, 3.0)!!, 0.0)
        assertEquals(QuoteMath.NO_CAP, QuotationEdit.screenCap(edit.copy(discount = Discount(DiscountKind.PERCENT, 2.0)), 3.0)!!, 0.0)
        assertEquals(QuoteMath.NO_CAP, QuotationEdit.screenCap(edit.copy(discount = null), 3.0)!!, 0.0)
    }

    @Test
    fun `an edit whose discount went up shows the cap`() {
        val edit = shown(open()).draft

        assertEquals(3.0, QuotationEdit.screenCap(edit.copy(discount = Discount(DiscountKind.PERCENT, 8.0)), 3.0)!!, 0.0)
    }

    @Test
    fun `a discount added to an edit that had none shows the cap`() {
        val plain = shown(open(of = issue(finalised.copy(discount = null)))).draft

        assertEquals(QuoteMath.NO_CAP, QuotationEdit.screenCap(plain, 3.0)!!, 0.0)
        assertEquals(3.0, QuotationEdit.screenCap(plain.copy(discount = Discount(DiscountKind.RUPEES, 100.0)), 3.0)!!, 0.0)
    }
}
