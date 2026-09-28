package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.mapping.DocData
import `in`.smartie.quotedesk.data.mapping.toQuotationRecord
import `in`.smartie.quotedesk.data.model.NumberingRecord
import `in`.smartie.quotedesk.data.model.PartyRecord
import `in`.smartie.quotedesk.data.model.QuotationLineGeometry
import `in`.smartie.quotedesk.data.model.QuotationLineRecord
import `in`.smartie.quotedesk.data.model.QuotationPartySnapshot
import `in`.smartie.quotedesk.data.model.QuotationRecord
import `in`.smartie.quotedesk.data.model.QuotingRecord
import `in`.smartie.quotedesk.data.model.RateTierV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Editing a finalised quotation, decided before Firestore (N5.10 commit 3).
 *
 * **Every stored quotation here was issued by `QuotationWrite.plan` and read
 * back through the reader the app uses**, so a figure the edit gets wrong is
 * a figure the edit would really write over a quotation a customer holds —
 * not one a hand-built record happened to agree with.
 *
 * The worked quotation is `QuotationWriteTest`'s: a motor, a shutter at the
 * area rate, a fixed installation, 5% off, carriage "Mumbai to Vadodara",
 * GST at 18%. Stored `staff` is displayed **Manager**.
 */
class QuotationEditTest {

    private val manager = Member(uid = "u_m", name = "Manager Person", role = Role.STAFF)
    private val otherManager = Member(uid = "u_m2", name = "Other Manager", role = Role.STAFF)
    private val owner = Member(uid = "u_o", name = "Owner Person", role = Role.OWNER)

    private val capOfTen = QuotingRecord(managerDiscountPct = 10.0)
    private val capOfFive = QuotingRecord(managerDiscountPct = 5.0)
    private val counter = NumberingRecord(prefix = "SIE/QD", financialYear = "2025-26", next = 9, pad = 3)
    private val issuedAt = 1_760_000_000_000L
    private val editedAt = 1_760_500_000_000L

    private val shutter = AreaLine(width = 3000.0, height = 3500.0, count = 2.0)

    private val ready: QuoteDraft = QuoteDraft(
        id = "qd_1",
        tier = RateTierV2.CLIENT,
        lines = listOf(
            DraftLine(
                id = "ln_motor", title = "Sliding gate motor", key = "gateMotors|SIE1000",
                spec = "1000 kg", quantity = 2.0, rate = 22_200.0, tier = RateTierV2.CLIENT
            )
        ),
        party = QuotationPartySnapshot(name = "Walk-in Builders", site = "Plot 7", phone = "9876543210"),
        transport = 1_500.0,
        transportNote = "Mumbai to Vadodara",
        installation = Installation(InstallationMode.FIXED, 2_000.0),
        discount = Discount(DiscountKind.PERCENT, 5.0),
        gstPercent = 18.0
    ).addArea(id = "ln_shutter", area = shutter, rate = 450.0, title = "Rolling shutter")

    /** Issue [draft] as [member] and hand back the stored map the plan wrote. */
    private fun issued(draft: QuoteDraft = ready, member: Member = manager, cap: QuotingRecord = capOfTen): Map<String, Any?> {
        val plan = QuotationWrite.plan(draft, member, cap, counter, null, emptyList(), null, issuedAt)
        return (plan as QuotationPlan.Write).quotation
    }

    /** Read a stored map back through the app's own reader. */
    private fun read(stored: Map<String, Any?>): QuotationRecord = DocData(stored["id"] as String, stored).toQuotationRecord()

    private var lineIds = 0
    private fun open(record: QuotationRecord): QuoteDraft =
        QuotationEdit.draftFrom(record, editedAt) { "ln_edit_${lineIds++}" }

    private fun save(
        edited: QuoteDraft,
        stored: QuotationRecord?,
        member: Member = manager,
        quoting: QuotingRecord? = capOfTen,
        customers: List<PartyRecord> = emptyList()
    ): EditPlan = QuotationEdit.plan(edited, member, quoting, stored, customers, editedAt)

    private fun fieldsOf(plan: EditPlan): Map<String, Any?> = (plan as EditPlan.Update).fields

    // --- the round trip: an edit that changes nothing changes nothing --------------

    @Test
    fun `an unchanged edit writes the stored lines and every figure back exactly`() {
        val stored = issued()
        val record = read(stored)

        val fields = fieldsOf(save(open(record), record))

        assertEquals("the lines, key for key", stored["lines"], fields["lines"])
        assertEquals(stored["subtotal"], fields["subtotal"])
        assertEquals(stored["total"], fields["total"])
        assertEquals(stored["install"], fields["install"])
        assertEquals(stored["disc"], fields["disc"])
        assertEquals(stored["discBase"], fields["discBase"])
        assertEquals(stored["party"], fields["party"])
        assertEquals(stored["tier"], fields["tier"])
        assertEquals(stored["gstPct"], fields["gstPct"])
    }

    @Test
    fun `the discount base excludes carriage after an edit`() {
        // R8. Carriage is a line in `lines`; rebuilt as an ordinary line it
        // would count as products and the 5% would be taken on it too.
        val record = read(issued())

        val draft = open(record)

        assertEquals(1_500.0, draft.transport, 0.0)
        assertEquals("Mumbai to Vadodara", draft.transportNote)
        assertFalse(draft.lines.any { it.title == QuotationWrite.TRANSPORT_TITLE })
        assertEquals(148_550.0, draft.totals().discountBase, 0.0)
        assertEquals(7_428.0, draft.totals().discount, 0.0)
    }

    @Test
    fun `V8C4's own carriage line is recognised - its empty unit reads back as each`() {
        // V8C4 stores `u: ""`; the reader skips a blank string and returns its
        // default, `each`. Both must be recognised or no V8C4 carriage ever is.
        val v8c4 = DocData(
            "q_v8c4",
            mapOf(
                "id" to "q_v8c4", "no" to "SIE/QD/2025-26/010", "byUid" to "u_m", "tier" to "dealer",
                "gst" to true, "gstPct" to 18.0, "subtotal" to 24_700.0, "total" to 29_146.0,
                "party" to mapOf("name" to "Sunrise Constructions"),
                "lines" to listOf(
                    mapOf("t" to "Sliding gate motor", "s" to "", "u" to "each", "qty" to 1.0, "rate" to 22_200.0,
                        "origRate" to 22_200.0, "k" to "gateMotors|SIE1000", "manual" to false, "amt" to 22_200.0),
                    mapOf("t" to "Transportation", "s" to "Pune", "u" to "", "qty" to 1.0, "rate" to 2_500.0,
                        "origRate" to 2_500.0, "k" to null, "manual" to false, "amt" to 2_500.0)
                )
            )
        ).toQuotationRecord()

        val draft = open(v8c4)

        assertEquals(2_500.0, draft.transport, 0.0)
        assertEquals("Pune", draft.transportNote)
        assertEquals(1, draft.lines.size)
    }

    @Test
    fun `a hand-typed Transportation line is a line, not the carriage`() {
        val record = read(issued()).let { r ->
            r.copy(lines = r.lines.filterNot { it.title == QuotationWrite.TRANSPORT_TITLE } +
                QuotationLineRecord(title = "Transportation", unit = "", quantity = 1.0, rate = 900.0,
                    manual = true, amount = 900.0))
        }
        val draft = open(record)

        assertEquals(0.0, draft.transport, 0.0)
        assertTrue(draft.lines.any { it.title == "Transportation" && it.manual })
    }

    // --- area geometry ----------------------------------------------------------------

    @Test
    fun `an area line whose minimum applied comes back unchanged`() {
        // R9. 600 x 600 mm is 3.87 sq ft, 4 rounded up; the product's minimum
        // of 10 is what was charged, and the minimum itself is not stored.
        val small = QuoteDraft(
            id = "qd_small", tier = RateTierV2.CLIENT,
            party = QuotationPartySnapshot(name = "Walk-in Builders"), gstPercent = 18.0
        ).addArea(
            id = "ln_small",
            area = AreaLine(width = 600.0, height = 600.0, count = 3.0, minimumSqft = 10.0),
            rate = 450.0,
            title = "Rolling shutter"
        )
        val stored = issued(small)
        val record = read(stored)

        val draft = open(record)
        val line = draft.lines.single()

        assertEquals(10.0, line.area?.minimumSqft)
        assertEquals(30.0, line.quantity, 0.0)
        assertEquals(stored["lines"], fieldsOf(save(draft, record))["lines"])
    }

    @Test
    fun `an area line without a minimum restores none`() {
        val record = read(issued())
        val line = open(record).lines.single { it.isArea }

        assertNull(line.area?.minimumSqft)
        assertEquals(227.0, line.quantity, 0.0)
    }

    @Test
    fun `geometry that does not add up is dropped and the stored quantity kept`() {
        // The PWA's re-save, or a hand edit: `qty` carries the rupees, so it
        // stands and the line is edited as a plain line.
        val record = read(issued()).let { r ->
            r.copy(lines = r.lines.map { line ->
                if (line.geometry != null) line.copy(quantity = 250.0) else line
            })
        }
        val line = open(record).lines.single { it.title == "Rolling shutter" }

        assertNull(line.area)
        assertEquals(250.0, line.quantity, 0.0)
    }

    // --- what the reader defaults, an edit may not ----------------------------------

    @Test
    fun `a rate V8C4 had typed by hand comes back typed by hand`() {
        val record = read(issued()).let { r ->
            r.copy(lines = r.lines.map { if (it.key == "gateMotors|SIE1000") it.copy(rate = 20_000.0, originalRate = 22_200.0) else it })
        }
        val lines = open(record).lines

        assertTrue(lines.single { it.key == "gateMotors|SIE1000" }.rateEdited)
        assertFalse("a catalogue line at its own rate is not", read(issued()).let(::open).lines
            .single { it.key == "gateMotors|SIE1000" }.rateEdited)
    }

    @Test
    fun `an unknown stored tier is a fault recovered to Client, never Dealer`() {
        val draft = open(read(issued()).copy(storedTier = "wholesale"))

        assertEquals(RateTierV2.CLIENT, draft.tier)
        assertTrue(DraftFault.TIER in draft.faults)
        assertEquals(DraftFault.TIER.message, (save(draft, read(issued())) as EditPlan.Refused).message)
    }

    @Test
    fun `a Contractor quotation keeps its tier on an edit and is not refused for it`() {
        // Q6: the stored tier stands. The builder no longer offers Contractor,
        // but an edit corrects a quotation; it does not reissue it.
        val record = read(issued()).copy(storedTier = "contractor", tier = RateTierV2.CONTRACTOR)
        val draft = open(record)

        assertEquals(RateTierV2.CONTRACTOR, draft.tier)
        assertEquals("contractor", fieldsOf(save(draft, record))["tier"])
        // A new quotation at that tier is still refused.
        assertEquals(QuoteDraft.TIER_NOT_OFFERED, draft.copy(editOf = null).refusal(QuoteMath.NO_CAP))
    }

    @Test
    fun `an absent GST rate is not set, and the save is refused rather than charging 0 percent`() {
        val record = read(issued()).copy(storedGstPercent = null)
        val draft = open(record)

        assertNull(draft.gstPercent)
        assertEquals(QuoteDraft.GST_NOT_SET, (save(draft, record) as EditPlan.Refused).message)
    }

    @Test
    fun `an unreadable installation is a fault and refuses the save`() {
        val record = read(issued()).let { it.copy(installation = it.installation?.copy(mode = "weekly")) }
        val draft = open(record)

        assertNull(draft.installation)
        assertEquals(DraftFault.INSTALLATION.message, (save(draft, record) as EditPlan.Refused).message)
    }

    // --- the link ---------------------------------------------------------------------

    private val walkIn = PartyRecord(id = "c_walk", name = "Walk-in Builders", phone = "9876543210")
    private val metro = PartyRecord(id = "c_metro", name = "Metro Glass", phone = "9822001100")

    @Test
    fun `an absent partyId stays absent - it is never re-resolved`() {
        // R10. The form matches a saved customer exactly, and still no link is
        // made: a walk-in quoted without being filed stays unfiled.
        val record = read(issued())
        assertEquals("", record.partyId)

        val fields = fieldsOf(save(open(record), record, customers = listOf(walkIn)))

        assertFalse(fields.containsKey("partyId"))
    }

    @Test
    fun `a stored link stays while the form is still that party`() {
        val record = read(issued()).copy(partyId = "c_walk")
        assertEquals("c_walk", fieldsOf(save(open(record), record, customers = listOf(walkIn)))["partyId"])
    }

    @Test
    fun `a stored link the form no longer matches is removed, not kept`() {
        val record = read(issued()).copy(partyId = "c_walk")
        val retyped = open(record).let { it.copy(party = QuotationPartySnapshot(name = "Harbour Interiors")) }

        assertEquals(DeleteField, fieldsOf(save(retyped, record, customers = listOf(walkIn)))["partyId"])
    }

    @Test
    fun `a customer picked while editing replaces the link`() {
        val record = read(issued())
        val picked = open(record).withParty(metro)

        assertEquals("c_metro", fieldsOf(save(picked, record, customers = listOf(walkIn, metro)))["partyId"])
    }

    // --- two editors, and a quotation that moved --------------------------------------

    @Test
    fun `a quotation changed since it was opened is a conflict naming who and when`() {
        val opened = read(issued())
        val draft = open(opened)
        val now = opened.copy(revision = 1, lastEditedBy = "Asha Nair", lastEditedAt = 1_760_300_000_000L)

        val conflict = (save(draft, now) as EditPlan.Conflict).conflict

        assertEquals(EditConflict.Changed("SIE/QD/2025-26/009", "Asha Nair", 1_760_300_000_000L), conflict)
        assertEquals(
            "Not saved — SIE/QD/2025-26/009 was changed by Asha Nair at 11:20 after you opened it. " +
                "Your changes are still here: discard them and edit again.",
            conflict.message { "11:20" }
        )
    }

    @Test
    fun `a quotation cancelled since it was opened says so`() {
        val opened = read(issued())
        val now = opened.copy(status = "Cancelled", cancelledBy = "Owner Person")

        val conflict = (save(open(opened), now) as EditPlan.Conflict).conflict

        assertEquals(
            "Not saved — SIE/QD/2025-26/009 was cancelled by Owner Person. A cancelled quotation cannot be edited.",
            conflict.message { "" }
        )
    }

    @Test
    fun `a quotation that has gone says so`() {
        val opened = read(issued())
        val conflict = (save(open(opened), null) as EditPlan.Conflict).conflict

        assertEquals("Not saved — SIE/QD/2025-26/009 could not be found.", conflict.message { "" })
    }

    // --- who may ----------------------------------------------------------------------

    @Test
    fun `another Manager's quotation is refused, and an Owner may edit it`() {
        val record = read(issued())

        assertEquals(QuotationEdit.NOT_YOURS, (save(open(record), record, member = otherManager) as EditPlan.Refused).message)
        assertTrue(save(open(record), record, member = owner) is EditPlan.Update)
    }

    @Test
    fun `a draft that is not an edit is refused`() {
        assertEquals(QuotationEdit.NOT_AN_EDIT, (save(ready, read(issued())) as EditPlan.Refused).message)
    }

    // --- what is written --------------------------------------------------------------

    @Test
    fun `every key written is one the rule allows, and snap never`() {
        // The rule's list is pinned by the emulator's "an edit cannot touch …"
        // tests; this pins the app's side of the same list.
        val record = read(issued())
        val fields = fieldsOf(save(open(record).copy(installation = null, discount = null), record))

        assertTrue(fields.keys.toString(), QuotationEdit.EDITABLE.containsAll(fields.keys))
        listOf("snap", "no", "at", "by", "byUid", "status", "id", "serverAt").forEach {
            assertFalse(it, fields.containsKey(it))
        }
    }

    @Test
    fun `the stamp names the editor, and the revision moves by exactly one`() {
        val record = read(issued()).copy(revision = 3)
        val fields = fieldsOf(save(open(record), record, member = owner))

        assertEquals("Owner Person", fields["lastEditedBy"])
        assertEquals("u_o", fields["lastEditedByUid"])
        assertEquals(editedAt, fields["lastEditedAt"])
        assertEquals(4, fields["rev"])
    }

    @Test
    fun `an edit that really changes a line writes the new line`() {
        val record = read(issued())
        val draft = open(record).let { d -> d.setQuantity(d.lines.first { it.key == "gateMotors|SIE1000" }.id, 5.0) }

        @Suppress("UNCHECKED_CAST")
        val lines = fieldsOf(save(draft, record))["lines"] as List<Map<String, Any?>>

        assertEquals(5.0, lines.first { it["k"] == "gateMotors|SIE1000" }["qty"])
    }

    @Test
    fun `an installation and a discount taken off are deleted, never zeroed`() {
        val record = read(issued())
        val fields = fieldsOf(save(open(record).copy(installation = null, discount = null), record))

        assertEquals(DeleteField, fields["install"])
        assertEquals(DeleteField, fields["disc"])
        assertEquals(DeleteField, fields["discBase"])
    }

    // --- the cap on an edit: only when the discount goes up (amendment A) -----------

    /** Issued at [pct]% by the Manager under a 10% cap. */
    private fun discountedAt(pct: Double): QuotationRecord =
        read(issued(ready.copy(discount = Discount(DiscountKind.PERCENT, pct)), cap = capOfTen))

    @Test
    fun `(i) a phone number fixed under a lowered cap is saved`() {
        val record = discountedAt(10.0)
        val draft = open(record).let { it.copy(party = it.party.copy(phone = "9876543211")) }

        assertTrue(save(draft, record, quoting = capOfFive) is EditPlan.Update)
    }

    @Test
    fun `(ii) the discount raised past a lowered cap is refused with the cap's sentence`() {
        val record = discountedAt(4.0)
        val draft = open(record).copy(discount = Discount(DiscountKind.PERCENT, 8.0))

        assertEquals(
            QuoteMath.discountRefusal(Discount(DiscountKind.PERCENT, 8.0), draft.discountBase, 5.0),
            (save(draft, record, quoting = capOfFive) as EditPlan.Refused).message
        )
    }

    @Test
    fun `(iii) lines added under an unchanged percentage are capped`() {
        val record = discountedAt(10.0)
        val draft = open(record).let { d -> d.setQuantity(d.lines.first { !it.isArea }.id, 6.0) }

        assertTrue(save(draft, record, quoting = capOfFive) is EditPlan.Refused)
    }

    @Test
    fun `(v) lines removed under an unchanged percentage are saved`() {
        val record = discountedAt(10.0)
        val draft = open(record).let { d -> d.remove(d.lines.first { it.isArea }.id) }

        assertTrue(save(draft, record, quoting = capOfFive) is EditPlan.Update)
    }

    @Test
    fun `the raise is judged against the stored discount, not the one the screen opened`() {
        // Opened at 4% and raised to 8%: against the opened copy that is a
        // raise past a 5% cap. The quotation read in the transaction holds 9%
        // — so 8% is not a raise at all, and the cap is not asked.
        val opened = discountedAt(4.0)
        val draft = open(opened).copy(discount = Discount(DiscountKind.PERCENT, 8.0))
        val stored = discountedAt(9.0)

        assertTrue(save(draft, stored, quoting = capOfFive) is EditPlan.Update)
        // The witness: against the 4% it is refused.
        assertTrue(save(draft, opened, quoting = capOfFive) is EditPlan.Refused)
    }
}
