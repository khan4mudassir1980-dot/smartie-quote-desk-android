package `in`.smartie.quotedesk.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turning an opening into square feet somebody pays for.
 *
 * Two things here are easy to get wrong in a way nobody notices until it is
 * money. **The rounding goes up, but an exact measurement must not move** —
 * a 10 ft × 8 ft shutter is 80 sq ft, and float noise in the last bits of a
 * millimetre calculation would otherwise push it to 80.5, half a square foot
 * of somebody else's money on a measurement that was exact. And **the product
 * minimum applies after the rounding, never before**, which is invisible until
 * the minimum is not itself a multiple of a half.
 *
 * New in N5: V8C4 prices nothing by area, so none of this is a port and none
 * of it can be checked against the PWA.
 */
class QuoteAreaTest {

    private fun opening(
        width: Double,
        height: Double,
        unit: DimensionUnit = DimensionUnit.MM,
        count: Double = 1.0,
        minimumSqft: Double? = null
    ) = AreaLine(width, height, unit, count, minimumSqft)

    // --- the measurement -------------------------------------------------------

    @Test
    fun `millimetres are the default, because that is how a site is measured`() {
        assertEquals(DimensionUnit.MM, AreaLine(width = 3000.0, height = 3500.0).unit)
        assertEquals(DimensionUnit.MM, DimensionUnit.from(null))
        assertEquals(DimensionUnit.MM, DimensionUnit.from("furlongs"))
        assertEquals(DimensionUnit.FT, DimensionUnit.from("ft"))
        assertEquals(DimensionUnit.FT, DimensionUnit.from("  FT "))
    }

    @Test
    fun `a foot is 304 point 8 millimetres, and the two entries agree`() {
        val inFeet = opening(10.0, 8.0, DimensionUnit.FT)
        val inMillimetres = opening(3048.0, 2438.4, DimensionUnit.MM)

        assertEquals(80.0, QuoteArea.squareFeet(inFeet), 1e-9)
        assertEquals(80.0, QuoteArea.squareFeet(inMillimetres), 1e-9)
        assertEquals(
            QuoteArea.chargeableSqft(inFeet),
            QuoteArea.chargeableSqft(inMillimetres),
            0.0
        )
    }

    // --- rounding up, and the exact multiple that must not move ----------------

    @Test
    fun `area goes up to the next half square foot`() {
        // The Owner's own example: 3000 x 3500 mm measures 113.0211 sq ft.
        assertEquals(113.0211, QuoteArea.squareFeet(opening(3000.0, 3500.0)), 1e-4)
        assertEquals(113.5, QuoteArea.chargeableSqft(opening(3000.0, 3500.0)), 0.0)

        // 2400 x 2100 mm measures 54.2501, so it lands on 54.5 rather than 54.
        assertEquals(54.5, QuoteArea.chargeableSqft(opening(2400.0, 2100.0)), 0.0)
    }

    @Test
    fun `an exact multiple of a half is left exactly where it is`() {
        // The whole reason the rounding is done in BigDecimal. Through a
        // double, 3048 x 2438.4 / 304.8^2 lands a hair either side of 80, and
        // a bare `ceil` would sell half a square foot that was never measured.
        assertEquals(80.0, QuoteArea.chargeableSqft(opening(10.0, 8.0, DimensionUnit.FT)), 0.0)
        assertEquals(80.0, QuoteArea.chargeableSqft(opening(3048.0, 2438.4)), 0.0)

        assertEquals(80.0, QuoteArea.roundUpToHalf(80.0), 0.0)
        assertEquals(80.5, QuoteArea.roundUpToHalf(80.5), 0.0)
        assertEquals(0.5, QuoteArea.roundUpToHalf(0.5), 0.0)
        // And a hair over really does move.
        assertEquals(80.5, QuoteArea.roundUpToHalf(80.01), 0.0)
    }

    // --- the minimum, and when it applies ---------------------------------------

    @Test
    fun `the product minimum lifts a small opening`() {
        // 600 x 900 mm measures 5.8125, rounds to 6.0, and is then lifted.
        val panel = opening(600.0, 900.0, minimumSqft = 10.0)
        assertEquals(5.8125, QuoteArea.squareFeet(panel), 1e-4)
        // Rounded to 6.0 first, and only then lifted — the order matters, and
        // the test below is the one that can tell the two apart.
        assertEquals(6.0, QuoteArea.chargeableSqft(panel.copy(minimumSqft = null)), 0.0)
        assertEquals(10.0, QuoteArea.chargeableSqft(panel), 0.0)
    }

    @Test
    fun `and the minimum applies after the rounding, never before`() {
        // The case that tells the two orders apart. Measured 9.0 sq ft against
        // a minimum of 10.3: rounding first gives max(9.0, 10.3) = 10.3, and
        // the minimum is charged as it was set. Lifting first would give
        // roundUpToHalf(10.3) = 10.5, which is a fifth of a square foot nobody
        // agreed to.
        val line = opening(3.0, 3.0, DimensionUnit.FT, minimumSqft = 10.3)
        assertEquals(9.0, QuoteArea.squareFeet(line), 1e-9)
        assertEquals(10.3, QuoteArea.chargeableSqft(line), 0.0)
    }

    @Test
    fun `a minimum below the measurement changes nothing`() {
        val line = opening(3000.0, 3500.0, minimumSqft = 100.0)
        assertEquals(113.5, QuoteArea.chargeableSqft(line), 0.0)
    }

    // --- what the line stores and what it comes to -------------------------------

    @Test
    fun `the stored quantity is the total area, so the PWA prints the line right`() {
        // V8C4 knows nothing about `nos`. It prints `qty` against `rate` and
        // falls back to qty x rate when an amount is missing, so the quantity
        // must be the whole chargeable area rather than the door count.
        val line = opening(3000.0, 3500.0, count = 2.0)

        assertEquals(113.5, QuoteArea.chargeableSqft(line), 0.0)
        assertEquals(227.0, QuoteArea.totalSqft(line), 0.0)
        assertEquals(102_150.0, QuoteArea.amount(line, rate = 450.0), 0.0)
        // qty x rate really does equal the amount, which is the point.
        assertEquals(
            QuoteArea.amount(line, rate = 450.0),
            QuoteArea.totalSqft(line) * 450.0,
            0.0
        )
    }

    @Test
    fun `the amount is whole rupees, like every other stored figure`() {
        // 54.5 x 3 x 520.37 = 85 080.495, which stores as 85 080.
        val line = opening(2400.0, 2100.0, count = 3.0)
        assertEquals(85_080.0, QuoteArea.amount(line, rate = 520.37), 0.0)
    }

    @Test
    fun `the line says its own working, for the card and for the PDF`() {
        val line = opening(3000.0, 3500.0, count = 2.0)
        assertEquals(
            "3000 × 3500 mm = 113.5 sq ft × ₹450 × 2 nos",
            QuoteArea.describe(line, rate = 450.0)
        )
    }

    @Test
    fun `the measurements are not digit-grouped, because a width is not a price`() {
        // `Money.formatQuantity` would render this opening as 3,000 x 3,500.
        val described = QuoteArea.describe(opening(3000.0, 3500.0), rate = 450.0)
        assertTrue("grouped as a price: $described", described.startsWith("3000 × 3500 mm"))
    }

    @Test
    fun `feet describe themselves in feet`() {
        assertEquals(
            "10 × 8 ft = 80 sq ft × ₹1,200 × 1 nos",
            QuoteArea.describe(opening(10.0, 8.0, DimensionUnit.FT), rate = 1200.0)
        )
    }

    // --- what cannot be priced ----------------------------------------------------

    @Test
    fun `an opening with no size is refused rather than priced at zero`() {
        assertEquals(QuoteArea.NOT_A_MEASUREMENT, QuoteArea.refusal(opening(0.0, 3500.0)))
        assertEquals(QuoteArea.NOT_A_MEASUREMENT, QuoteArea.refusal(opening(3000.0, -1.0)))
        assertEquals(
            QuoteArea.NOT_A_MEASUREMENT,
            QuoteArea.refusal(opening(Double.NaN, 3500.0))
        )
        assertEquals(
            QuoteArea.NO_OPENINGS,
            QuoteArea.refusal(opening(3000.0, 3500.0, count = 0.0))
        )
        assertEquals(
            QuoteArea.NEGATIVE_MINIMUM,
            QuoteArea.refusal(opening(3000.0, 3500.0, minimumSqft = -1.0))
        )
        assertNull(QuoteArea.refusal(opening(3000.0, 3500.0, count = 2.0, minimumSqft = 10.0)))
    }
}
