package `in`.smartie.quotedesk.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What a quotation comes to, and the order it gets there in.
 *
 * **The order is the thing.** Products and installation are added, the
 * discount comes off *that*, transport goes on afterwards, and GST falls on
 * the whole lot. So a discount never touches transport, and GST does — which
 * is V8C4's behaviour, kept deliberately rather than tidied up.
 *
 * **Every stored figure is whole rupees**, rounded as it is computed, and each
 * later figure is built from the already-rounded earlier ones. That keeps a
 * printed page adding up, keeps the PWA and the native app showing the same
 * numbers, and makes the bound the security rules check — `discountBase` can
 * never exceed `subtotal + discount` — exact integer arithmetic rather than
 * something that drifts by a rupee.
 *
 * Titles: stored `staff` is displayed **Manager**, and a Manager is the only
 * role the discount cap applies to.
 */
class QuoteTotalsTest {

    // --- installation, in all four modes -----------------------------------------

    @Test
    fun `a fixed installation charge is the rate, and ignores everything else`() {
        val fixed = Installation(InstallationMode.FIXED, rate = 1200.0, basis = 999.0)
        assertEquals(1200.0, fixed.amount, 0.0)
        assertEquals(0.0, Installation.defaultBasis(InstallationMode.FIXED, 4.0, 200.0, 50_000.0), 0.0)
    }

    @Test
    fun `per door multiplies by the number of doors`() {
        val perDoor = Installation(InstallationMode.PER_DOOR, rate = 1500.0, basis = 2.0)
        assertEquals(3000.0, perDoor.amount, 0.0)
        assertEquals(2.0, Installation.defaultBasis(InstallationMode.PER_DOOR, 2.0, 227.0, 1000.0), 0.0)
    }

    @Test
    fun `per square foot multiplies by the chargeable area, not the measured one`() {
        val perSqft = Installation(InstallationMode.PER_SQFT, rate = 60.0, basis = 163.5)
        assertEquals(9810.0, perSqft.amount, 0.0)
        assertEquals(
            163.5,
            Installation.defaultBasis(InstallationMode.PER_SQFT, 3.0, 163.5, 1000.0),
            0.0
        )
    }

    @Test
    fun `a percentage is taken on the products figure before any discount`() {
        val percent = Installation(InstallationMode.PERCENT, rate = 8.0, basis = 19_400.0)
        assertEquals(1552.0, percent.amount, 0.0)
        assertEquals(
            19_400.0,
            Installation.defaultBasis(InstallationMode.PERCENT, 0.0, 0.0, 19_400.0),
            0.0
        )
    }

    @Test
    fun `the basis is stored, so a quotation reopened later shows what it was struck on`() {
        // Defaulted from the lines and then editable, which is why it travels
        // with the quotation rather than being recomputed from today's lines.
        val edited = Installation(InstallationMode.PER_DOOR, rate = 1500.0, basis = 5.0)
        assertEquals(7500.0, edited.amount, 0.0)
    }

    // --- the order things are added in --------------------------------------------

    @Test
    fun `a discount comes off products and installation, and never off transport`() {
        val totals = QuoteMath.totals(
            QuoteCharges(
                products = 10_000.0,
                installation = Installation(InstallationMode.FIXED, rate = 2000.0),
                discount = Discount(DiscountKind.PERCENT, 10.0),
                transport = 1000.0
            )
        )

        assertEquals(12_000.0, totals.discountBase, 0.0)
        // Ten per cent of 12,000 — not of 13,000.
        assertEquals(1200.0, totals.discount, 0.0)
        assertEquals(1000.0, totals.transport, 0.0)
        assertEquals(11_800.0, totals.subtotal, 0.0)
    }

    @Test
    fun `GST falls on transport too, which is what V8C4 does`() {
        val totals = QuoteMath.totals(
            QuoteCharges(
                products = 10_000.0,
                transport = 1000.0,
                gstEnabled = true,
                gstPercent = 18.0
            )
        )

        // 18% of 11,000, not of 10,000.
        assertEquals(11_000.0, totals.subtotal, 0.0)
        assertEquals(1980.0, totals.gst, 0.0)
        assertEquals(12_980.0, totals.total, 0.0)
    }

    @Test
    fun `GST switched off charges nothing and claims no rate`() {
        val totals = QuoteMath.totals(
            QuoteCharges(products = 10_000.0, gstEnabled = false, gstPercent = 18.0)
        )
        assertEquals(0.0, totals.gst, 0.0)
        assertEquals(0.0, totals.gstPercent, 0.0)
        assertEquals(10_000.0, totals.total, 0.0)
    }

    @Test
    fun `every stored figure is whole rupees`() {
        val totals = QuoteMath.totals(
            QuoteCharges(
                products = 10_000.49,
                installation = Installation(InstallationMode.PERCENT, rate = 7.5, basis = 10_000.0),
                discount = Discount(DiscountKind.PERCENT, 3.0),
                transport = 99.5,
                gstEnabled = true,
                gstPercent = 18.0
            )
        )

        listOf(
            totals.products, totals.installation, totals.discountBase,
            totals.discount, totals.transport, totals.subtotal, totals.gst, totals.total
        ).forEach { assertEquals("$it is not a whole rupee", it, Math.floor(it), 0.0) }
    }

    @Test
    fun `discountBase never exceeds subtotal plus discount, which is the rules bound`() {
        // The security rules cannot sum an array, so they check a Manager's cap
        // against the stored `discBase` — and bound that against the figures
        // beside it. Transport is never negative, so the bound holds, and
        // because every figure is already whole rupees it holds exactly.
        listOf(0.0, 1.0, 2500.0, 99_999.0).forEach { transport ->
            val totals = QuoteMath.totals(
                QuoteCharges(
                    products = 44_400.33,
                    installation = Installation(InstallationMode.PER_DOOR, rate = 1500.0, basis = 2.0),
                    discount = Discount(DiscountKind.PERCENT, 10.0),
                    transport = transport
                )
            )
            assertEquals(
                "the bound must be exact, not within a rupee",
                totals.discountBase,
                totals.subtotal - totals.transport + totals.discount,
                0.0
            )
        }
    }

    // --- the Manager's cap ---------------------------------------------------------

    @Test
    fun `a Manager over the cap is refused, and told the figure they may have`() {
        val base = 94_830.0
        val refusal = QuoteMath.discountRefusal(
            Discount(DiscountKind.PERCENT, 15.0), base, capPercent = 10.0
        )
        assertEquals("The most you can discount is 10% (₹9,483)", refusal)
    }

    @Test
    fun `and at the cap exactly, they are not`() {
        val base = 94_830.0
        assertNull(QuoteMath.discountRefusal(Discount(DiscountKind.PERCENT, 10.0), base, 10.0))
        assertNull(QuoteMath.discountRefusal(Discount(DiscountKind.RUPEES, 9483.0), base, 10.0))
        // A rupee over is over.
        assertEquals(
            QuoteMath.overTheCap(10.0, 9483.0),
            QuoteMath.discountRefusal(Discount(DiscountKind.RUPEES, 9484.0), base, 10.0)
        )
    }

    @Test
    fun `an Owner and an Administrator are not capped`() {
        val base = 94_830.0
        assertNull(QuoteMath.discountRefusal(Discount(DiscountKind.PERCENT, 100.0), base, QuoteMath.NO_CAP))
        assertNull(QuoteMath.discountRefusal(Discount(DiscountKind.RUPEES, base), base, QuoteMath.NO_CAP))
    }

    @Test
    fun `a discount cannot be negative, over a hundred per cent, or bigger than the job`() {
        val base = 10_000.0
        assertEquals(
            QuoteMath.NEGATIVE_DISCOUNT,
            QuoteMath.discountRefusal(Discount(DiscountKind.RUPEES, -1.0), base, QuoteMath.NO_CAP)
        )
        assertEquals(
            QuoteMath.OVER_A_HUNDRED,
            QuoteMath.discountRefusal(Discount(DiscountKind.PERCENT, 101.0), base, QuoteMath.NO_CAP)
        )
        assertEquals(
            QuoteMath.moreThanTheQuotation(base),
            QuoteMath.discountRefusal(Discount(DiscountKind.RUPEES, 10_001.0), base, QuoteMath.NO_CAP)
        )
    }

    // --- the four worked examples, end to end ---------------------------------------

    @Test
    fun `A - a percentage discount, installation per door, transport and GST`() {
        val motors = 2 * 22_200.0
        val shutters = QuoteArea.amount(
            AreaLine(3000.0, 3500.0, DimensionUnit.MM, count = 2.0),
            rate = 450.0
        )
        assertEquals(102_150.0, shutters, 0.0)

        val totals = QuoteMath.totals(
            QuoteCharges(
                products = motors + shutters,
                installation = Installation(InstallationMode.PER_DOOR, rate = 1500.0, basis = 2.0),
                discount = Discount(DiscountKind.PERCENT, 10.0),
                transport = 2500.0,
                gstEnabled = true,
                gstPercent = 18.0
            )
        )

        assertEquals(146_550.0, totals.products, 0.0)
        assertEquals(3000.0, totals.installation, 0.0)
        assertEquals(149_550.0, totals.discountBase, 0.0)
        assertEquals(14_955.0, totals.discount, 0.0)
        assertEquals(2500.0, totals.transport, 0.0)
        assertEquals(137_095.0, totals.subtotal, 0.0)
        assertEquals(24_677.0, totals.gst, 0.0)
        assertEquals(161_772.0, totals.total, 0.0)
    }

    @Test
    fun `B - a rupee discount, installation as a percentage of products`() {
        val totals = QuoteMath.totals(
            QuoteCharges(
                products = 120 * 145.0 + 2000.0,
                installation = Installation(InstallationMode.PERCENT, rate = 8.0, basis = 19_400.0),
                discount = Discount(DiscountKind.RUPEES, 2000.0),
                transport = 800.0,
                gstEnabled = true,
                gstPercent = 18.0
            )
        )

        assertEquals(19_400.0, totals.products, 0.0)
        assertEquals(1552.0, totals.installation, 0.0)
        assertEquals(20_952.0, totals.discountBase, 0.0)
        assertEquals(2000.0, totals.discount, 0.0)
        assertEquals(19_752.0, totals.subtotal, 0.0)
        assertEquals(3555.0, totals.gst, 0.0)
        assertEquals(23_307.0, totals.total, 0.0)
    }

    @Test
    fun `C - installation per square foot, and a Manager held to the cap`() {
        val shutters = AreaLine(2400.0, 2100.0, DimensionUnit.MM, count = 3.0)
        assertEquals(54.5, QuoteArea.chargeableSqft(shutters), 0.0)
        assertEquals(163.5, QuoteArea.totalSqft(shutters), 0.0)

        val products = QuoteArea.amount(shutters, rate = 520.0)
        assertEquals(85_020.0, products, 0.0)

        val installation = Installation(InstallationMode.PER_SQFT, rate = 60.0, basis = 163.5)
        val base = QuoteMath.rupees(products + installation.amount)
        assertEquals(94_830.0, base, 0.0)

        // The Manager types 15% against a 10% cap and is refused by name.
        assertEquals(
            "The most you can discount is 10% (₹9,483)",
            QuoteMath.discountRefusal(Discount(DiscountKind.PERCENT, 15.0), base, capPercent = 10.0)
        )

        val totals = QuoteMath.totals(
            QuoteCharges(
                products = products,
                installation = installation,
                discount = Discount(DiscountKind.PERCENT, 10.0),
                transport = 0.0,
                gstEnabled = true,
                gstPercent = 18.0
            )
        )

        assertEquals(9810.0, totals.installation, 0.0)
        assertEquals(9483.0, totals.discount, 0.0)
        assertEquals(85_347.0, totals.subtotal, 0.0)
        assertEquals(15_362.0, totals.gst, 0.0)
        assertEquals(100_709.0, totals.total, 0.0)
    }

    @Test
    fun `D - the product minimum biting, with a fixed installation charge`() {
        val panel = AreaLine(600.0, 900.0, DimensionUnit.MM, count = 1.0, minimumSqft = 10.0)
        val products = QuoteArea.amount(panel, rate = 700.0)
        assertEquals(7000.0, products, 0.0)

        val totals = QuoteMath.totals(
            QuoteCharges(
                products = products,
                installation = Installation(InstallationMode.FIXED, rate = 1200.0),
                gstEnabled = true,
                gstPercent = 18.0
            )
        )

        assertEquals(8200.0, totals.discountBase, 0.0)
        assertEquals(0.0, totals.discount, 0.0)
        assertEquals(0.0, totals.transport, 0.0)
        assertEquals(8200.0, totals.subtotal, 0.0)
        assertEquals(1476.0, totals.gst, 0.0)
        assertEquals(9676.0, totals.total, 0.0)
    }
}
