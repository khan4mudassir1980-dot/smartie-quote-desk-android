package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.model.RateTierV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What somebody typed into the two "add a line" forms.
 *
 * **The empty rate box is the one that decides the shape.** Blank means
 * "price not set" and must stay null all the way through (audit P2); zero
 * means free. A parser that reads a blank box as `0.0` loses that distinction
 * before anybody can act on it, and the line goes out priced at nothing.
 *
 * **A box holding `12x` is refused, never read as 12.** Silently dropping the
 * character somebody typed is a money-affecting fallback of exactly the kind
 * the draft codec already refuses to make.
 */
class QuoteLineEntryTest {

    // --- reading a number box -------------------------------------------------

    @Test
    fun `a blank rate box is not set, and is not zero`() {
        assertNull(QuoteLineEntry.number(""))
        assertNull(QuoteLineEntry.number("   "))
        assertEquals(0.0, QuoteLineEntry.number("0")!!, 0.0)
    }

    @Test
    fun `a number typed with the grouping a person reads is still a number`() {
        assertEquals(22200.0, QuoteLineEntry.number("22,200")!!, 0.0)
        assertEquals(1234.5, QuoteLineEntry.number(" 1,234.5 ")!!, 0.0)
    }

    @Test
    fun `what is not a number is refused, never partly read`() {
        assertNull(QuoteLineEntry.number("12x"))
        assertNull(QuoteLineEntry.number("abc"))
        // Infinity is a number to Kotlin and not to a quotation.
        assertNull(QuoteLineEntry.number("Infinity"))
    }

    // --- a line typed by hand -------------------------------------------------

    @Test
    fun `a line with no description is refused`() {
        assertEquals(
            QuoteLineEntry.NO_DESCRIPTION,
            ManualEntry(title = "   ", rate = "2000").refusal()
        )
    }

    @Test
    fun `a negative rate is refused, and so is one that is not a number`() {
        assertEquals(
            QuoteLineEntry.NEGATIVE_RATE,
            ManualEntry(title = "Site visit", rate = "-1").refusal()
        )
        assertEquals(
            QuoteLineEntry.NOT_A_RATE,
            ManualEntry(title = "Site visit", rate = "12x").refusal()
        )
    }

    @Test
    fun `a quantity of zero or of nonsense is refused`() {
        assertEquals(
            QuoteLineEntry.NOT_A_QUANTITY,
            ManualEntry(title = "Site visit", quantity = "0").refusal()
        )
        assertEquals(
            QuoteLineEntry.NOT_A_QUANTITY,
            ManualEntry(title = "Site visit", quantity = "two").refusal()
        )
    }

    @Test
    fun `a line with no rate at all is allowed, and stays unpriced`() {
        val entry = ManualEntry(title = "Site visit", rate = "")
        assertNull(entry.refusal())

        val line = entry.addTo(QuoteDraft(id = "qd_1"), "ln_1").lines.single()
        assertNull("blank is not set, and never zero", line.rate)
        assertTrue(line.needsRate)
    }

    @Test
    fun `a hand-typed line is manual, and its rate survives a change of tier`() {
        val draft = ManualEntry(title = "Site visit", rate = "2000")
            .addTo(QuoteDraft(id = "qd_1"), "ln_1")
        val line = draft.lines.single()

        assertTrue(line.manual)
        assertTrue(line.rateEdited)
        assertEquals(2000.0, line.rate!!, 0.0)

        val repriced = draft.withTier(RateTierV2.DEALER) { 1.0 }
        assertEquals(0, repriced.repriced)
        assertEquals(2000.0, repriced.draft.lines.single().rate!!, 0.0)
    }

    // --- an opening -----------------------------------------------------------

    @Test
    fun `an opening with no measurement is refused in the arithmetic's own words`() {
        assertEquals(
            QuoteArea.NOT_A_MEASUREMENT,
            AreaEntry(title = "Shutter", width = "0", height = "3500").refusal()
        )
        assertEquals(
            QuoteArea.NO_OPENINGS,
            AreaEntry(title = "Shutter", width = "3000", height = "3500", count = "0").refusal()
        )
    }

    @Test
    fun `an opening stores the total chargeable area as its quantity`() {
        val entry = AreaEntry(
            title = "Rolling shutter",
            width = "3000",
            height = "3500",
            count = "2",
            rate = "450"
        )
        assertNull(entry.refusal())

        val line = entry.addTo(QuoteDraft(id = "qd_1"), "ln_a").lines.single()
        // 113.5 chargeable sq ft per opening, two of them.
        assertEquals(227.0, line.quantity, 0.0)
        assertEquals(ProductUnit.AREA, line.unit)
        assertEquals(102_150.0, line.amount!!, 0.0)
    }

    @Test
    fun `reopening an opening shows back what was typed`() {
        val line = AreaEntry(
            title = "Rolling shutter",
            width = "3000",
            height = "3500",
            count = "2",
            rate = "450",
            minimumSqft = "120"
        ).addTo(QuoteDraft(id = "qd_1"), "ln_a").lines.single()

        val reopened = AreaEntry.of(line)
        assertEquals("3000", reopened.width)
        assertEquals("3500", reopened.height)
        assertEquals("2", reopened.count)
        assertEquals("450", reopened.rate)
        assertEquals("120", reopened.minimumSqft)
        assertEquals(DimensionUnit.MM, reopened.unit)
    }

    @Test
    fun `correcting a measurement recomputes the quantity from the opening`() {
        val draft = AreaEntry(
            title = "Rolling shutter", width = "3000", height = "3500", count = "2", rate = "450"
        ).addTo(QuoteDraft(id = "qd_1"), "ln_a")
        val line = draft.lines.single()

        val corrected = AreaEntry.of(line).copy(height = "4000").applyTo(draft, line)
        val after = corrected.lines.single()

        // 3000 x 4000 mm is 129.5 chargeable sq ft, two of them.
        assertEquals(259.0, after.quantity, 0.0)
        assertEquals(QuoteArea.totalSqft(after.area!!), after.quantity, 0.0)
    }

    @Test
    fun `correcting a measurement does not freeze a catalogue rate`() {
        // `setRate` marks a line hand-typed, and a hand-typed rate is never
        // repriced by a change of tier. Reopening the form to fix a width
        // must not do that to a line the catalogue prices.
        val draft = AreaEntry(
            title = "Rolling shutter", width = "3000", height = "3500", count = "2", rate = "450"
        ).addTo(QuoteDraft(id = "qd_1"), "ln_a", key = "rs|RS500")
        val line = draft.lines.single()
        assertTrue("a keyed area line starts catalogue-priced", !line.rateEdited)

        val corrected = AreaEntry.of(line).copy(height = "4000").applyTo(draft, line)
        assertTrue(!corrected.lines.single().rateEdited)

        // But actually typing a different rate is a person's decision, and is
        // recorded as one.
        val retyped = AreaEntry.of(line).copy(rate = "500").applyTo(draft, line)
        assertTrue(retyped.lines.single().rateEdited)
        assertEquals(500.0, retyped.lines.single().rate!!, 0.0)
    }

    @Test
    fun `an opening with no minimum keeps none, rather than a zero`() {
        val line = AreaEntry(
            title = "Shutter", width = "3000", height = "3500", rate = "450"
        ).addTo(QuoteDraft(id = "qd_1"), "ln_a").lines.single()

        assertNull(line.area!!.minimumSqft)
    }
}
