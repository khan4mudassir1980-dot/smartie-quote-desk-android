package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.model.QuotationPartySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The five states the GST control can be in.
 *
 * **The two that matter are the last two.** Changing a money field because
 * the lines changed is precisely the silent, money-affecting fallback N5.8a's
 * second amendment forbade — so when a later product disagrees, the
 * disagreement is *shown* and the rate stays where somebody put it. And
 * switching GST off deliberately stays distinguishable from never having set
 * it: the model separates them as `gstEnabled` against a null `gstPercent`,
 * and these are the words for each.
 */
class QuoteGstTest {

    @Test
    fun `off is a decision, and says so`() {
        assertEquals(QuoteGst.NO_GST, QuoteGst.note(enabled = false, percent = null, rates = listOf(18.0)))
        // Still off even when a rate was set earlier: the switch decides.
        assertEquals(QuoteGst.NO_GST, QuoteGst.note(enabled = false, percent = 18.0, rates = listOf(18.0)))
    }

    @Test
    fun `products that agree explain where the rate came from`() {
        assertEquals(
            QuoteGst.FROM_THE_PRODUCTS,
            QuoteGst.note(enabled = true, percent = 18.0, rates = listOf(18.0))
        )
    }

    @Test
    fun `products that disagree ask, rather than choosing`() {
        assertEquals(
            QuoteGst.THEY_DISAGREE,
            QuoteGst.note(enabled = true, percent = null, rates = listOf(18.0, 12.0))
        )
    }

    @Test
    fun `nothing to suggest is not the same as a disagreement`() {
        // A quotation of hand-typed lines. There is no rate to read off
        // anything, which is a third state and not a conflict.
        assertEquals(
            QuoteDraft.GST_NOT_SET,
            QuoteGst.note(enabled = true, percent = null, rates = emptyList())
        )
        assertEquals(
            QuoteGst.SET_BY_HAND,
            QuoteGst.note(enabled = true, percent = 18.0, rates = emptyList())
        )
    }

    @Test
    fun `a product added later that disagrees is a note, never an overwrite`() {
        val note = QuoteGst.note(enabled = true, percent = 18.0, rates = listOf(18.0, 12.0))
        assertEquals(QuoteGst.ratedAt(12.0), note)
        // It names the figure, so the person can decide rather than guess.
        assert(note.contains("12%"))
    }

    // --- what the rates are read off -------------------------------------------

    private fun draftWith(vararg keys: String): QuoteDraft {
        var draft = QuoteDraft(id = "qd_1")
        keys.forEachIndexed { index, key ->
            draft = draft.copy(
                lines = draft.lines + DraftLine(id = "ln_$index", key = key, title = key, rate = 1.0)
            )
        }
        return draft
    }

    private val rates = mapOf("gate|A" to 18.0, "gate|B" to 12.0, "gate|C" to 18.0)

    @Test
    fun `hand-typed lines carry no rate to read, and are not counted`() {
        val draft = draftWith("gate|A").addManual("ln_m", "Site visit", rate = 2000.0)
        assertEquals(listOf(18.0), draft.gstRates { rates[it] })
        assertEquals(18.0, draft.gstSuggestion { rates[it] }!!, 0.0)
    }

    @Test
    fun `two products at one rate still agree`() {
        val draft = draftWith("gate|A", "gate|C")
        assertEquals(listOf(18.0), draft.gstRates { rates[it] })
        assertEquals(18.0, draft.gstSuggestion { rates[it] }!!, 0.0)
    }

    @Test
    fun `two products at different rates suggest nothing at all`() {
        val draft = draftWith("gate|A", "gate|B")
        assertEquals(listOf(18.0, 12.0), draft.gstRates { rates[it] })
        assertNull("a quotation carries one rate, so there is nothing to choose",
            draft.gstSuggestion { rates[it] })
    }

    @Test
    fun `an unresolved rate charges nothing and blocks finalising`() {
        val draft = QuoteDraft(id = "qd_1", partyId = "c_1")
            .addManual("ln_1", "Site visit", rate = 1000.0)

        assertNull(draft.gstPercent)
        assertEquals(QuoteDraft.GST_NOT_SET, draft.refusal(QuoteMath.NO_CAP))
        // And it does not quietly charge 0% in the meantime.
        assertEquals(0.0, draft.totals().gst, 0.0)
        assertEquals(1000.0, draft.totals().total, 0.0)
    }

    @Test
    fun `switched off, a quotation finalises with no GST at all`() {
        // Named, because from N5.9a a quotation needs a party NAME to issue;
        // a saved customer's id alone no longer satisfies that gate.
        val draft = QuoteDraft(
            id = "qd_1",
            partyId = "c_1",
            party = QuotationPartySnapshot(name = "Sunrise Constructions"),
            gstEnabled = false
        ).addManual("ln_1", "Site visit", rate = 1000.0)

        assertNull(draft.refusal(QuoteMath.NO_CAP))
        assertEquals(0.0, draft.totals().gst, 0.0)
    }

    @Test
    fun `GST falls on transport as well, which is V8C4's behaviour`() {
        val draft = QuoteDraft(id = "qd_1", partyId = "c_1", gstPercent = 18.0, transport = 2500.0)
            .addManual("ln_1", "Motor", rate = 10_000.0)

        val totals = draft.totals()
        assertEquals(12_500.0, totals.subtotal, 0.0)
        assertEquals(2250.0, totals.gst, 0.0)
        assertEquals(14_750.0, totals.total, 0.0)
    }
}
