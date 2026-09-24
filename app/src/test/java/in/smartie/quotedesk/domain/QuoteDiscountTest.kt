package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.model.QuotingRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The discount cap, and the two refusals that are easy to confuse.
 *
 * **A cap nobody configured and a cap of zero both permit no discount, and
 * only one of them is somebody's mistake.** `observeQuoting` emits a null
 * record for an absent document, which is not a record holding `0.0`. A
 * Manager who reads "the limit has not been set" can ask the Owner to set it;
 * one who reads "the most you can discount is 0%" would reasonably conclude
 * the Owner meant it.
 *
 * Stored `staff` is displayed **Manager**.
 */
class QuoteDiscountTest {

    private val owner = Member(uid = "u_o", name = "Mudassir", role = Role.OWNER)
    private val admin = Member(uid = "u_a", name = "Asif", role = Role.ADMIN)
    private val manager = Member(uid = "u_m", name = "Sam", role = Role.STAFF)

    @Test
    fun `an Owner and an Administrator are uncapped`() {
        assertEquals(QuoteMath.NO_CAP, QuoteDiscount.capFor(owner, null)!!, 0.0)
        assertEquals(QuoteMath.NO_CAP, QuoteDiscount.capFor(admin, null)!!, 0.0)
        // And a stored cap does not apply to them.
        assertEquals(
            QuoteMath.NO_CAP,
            QuoteDiscount.capFor(owner, QuotingRecord(managerDiscountPct = 5.0))!!,
            0.0
        )
    }

    @Test
    fun `a Manager takes the configured cap`() {
        assertEquals(
            5.0,
            QuoteDiscount.capFor(manager, QuotingRecord(managerDiscountPct = 5.0))!!,
            0.0
        )
    }

    @Test
    fun `an absent settings document is not a cap of zero`() {
        assertNull(QuoteDiscount.capFor(manager, null))
        assertEquals(
            0.0,
            QuoteDiscount.capFor(manager, QuotingRecord(managerDiscountPct = 0.0))!!,
            0.0
        )
    }

    @Test
    fun `the two refusals say different things, because they are different`() {
        val discount = Discount(DiscountKind.PERCENT, 10.0)

        assertEquals(
            QuoteDiscount.CAP_NOT_SET,
            QuoteDiscount.refusal(discount, base = 100_000.0, cap = null)
        )
        assertEquals(
            QuoteMath.overTheCap(0.0, 0.0),
            QuoteDiscount.refusal(discount, base = 100_000.0, cap = 0.0)
        )
    }

    @Test
    fun `a refusal names the figure the person may actually have`() {
        // Never a silent clamp: a quotation that went out at a discount
        // nobody chose is worse than one that would not save.
        val refusal = QuoteDiscount.refusal(
            Discount(DiscountKind.PERCENT, 10.0),
            base = 100_000.0,
            cap = 5.0
        )
        assertEquals(QuoteMath.overTheCap(5.0, 5_000.0), refusal)
        assert(refusal!!.contains("5,000"))
    }

    @Test
    fun `a discount within the cap is allowed`() {
        assertNull(
            QuoteDiscount.refusal(
                Discount(DiscountKind.PERCENT, 5.0),
                base = 100_000.0,
                cap = 5.0
            )
        )
        assertNull(
            QuoteDiscount.refusal(
                Discount(DiscountKind.RUPEES, 5_000.0),
                base = 100_000.0,
                cap = 5.0
            )
        )
    }

    @Test
    fun `an uncapped Owner is still bounded by the quotation itself`() {
        assertEquals(
            QuoteMath.moreThanTheQuotation(10_000.0),
            QuoteDiscount.refusal(
                Discount(DiscountKind.RUPEES, 20_000.0),
                base = 10_000.0,
                cap = QuoteMath.NO_CAP
            )
        )
        // Which is the non-negative invariant's first gate: a discount larger
        // than the quotation would drive the subtotal below zero, where
        // HALF_UP and JavaScript's Math.round stop agreeing.
        assertEquals(
            QuoteMath.NEGATIVE_DISCOUNT,
            QuoteDiscount.refusal(
                Discount(DiscountKind.RUPEES, -1.0),
                base = 10_000.0,
                cap = QuoteMath.NO_CAP
            )
        )
    }
}
