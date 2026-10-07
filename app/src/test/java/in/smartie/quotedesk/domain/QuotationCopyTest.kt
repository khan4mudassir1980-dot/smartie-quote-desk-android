package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.mapping.DocData
import `in`.smartie.quotedesk.data.mapping.Keys
import `in`.smartie.quotedesk.data.mapping.toQuotationRecord
import `in`.smartie.quotedesk.data.model.NumberingRecord
import `in`.smartie.quotedesk.data.model.ProductRecord
import `in`.smartie.quotedesk.data.model.QuotationPartySnapshot
import `in`.smartie.quotedesk.data.model.QuotationRecord
import `in`.smartie.quotedesk.data.model.QuotingRecord
import `in`.smartie.quotedesk.data.model.RateTierV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Duplicate (N5.10 commit 8, amendment C), decided before anything is stored.
 *
 * The original is issued by `QuotationWrite.plan` and read back through the
 * app's reader — carriage as V8C4's Transportation line, an installation, a
 * discount and GST at 18% — so what a copy leaves behind is what a real
 * quotation holds, not what a hand-built record happened to omit.
 */
class QuotationCopyTest {

    private val manager = Member(uid = "u_m", name = "Manager Person", role = Role.STAFF)
    private val staff = Member(uid = "u_s", name = "Staff Person", role = Role.WORKER)
    private val counter = NumberingRecord(prefix = "SIE/QD", financialYear = "2025-26", next = 9, pad = 3)
    private val issuedAt = 1_760_000_000_000L
    private val copiedAt = 1_760_900_000_000L

    private val motor = ProductRecord(
        documentId = Keys.productDocId("gate", "SIE1000"),
        key = Keys.productKey("gate", "SIE1000"),
        group = "gate",
        seedModel = "SIE1000",
        model = "SIE1000",
        categoryId = "cat-sliding",
        dealer = 18_000.0,
        client = 17_000.0
    )

    private val original: QuoteDraft = QuoteDraft(
        id = "qd_9",
        tier = RateTierV2.CLIENT,
        party = QuotationPartySnapshot(name = "Walk-in Builders", site = "Plot 7", phone = "9876543210"),
        transport = 1_500.0,
        transportNote = "Mumbai to Vadodara",
        installation = Installation(InstallationMode.FIXED, 2_000.0),
        discount = Discount(DiscountKind.PERCENT, 5.0),
        gstPercent = 18.0
    ).add(motor, quantity = 2.0, id = "ln_motor")
        .addManual(id = "ln_visit", title = "Site visit", rate = 1_000.0)

    private fun issue(draft: QuoteDraft = original): QuotationRecord {
        val plan = QuotationWrite.plan(
            draft, manager, QuotingRecord(managerDiscountPct = 10.0), counter, null, emptyList(), null, issuedAt
        )
        val stored = (plan as QuotationPlan.Write).quotation
        return DocData(stored["id"] as String, stored).toQuotationRecord()
    }

    // The link, set here: `plan` derives it from a customer list this test does not need.
    private val record = issue().copy(partyId = "c_walkin")

    private var ids = 0
    private fun copy(of: QuotationRecord = record, newId: String = "qd_copy"): QuoteDraft =
        QuotationCopy.copyOf(of, copiedAt, newId) { "ln_c${ids++}" }

    // --- R15: always a new id --------------------------------------------------------

    @Test
    fun `a copy takes a new id - never the quotation's`() {
        assertEquals("qd_copy", copy().id)
        assertThrows(IllegalArgumentException::class.java) { copy(newId = record.id) }
    }

    @Test
    fun `and never the replaced draft's`() {
        val inProgress = QuoteDraft(id = "qd_now").addManual(id = "ln_n", title = "Survey", rate = 500.0)
        val stored = QuoteDrafts(listOf(inProgress), currentId = inProgress.id)

        assertThrows(IllegalArgumentException::class.java) {
            QuotationCopy.opening(stored, record, copiedAt, newId = "qd_now")
        }
    }

    // --- what a copy carries ------------------------------------------------------------

    @Test
    fun `a copy carries the lines at their quoted rates, the party with its link, and the tier`() {
        val copied = copy()

        assertEquals(RateTierV2.CLIENT, copied.tier)
        assertEquals(record.party, copied.party)
        assertEquals("c_walkin", copied.partyId)
        assertEquals(listOf(17_000.0, 1_000.0), copied.lines.map { it.rate })
        assertEquals(listOf(2.0, 1.0), copied.lines.map { it.quantity })
    }

    @Test
    fun `a copy's discount base excludes carriage - restored to the transport field`() {
        // R25. V8C4 keeps its Transportation line as an ordinary line, which a
        // percentage discount on the copy would then take in.
        val copied = copy()

        assertEquals(1_500.0, copied.transport, 0.0)
        assertEquals("Mumbai to Vadodara", copied.transportNote)
        assertTrue(copied.lines.none { it.title == QuotationWrite.TRANSPORT_TITLE })
        assertEquals(35_000.0, copied.totals().discountBase, 0.0)
    }

    @Test
    fun `a copy carries no installation and no discount`() {
        // R26. V8C4's list stops at lines, party and tier.
        val copied = copy()

        assertNull(copied.installation)
        assertNull(copied.discount)
        assertTrue(copied.faults.isEmpty())
    }

    @Test
    fun `a copy does not carry the original's GST - it starts at the builder's default`() {
        // The Owner, 2026-09-28: the stored 18% is not carried.
        val copied = copy()

        assertTrue(copied.gstEnabled)
        assertNull(copied.gstPercent)
        assertEquals(18.0, record.gstPercent, 0.0)
    }

    @Test
    fun `a copy is a new quotation, not an edit, and says whose rates it carries`() {
        val copied = copy()

        assertNull(copied.editOf)
        assertEquals(CopyOrigin("SIE/QD/2025-26/009", issuedAt), copied.copiedFrom)
        assertTrue(copied.ratesAsQuoted)
    }

    @Test
    fun `the copy's "rates as quoted" date is the original's issue date - the server's, else the device's`() {
        // The Owner's review of 2026-10-06: one definition of a quotation's
        // date. The server's serverAt wins over a phone clock that ran fast.
        val serverTime = issuedAt - 86_400_000L
        assertEquals(
            CopyOrigin("SIE/QD/2025-26/009", serverTime),
            copy(record.copy(serverAt = serverTime)).copiedFrom
        )
        assertEquals(CopyOrigin("SIE/QD/2025-26/009", issuedAt), copy(record.copy(serverAt = 0L)).copiedFrom)
    }

    // --- rates: what reprices, and when ----------------------------------------------------

    @Test
    fun `a copy opens at its copied rates, and the catalogue arriving changes nothing`() {
        // R24. A **Dealer** original, so a line left at `DraftLine`'s default
        // tier — Client — could not pass by coincidence. Today's catalogue has
        // moved; each line's own tier is the copy's, so `alignDraftToTier`
        // finds nothing out of step.
        val dealer = issue(
            QuoteDraft(id = "qd_9", tier = RateTierV2.DEALER, party = original.party, gstPercent = 18.0)
                .add(motor, quantity = 2.0, id = "ln_motor")
        )
        val copied = copy(of = dealer)
        val todays = mapOf(motor.key to 14_000.0)

        assertEquals(RateTierV2.DEALER, copied.tier)
        assertFalse(copied.hasLinesOutOfStep)
        val aligned = copied.alignLinesToTier { todays[it] }
        assertEquals(0, aligned.repriced)
        assertEquals(copied.lines, aligned.draft.lines)
        assertEquals(18_000.0, aligned.draft.lines.single().rate!!, 0.0)
    }

    @Test
    fun `one tap on the other tier reprices at today's catalogue, and the note stops claiming the quoted rates`() {
        val copied = copy()

        val switched = copied.withTier(RateTierV2.DEALER) { key -> mapOf(motor.key to 14_000.0)[key] }

        assertEquals(1, switched.repriced)
        assertEquals(1, switched.kept)
        assertEquals(14_000.0, switched.draft.lines.first { it.key == motor.key }.rate!!, 0.0)
        assertFalse(switched.draft.ratesAsQuoted)
    }

    @Test
    fun `a switch that reprices nothing leaves the rates as quoted`() {
        val handTyped = copy(of = issue(original.copy(lines = emptyList(), transport = 0.0, installation = null, discount = null)
            .addManual(id = "ln_visit", title = "Site visit", rate = 1_000.0)))

        val switched = handTyped.withTier(RateTierV2.DEALER) { null }

        assertEquals(0, switched.repriced)
        assertTrue(switched.draft.ratesAsQuoted)
    }

    @Test
    fun `an edit, too, stops claiming its quoted rates once a switch has repriced a line`() {
        val edit = QuotationEdit.draftFrom(record, copiedAt) { "ln_e${ids++}" }
        assertTrue(edit.ratesAsQuoted)

        val switched = edit.withTier(RateTierV2.DEALER) { key -> mapOf(motor.key to 14_000.0)[key] }.draft
        assertFalse(switched.ratesAsQuoted)
        assertFalse("and back again is today's rates, not the quoted ones",
            switched.withTier(RateTierV2.CLIENT) { key -> mapOf(motor.key to 23_000.0)[key] }.draft.ratesAsQuoted)
    }

    @Test
    fun `plus on a product already on the copy raises its quantity at the copied rate`() {
        val copied = copy()

        val more = copied.add(motor.copy(client = 25_000.0), quantity = 1.0, id = "ln_more")

        val line = more.lines.single { it.key == motor.key }
        assertEquals(3.0, line.quantity, 0.0)
        assertEquals(17_000.0, line.rate!!, 0.0)
    }

    @Test
    fun `a contractor quotation's copy keeps its tier, and asks for Dealer or Client before it is issued`() {
        val contractor = record.copy(tier = RateTierV2.CONTRACTOR, tierName = "Contractor", storedTier = "contractor")
        val copied = copy(of = contractor)

        assertEquals(RateTierV2.CONTRACTOR, copied.tier)
        assertEquals(QuoteDraft.TIER_NOT_OFFERED, copied.copy(gstPercent = 18.0).refusal(QuoteMath.NO_CAP))
    }

    // --- pressing Duplicate -----------------------------------------------------------------

    private val inProgress = QuoteDraft(id = "qd_now", updatedAt = 1_760_800_000_000L)
        .addManual(id = "ln_n", title = "Gate survey", rate = 500.0)

    @Test
    fun `with an edit open, Duplicate waits for it`() {
        val edit = QuotationEdit.draftFrom(record, copiedAt) { "ln_e${ids++}" }
        val stored = QuoteDrafts(listOf(inProgress, edit), currentId = edit.id)

        assertEquals(CopyStart.EditOpen("SIE/QD/2025-26/009"), QuotationCopy.start(stored))
        assertEquals(
            CopyOpening.Refused("Finish or discard your changes to SIE/QD/2025-26/009 first"),
            QuotationCopy.opening(stored, record, copiedAt, "qd_copy")
        )
    }

    @Test
    fun `with lines in progress, V8C4's question comes first`() {
        assertEquals(CopyStart.AskFirst, QuotationCopy.start(QuoteDrafts(listOf(inProgress), currentId = inProgress.id)))
    }

    @Test
    fun `with nothing in progress, the copy goes straight in`() {
        assertEquals(CopyStart.Go, QuotationCopy.start(QuoteDrafts()))
        assertEquals(CopyStart.Go, QuotationCopy.start(QuoteDrafts(listOf(QuoteDraft(id = "qd_empty")), "qd_empty")))
    }

    @Test
    fun `the copy replaces the draft in progress, and leaves every other draft alone`() {
        val other = QuoteDraft(id = "qd_other", updatedAt = 1L).addManual(id = "ln_o", title = "Other", rate = 1.0)
        val stored = QuoteDrafts(listOf(other, inProgress), currentId = inProgress.id)

        val opening = QuotationCopy.opening(stored, record, copiedAt, "qd_copy") { "ln_c${ids++}" } as CopyOpening.Show
        assertEquals("qd_now", opening.replacing)

        val after = stored.replaceWith(opening.replacing, opening.draft)
        assertEquals(opening.draft, after.current)
        assertNull(after["qd_now"])
        assertSame(other, after["qd_other"])
    }

    @Test
    fun `Duplicate is offered to anyone who may quote, never to Staff`() {
        assertTrue(QuotationCopy.offered(manager))
        assertFalse(QuotationCopy.offered(staff))
    }

    @Test
    fun `the copy's message is V8C4's, with finalise added - word for word`() {
        // The Owner's approval of 2026-10-06 (N5.11).
        assertEquals(
            "Copied into a new draft — it takes a new number when you finalise, download, print or share",
            QuotationCopy.COPIED
        )
    }
}
