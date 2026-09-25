package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.mapping.DocData
import `in`.smartie.quotedesk.data.mapping.toQuotationRecord
import `in`.smartie.quotedesk.data.model.NumberingRecord
import `in`.smartie.quotedesk.data.model.PartyRecord
import `in`.smartie.quotedesk.data.model.QuotationLineGeometry
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
 * Finalise, decided before Firestore: the document, the counter, and every
 * reason not to write either.
 *
 * **One worked quotation carries every money field at once**, so a figure
 * that lands in the wrong key cannot hide behind a zero:
 *
 * | Step | Figure |
 * |---|---|
 * | Sliding gate motor, 2 × ₹22,200 | ₹44,400 |
 * | Rolling shutter, 3000 × 3500 mm = 113.02 → **113.5** sq ft × 2 nos × ₹450 | ₹1,02,150 |
 * | **Products** | **₹1,46,550** |
 * | Installation, fixed | ₹2,000 |
 * | **Discount base** | **₹1,48,550** |
 * | 5% (₹7,427.50, HALF_UP) — exactly a Manager's cap of 5 | − ₹7,428 |
 * | Transport, "Mumbai to Vadodara" | ₹1,500 |
 * | **Subtotal** | **₹1,42,622** |
 * | GST 18% (₹25,671.96) | + ₹25,672 |
 * | **Total** | **₹1,68,294** |
 *
 * Stored `staff` is displayed **Manager** and stored `worker` **Staff**.
 */
class QuotationWriteTest {

    private val manager = Member(uid = "u_m", name = "Manager Person", role = Role.STAFF)
    private val owner = Member(uid = "u_o", name = "Owner Person", role = Role.OWNER)
    private val staff = Member(uid = "u_w", name = "Staff Person", role = Role.WORKER)

    private val capOfFive = QuotingRecord(managerDiscountPct = 5.0)
    private val counter = NumberingRecord(prefix = "SIE/QD", financialYear = "2025-26", next = 9, pad = 3)
    private val at = 1_760_000_000_000L
    private val snap = mapOf<String, Any?>("validityDays" to 15)

    private val shutter = AreaLine(width = 3000.0, height = 3500.0, count = 2.0)

    private val ready: QuoteDraft = QuoteDraft(
        id = "qd_1",
        tier = RateTierV2.CLIENT,
        lines = listOf(
            DraftLine(
                id = "ln_motor",
                title = "Sliding gate motor",
                key = "gateMotors|SIE1000",
                spec = "1000 kg",
                quantity = 2.0,
                rate = 22_200.0,
                tier = RateTierV2.CLIENT
            )
        ),
        party = QuotationPartySnapshot(name = "Walk-in Builders", site = "Plot 7"),
        transport = 1_500.0,
        transportNote = "Mumbai to Vadodara",
        installation = Installation(InstallationMode.FIXED, 2_000.0),
        discount = Discount(DiscountKind.PERCENT, 5.0),
        gstPercent = 18.0
    ).addArea(id = "ln_shutter", area = shutter, rate = 450.0, title = "Rolling shutter")

    private fun plan(
        draft: QuoteDraft = ready,
        member: Member = manager,
        quoting: QuotingRecord? = capOfFive,
        numbering: NumberingRecord? = counter,
        existing: QuotationRecord? = null,
        customer: PartyRecord? = null
    ): QuotationPlan = QuotationWrite.plan(
        draft = draft,
        member = member,
        quoting = quoting,
        counter = numbering,
        existing = existing,
        customer = customer,
        snap = snap,
        at = at
    )

    private fun write(plan: QuotationPlan): QuotationPlan.Write {
        assertTrue("expected a write, got $plan", plan is QuotationPlan.Write)
        return plan as QuotationPlan.Write
    }

    @Suppress("UNCHECKED_CAST")
    private fun lines(write: QuotationPlan.Write): List<Map<String, Any?>> =
        write.quotation["lines"] as List<Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.child(key: String): Map<String, Any?> = this[key] as Map<String, Any?>

    // --- the document ---------------------------------------------------------------

    @Test
    fun `a ready draft becomes one write, carrying what the create rule checks`() {
        val write = write(plan())

        // The five the deployed rule checks, each in the type it checks.
        assertEquals(write.quotationId, write.quotation["id"])
        assertEquals("SIE/QD/2025-26/009", write.quotation["no"])
        assertEquals(manager.uid, write.quotation["byUid"])
        assertEquals(at, write.quotation["at"])
        assertTrue(write.quotation["total"] is Double)

        assertEquals("Manager Person", write.quotation["by"])
        assertEquals("client", write.quotation["tier"])
        assertEquals("Client", write.quotation["tierName"])
        assertEquals("Finalised", write.quotation["status"])
        assertEquals(true, write.quotation["gst"])
        assertEquals(18.0, write.quotation["gstPct"])
    }

    @Test
    fun `the figures are the draft's own totals, and the rule's base bound holds on them`() {
        val write = write(plan())
        val q = write.quotation

        assertEquals(142_622.0, q["subtotal"])
        assertEquals(168_294.0, q["total"])
        assertEquals(
            mapOf("mode" to "fixed", "rate" to 2_000.0, "amt" to 2_000.0, "basis" to 0.0),
            q["install"]
        )
        assertEquals(mapOf("kind" to "pct", "value" to 5.0, "amt" to 7_428.0), q["disc"])
        assertEquals(148_550.0, q["discBase"])

        // `discountOk()`: the base is bounded by `subtotal + disc.amt`, and
        // with transport on the quotation it sits exactly transport below it.
        val subtotal = q["subtotal"] as Double
        val base = q["discBase"] as Double
        val amount = q.child("disc")["amt"] as Double
        assertTrue(base <= subtotal + amount)
        assertEquals(subtotal + amount - 1_500.0, base, 0.0)
    }

    @Test
    fun `the document id is the draft's own id, so every attempt targets one document`() {
        // The whole of the double-finalise defence. Minting an id here instead
        // — `Keys.generateId` per call — fails this: two attempts, two
        // documents, two numbers.
        val first = write(plan())
        val second = write(QuotationWrite.plan(ready, manager, capOfFive, counter, null, null, snap, at + 5_000))
        assertEquals("qd_1", first.quotationId)
        assertEquals(first.quotationId, second.quotationId)
    }

    @Test
    fun `snap is written exactly as it was given`() {
        assertEquals(snap, write(plan()).quotation["snap"])
    }

    @Test
    fun `no discount taken writes neither disc nor discBase`() {
        for (draft in listOf(ready.copy(discount = null), ready.copy(discount = Discount(DiscountKind.PERCENT, 0.0)))) {
            val q = write(plan(draft = draft)).quotation
            assertFalse(q.containsKey("disc"))
            assertFalse(q.containsKey("discBase"))
        }
    }

    @Test
    fun `an unpriced line is refused`() {
        val unrated = ready.setRate("ln_motor", null)
        assertEquals(QuotationPlan.Refused(QuoteDraft.LINE_NEEDS_RATE), plan(draft = unrated))
    }

    // --- the counter ------------------------------------------------------------------

    @Test
    fun `the number is the stored next, and the counter moves on by exactly one`() {
        val write = write(plan())
        assertEquals("SIE/QD/2025-26/009", write.number)

        // Nothing else, or the issue branch's `hasOnly(['next','lastIssued'])`
        // refuses the whole transaction.
        assertEquals(setOf("next", "lastIssued"), write.counter.keys)
        assertEquals(10, write.counter["next"])
        assertEquals(
            mapOf(
                "no" to "SIE/QD/2025-26/009",
                "at" to at,
                "by" to "Manager Person",
                "uid" to manager.uid,
                "src" to "android"
            ),
            write.counter.child("lastIssued")
        )
    }

    @Test
    fun `the issuer marker fits the rule's sixteen characters`() {
        // `numberingSrcOk()`: `src is string && src.size() <= 16`.
        assertEquals("android", QuotationWrite.SOURCE)
        assertTrue(QuotationWrite.SOURCE.length <= 16)
    }

    @Test
    fun `an unseeded or half-configured counter is refused rather than issuing a malformed number`() {
        val refused = QuotationPlan.Refused(QuotationWrite.NUMBERING_NOT_SET)
        assertEquals(refused, plan(numbering = null))
        // `Numbering.format` drops a blank segment, so either of these would
        // otherwise issue something like `2025-26/009`.
        assertEquals(refused, plan(numbering = counter.copy(prefix = "")))
        assertEquals(refused, plan(numbering = counter.copy(financialYear = " ")))
        assertEquals(refused, plan(numbering = counter.copy(next = 0)))
    }

    // --- a retry, and a collision ----------------------------------------------------------

    @Test
    fun `an existing quotation returns its stored number and writes nothing`() {
        val issued = QuotationRecord(id = "qd_1", number = "SIE/QD/2025-26/007", byUid = manager.uid)
        assertEquals(
            QuotationPlan.AlreadyIssued("qd_1", "SIE/QD/2025-26/007"),
            plan(existing = issued)
        )
    }

    @Test
    fun `an issued quotation stays issued, whatever has changed since`() {
        // The first attempt succeeded and its response was lost; since then
        // the counter has gone and the cap has been withdrawn. The quotation
        // the customer holds still happened. Moving the existence check below
        // the refusals fails this test.
        val issued = QuotationRecord(id = "qd_1", number = "SIE/QD/2025-26/007", byUid = manager.uid)
        assertEquals(
            QuotationPlan.AlreadyIssued("qd_1", "SIE/QD/2025-26/007"),
            plan(existing = issued, quoting = null, numbering = null)
        )
    }

    @Test
    fun `somebody else's document under this id is refused, never claimed`() {
        // Claiming it would let the caller clear a draft that was never issued.
        val theirs = QuotationRecord(id = "qd_1", number = "SIE/QD/2025-26/004", byUid = owner.uid)
        assertEquals(QuotationPlan.Refused(QuotationWrite.ID_TAKEN), plan(existing = theirs))
    }

    @Test
    fun `a draft with no identity is refused, because a retry of it could not be recognised`() {
        assertEquals(QuotationPlan.Refused(QuotationWrite.NO_IDENTITY), plan(draft = ready.copy(id = "")))
    }

    // --- who may, and how much -----------------------------------------------------------

    @Test
    fun `Staff cannot issue a quotation`() {
        assertEquals(QuotationPlan.Refused(QuotationWrite.NOT_ALLOWED), plan(member = staff))
    }

    @Test
    fun `a Manager past the cap is refused with the figure they may have`() {
        val tenPercent = ready.copy(discount = Discount(DiscountKind.PERCENT, 10.0))
        assertEquals(
            QuotationPlan.Refused(QuoteMath.overTheCap(5.0, 7_428.0)),
            plan(draft = tenPercent)
        )
    }

    @Test
    fun `a Manager with no cap configured is told so, not told zero`() {
        assertEquals(QuotationPlan.Refused(QuoteDiscount.CAP_NOT_SET), plan(quoting = null))
        // And without a discount the missing cap is nobody's business.
        write(plan(draft = ready.copy(discount = null), quoting = null))
    }

    @Test
    fun `an Owner is uncapped`() {
        val tenPercent = ready.copy(discount = Discount(DiscountKind.PERCENT, 10.0))
        val q = write(plan(draft = tenPercent, member = owner)).quotation
        assertEquals(14_855.0, q.child("disc")["amt"])
    }

    // --- who it is for ----------------------------------------------------------------------

    @Test
    fun `a walk-in is written from the typed name, with partyId absent`() {
        // A customer record handed in anyway is ignored: there is no saved
        // customer to resolve.
        val q = write(plan(customer = PartyRecord(id = "c_9", name = "Someone Else"))).quotation
        assertFalse(q.containsKey("partyId"))
        assertEquals("Walk-in Builders", q.child("party")["name"])
        assertEquals("Plot 7", q.child("party")["site"])
    }

    @Test
    fun `a saved customer is re-resolved from the record, keeping the draft's site`() {
        // Picked days ago under an older name; renamed since.
        val picked = ready.copy(
            partyId = "c_1",
            party = QuotationPartySnapshot(name = "Sunrise Constructions", site = "Plot 7")
        )
        val now = PartyRecord(
            id = "c_1",
            name = "Sunrise Constructions Pvt Ltd",
            phone = "9876543210",
            city = "Mumbai"
        )
        val q = write(plan(draft = picked, customer = now)).quotation
        assertEquals("c_1", q["partyId"])
        val party = q.child("party")
        assertEquals("Sunrise Constructions Pvt Ltd", party["name"])
        assertEquals("9876543210", party["phone"])
        assertEquals("Mumbai", party["city"])
        assertEquals("Plot 7", party["site"])
    }

    @Test
    fun `a saved customer that cannot be found is refused, not written from the stale snapshot`() {
        val picked = ready.copy(partyId = "c_1")
        assertEquals(QuotationPlan.Refused(QuotationWrite.CUSTOMER_GONE), plan(draft = picked, customer = null))
    }

    @Test
    fun `a quotation for nobody is refused`() {
        val nobody = ready.copy(party = QuotationPartySnapshot(name = " "))
        assertEquals(QuotationPlan.Refused(QuoteDraft.NO_PARTY), plan(draft = nobody))
    }

    // --- the lines ------------------------------------------------------------------------------

    @Test
    fun `transport is a Transportation line inside the subtotal, carrying its note`() {
        val all = lines(write(plan()))
        assertEquals(3, all.size)
        assertEquals(
            mapOf(
                "t" to "Transportation",
                "s" to "Mumbai to Vadodara",
                "u" to "no",
                "qty" to 1.0,
                "rate" to 1_500.0,
                "manual" to true,
                "amt" to 1_500.0
            ),
            all.last()
        )
    }

    @Test
    fun `no transport, no transport line`() {
        val all = lines(write(plan(draft = ready.copy(transport = 0.0))))
        assertEquals(2, all.size)
        assertTrue(all.none { it["t"] == "Transportation" })
    }

    @Test
    fun `an area line carries its opening, and a catalogue line carries none`() {
        val (motor, area) = lines(write(plan()))

        assertEquals("Rolling shutter", area["t"])
        assertEquals(227.0, area["qty"])
        assertEquals(450.0, area["rate"])
        assertEquals(102_150.0, area["amt"])
        assertEquals("per sq ft", area["u"])
        // The working, in the field V8C4 prints and keeps.
        assertEquals("3000 × 3500 mm = 113.5 sq ft × 2 nos", area["s"])
        assertEquals(3000.0, area["w"])
        assertEquals(3500.0, area["h"])
        assertEquals("mm", area["dim"])
        assertEquals(113.5, area["sqft"])
        assertEquals(2.0, area["nos"])

        assertEquals("gateMotors|SIE1000", motor["k"])
        for (key in listOf("w", "h", "dim", "sqft", "nos")) assertFalse(key, motor.containsKey(key))
    }

    @Test
    fun `the opening survives the reader, and a line without one reads as none`() {
        val write = write(plan())
        val read = DocData(write.quotationId, write.quotation).toQuotationRecord()

        assertEquals(
            QuotationLineGeometry(width = 3000.0, height = 3500.0, unit = "mm", sqftPerDoor = 113.5, count = 2.0),
            read.lines[1].geometry
        )
        assertNull(read.lines[0].geometry)
        assertNull(read.lines[2].geometry)
        // The money never depended on it.
        assertEquals(102_150.0, read.lines[1].amount, 0.0)
    }

    @Test
    fun `a partial opening reads as none, rather than as a guess`() {
        // What a hand-edited or half-stripped line might carry. N5.10 edits it
        // as a plain line; it never fills in the missing half.
        val stored = mapOf<String, Any?>(
            "lines" to listOf(
                mapOf("t" to "Rolling shutter", "u" to "per sq ft", "qty" to 227, "rate" to 450, "amt" to 102150, "w" to 3000, "h" to 3500)
            )
        )
        assertNull(DocData("q_x", stored).toQuotationRecord().lines.single().geometry)
    }
}
