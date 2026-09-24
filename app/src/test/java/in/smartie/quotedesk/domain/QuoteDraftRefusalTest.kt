package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.model.QuotationPartySnapshot
import `in`.smartie.quotedesk.data.model.RateTierV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The single gate, and the invariant that depends on it having been called.
 *
 * **`QuoteMath.totals` has no floor.** It computes
 * `discountBase - discount + transport` from whatever it is given, so a
 * negative discount, a negative transport or a negative installation each
 * produce a negative subtotal without complaint. Nothing in the arithmetic
 * stops that; three gates in [QuoteDraft.refusal] do.
 *
 * It matters beyond tidiness. Every figure is rounded HALF_UP, which rounds a
 * half **away from zero**, while V8C4 prints with JavaScript's `Math.round`,
 * which rounds a half toward **+infinity**. They agree on every non-negative
 * value and disagree on negative halves — so while the invariant holds the two
 * apps print the same number, and the moment it does not they quietly stop.
 */
class QuoteDraftRefusalTest {

    private val party = QuotationPartySnapshot(name = "Sunrise Constructions")

    /** A draft that is ready to go, so each test breaks exactly one thing. */
    private val ready = QuoteDraft(
        id = "qd_1",
        tier = RateTierV2.CLIENT,
        partyId = "c_1",
        party = party,
        gstPercent = 18.0
    ).addManual(id = "ln_1", title = "Sliding gate motor", quantity = 2.0, rate = 22200.0)

    private val uncapped = QuoteMath.NO_CAP

    @Test
    fun `a draft with everything in place is not refused`() {
        assertNull(ready.refusal(uncapped))
    }

    // --- the three gates ------------------------------------------------------------

    @Test
    fun `a negative transport is refused, and the subtotal never goes below zero`() {
        // Larger than the quotation, so the subtotal really does go under —
        // which is the point: the arithmetic accepts it without complaint.
        val broken = ready.copy(transport = -50_000.0)
        assertEquals(QuoteDraft.NEGATIVE_TRANSPORT, broken.refusal(uncapped))
        assertTrue("totals has no floor of its own", broken.totals().subtotal < 0.0)
        assertTrue(ready.totals().subtotal >= 0.0)
    }

    @Test
    fun `a negative installation is refused, on the rate and on the basis alike`() {
        val badRate = ready.copy(installation = Installation(InstallationMode.FIXED, -1000.0))
        assertEquals(QuoteDraft.NEGATIVE_INSTALLATION, badRate.refusal(uncapped))

        val badBasis = ready.copy(
            installation = Installation(InstallationMode.PER_DOOR, 1500.0, basis = -2.0)
        )
        assertEquals(QuoteDraft.NEGATIVE_INSTALLATION, badBasis.refusal(uncapped))

        val good = ready.copy(installation = Installation(InstallationMode.PER_DOOR, 1500.0, 2.0))
        assertNull(good.refusal(uncapped))
        assertTrue(good.totals().subtotal >= 0.0)
    }

    @Test
    fun `a discount equal to the whole quotation leaves nothing, and one rupee more is refused`() {
        val base = ready.discountBase
        assertEquals(44400.0, base, 0.0)

        val exact = ready.copy(discount = Discount(DiscountKind.RUPEES, base))
        assertNull(exact.refusal(uncapped))
        assertEquals(0.0, exact.totals().subtotal, 0.0)
        assertEquals(0.0, exact.totals().total, 0.0)

        val overBy1 = ready.copy(discount = Discount(DiscountKind.RUPEES, base + 1.0))
        assertEquals(QuoteMath.moreThanTheQuotation(base), overBy1.refusal(uncapped))
        assertTrue("and the arithmetic would have gone negative", overBy1.totals().subtotal < 0.0)
    }

    @Test
    fun `a Manager over the cap is refused with the cap and the figure named`() {
        val capped = ready.copy(discount = Discount(DiscountKind.PERCENT, 15.0))
        val allowed = QuoteMath.rupees(ready.discountBase * 10.0 / 100.0)
        assertEquals(QuoteMath.overTheCap(10.0, allowed), capped.refusal(capPercent = 10.0))
        // The same discount is fine for somebody uncapped.
        assertNull(capped.refusal(uncapped))
    }

    @Test
    fun `a missing quoting document means a cap of zero, and no discount at all`() {
        // The fail-closed ruling: an absent `/teamSettings/quoting` resolves to
        // 0, never to uncapped, so a configuration gap cannot quietly hand a
        // Manager an unlimited discount.
        val any = ready.copy(discount = Discount(DiscountKind.PERCENT, 1.0))
        assertEquals(
            QuoteMath.overTheCap(0.0, 0.0),
            any.refusal(capPercent = 0.0)
        )
        assertNull(ready.refusal(capPercent = 0.0))
    }

    // --- GST is resolved, never assumed ------------------------------------------------

    @Test
    fun `a draft with no resolved GST cannot be finalised, rather than charging zero`() {
        val unresolved = ready.copy(gstPercent = null)
        assertEquals(QuoteDraft.GST_NOT_SET, unresolved.refusal(uncapped))
        // And it charges nothing meanwhile, rather than pretending to be 0%.
        assertEquals(0.0, unresolved.totals().gst, 0.0)
    }

    @Test
    fun `GST switched off deliberately is not the same as one never set`() {
        val exempt = ready.copy(gstEnabled = false, gstPercent = null)
        assertNull(exempt.refusal(uncapped))
        assertEquals(0.0, exempt.totals().gst, 0.0)
    }

    @Test
    fun `the suggested rate is the one the lines agree on, and null when they do not`() {
        // The manual line is in the list deliberately: it has no product to
        // ask, so it must not be allowed to answer for the quotation.
        val draft = QuoteDraft(
            lines = listOf(
                DraftLine(id = "m", title = "Site visit", rate = 1.0, manual = true),
                DraftLine(id = "a", key = "g|A", title = "A", rate = 1.0),
                DraftLine(id = "b", key = "g|B", title = "B", rate = 1.0)
            )
        )
        assertEquals(18.0, draft.gstSuggestion { 18.0 }!!, 0.0)
        assertNull(draft.gstSuggestion { key -> if (key == "g|A") 18.0 else 12.0 })
        assertNull(QuoteDraft().gstSuggestion { 18.0 })
        // Manual lines only: nothing to agree on, so nothing is suggested.
        assertNull(
            QuoteDraft(lines = listOf(DraftLine(id = "m", title = "X", manual = true)))
                .gstSuggestion { 18.0 }
        )
    }

    // --- what the builder may not produce -----------------------------------------------

    @Test
    fun `the contractor tier is never offered on a new quotation`() {
        assertEquals(listOf(RateTierV2.DEALER, RateTierV2.CLIENT), QuoteTier.OFFERED)
        assertTrue(!QuoteTier.offers(RateTierV2.CONTRACTOR))
        assertEquals(
            QuoteDraft.TIER_NOT_OFFERED,
            ready.copy(tier = RateTierV2.CONTRACTOR).refusal(uncapped)
        )
    }

    @Test
    fun `an unpriced line, an empty quotation and a missing customer are each refused`() {
        assertEquals(QuoteDraft.NO_LINES, QuoteDraft(partyId = "c_1", gstPercent = 18.0).refusal(uncapped))
        assertEquals(QuoteDraft.NO_PARTY, ready.copy(partyId = "").refusal(uncapped))
        val unrated = ready.setRate("ln_1", null)
        assertEquals(QuoteDraft.LINE_NEEDS_RATE, unrated.refusal(uncapped))
    }

    // --- a damaged draft says so rather than choosing for somebody ------------------------

    @Test
    fun `a fault is reported before anything else, because it decides money`() {
        val damaged = ready.copy(faults = setOf(DraftFault.TIER))
        assertEquals(DraftFault.TIER.message, damaged.refusal(uncapped))
    }

    // --- the totals order, pinned from the draft's own side --------------------------------

    @Test
    fun `transport is inside the subtotal so GST falls on it, and the discount never does`() {
        val draft = ready.copy(
            transport = 2500.0,
            discount = Discount(DiscountKind.PERCENT, 10.0)
        )
        val totals = draft.totals()
        assertEquals(44400.0, totals.discountBase, 0.0)
        assertEquals(4440.0, totals.discount, 0.0)
        // 44400 - 4440 + 2500: the discount stopped before the carriage.
        assertEquals(42460.0, totals.subtotal, 0.0)
        // 18% of the lot, transport included.
        assertEquals(7643.0, totals.gst, 0.0)
        assertEquals(50103.0, totals.total, 0.0)
    }
}
