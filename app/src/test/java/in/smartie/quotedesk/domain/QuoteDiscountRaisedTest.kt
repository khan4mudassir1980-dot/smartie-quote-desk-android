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
        assertFalse(QuoteDiscount.raised(3_400.0, 34_000.0, 3_400.0, 34_000.0))
    }

    @Test
    fun `(ii) the discount raised - raised`() {
        assertTrue(QuoteDiscount.raised(3_400.0, 34_000.0, 4_080.0, 34_000.0))
    }

    @Test
    fun `(iii) lines added under an unchanged percentage - the amount rose, so raised`() {
        // The rate is the same 10%; only the amount clause catches it.
        assertTrue(QuoteDiscount.raised(3_400.0, 34_000.0, 5_100.0, 51_000.0))
    }

    @Test
    fun `(iv) a flat discount kept while lines are removed - the rate rose, so raised`() {
        // The amount is the same 1,500; only the rate clause catches it.
        assertTrue(QuoteDiscount.raised(1_500.0, 34_000.0, 1_500.0, 17_000.0))
    }

    @Test
    fun `(v) lines removed under an unchanged percentage - not raised`() {
        assertFalse(QuoteDiscount.raised(3_400.0, 34_000.0, 1_700.0, 17_000.0))
    }

    @Test
    fun `(v) with a base that rounds - not raised, only because of the rupee margin`() {
        // 10% of 34,010 is 3,401; of 17,005 it is 1,700.5, stored as 1,701.
        // 1,701 x 34,010 = 57,851,010 against 3,401 x 17,005 + 34,010 =
        // 57,868,015. Without the margin, 57,834,005 would call it a rise.
        assertFalse(QuoteDiscount.raised(3_401.0, 34_010.0, 1_701.0, 17_005.0))
    }

    @Test
    fun `a discount added where there was none - raised`() {
        assertTrue(QuoteDiscount.raised(null, null, 1_700.0, 34_000.0))
        assertTrue(QuoteDiscount.raised(0.0, 34_000.0, 1_700.0, 34_000.0))
    }

    @Test
    fun `a discount taken off - not raised`() {
        assertFalse(QuoteDiscount.raised(3_400.0, 34_000.0, 0.0, 34_000.0))
    }

    @Test
    fun `one rupee more is the margin, two is a rise`() {
        assertFalse(QuoteDiscount.raised(3_400.0, 34_000.0, 3_401.0, 34_000.0))
        assertTrue(QuoteDiscount.raised(3_400.0, 34_000.0, 3_402.0, 34_000.0))
    }

    @Test
    fun `a stored base that cannot be read counts as raised - it fails closed`() {
        assertTrue(QuoteDiscount.raised(3_400.0, null, 1_700.0, 17_000.0))
    }
}
