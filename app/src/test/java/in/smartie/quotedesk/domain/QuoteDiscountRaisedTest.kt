package `in`.smartie.quotedesk.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "The discount went up" — the Owner's rule for the cap on an edit
 * (amendment A, 2026-09-28): the cap stops an edit only then.
 *
 * **The same figures as the emulator tests (i)–(v) in `quotation.test.js`**,
 * which pin `qnDiscountRaised()` in the rules. The two must agree to the
 * rupee: the app refusing what the rule would pass is a Manager told no for
 * nothing; the app passing what the rule refuses is a save that fails
 * after the person pressed it.
 *
 * Before is the **stored** quotation's discount and base; after, the edit's.
 */
class QuoteDiscountRaisedTest {

    @Test
    fun `(i) nothing about the discount changed - not raised`() {
        assertFalse(QuoteDiscount.raised(4_440.0, 44_400.0, 4_440.0, 44_400.0))
    }

    @Test
    fun `(ii) the discount raised - raised`() {
        assertTrue(QuoteDiscount.raised(4_440.0, 44_400.0, 5_328.0, 44_400.0))
    }

    @Test
    fun `(iii) lines added under an unchanged percentage - the amount rose, so raised`() {
        // The rate is the same 10%; only the amount clause catches it.
        assertTrue(QuoteDiscount.raised(4_440.0, 44_400.0, 6_660.0, 66_600.0))
    }

    @Test
    fun `(iv) a flat discount kept while lines are removed - the rate rose, so raised`() {
        // The amount is the same 2,000; only the rate clause catches it.
        assertTrue(QuoteDiscount.raised(2_000.0, 44_400.0, 2_000.0, 22_200.0))
    }

    @Test
    fun `(v) lines removed under an unchanged percentage - not raised`() {
        assertFalse(QuoteDiscount.raised(4_440.0, 44_400.0, 2_220.0, 22_200.0))
    }

    @Test
    fun `(v) with a base that rounds - not raised, only because of the rupee margin`() {
        // 10% of 44,410 is 4,441; of 22,205 it is 2,220.5, stored as 2,221.
        // 2,221 x 44,410 = 98,634,610 against 4,441 x 22,205 + 44,410 =
        // 98,656,815. Without the margin, 98,612,405 would call it a rise.
        assertFalse(QuoteDiscount.raised(4_441.0, 44_410.0, 2_221.0, 22_205.0))
    }

    @Test
    fun `a discount added where there was none - raised`() {
        assertTrue(QuoteDiscount.raised(null, null, 2_220.0, 44_400.0))
        assertTrue(QuoteDiscount.raised(0.0, 44_400.0, 2_220.0, 44_400.0))
    }

    @Test
    fun `a discount taken off - not raised`() {
        assertFalse(QuoteDiscount.raised(4_440.0, 44_400.0, 0.0, 44_400.0))
    }

    @Test
    fun `one rupee more is the margin, two is a rise`() {
        assertFalse(QuoteDiscount.raised(4_440.0, 44_400.0, 4_441.0, 44_400.0))
        assertTrue(QuoteDiscount.raised(4_440.0, 44_400.0, 4_442.0, 44_400.0))
    }

    @Test
    fun `a stored base that cannot be read counts as raised - it fails closed`() {
        assertTrue(QuoteDiscount.raised(4_440.0, null, 2_220.0, 22_200.0))
    }
}
