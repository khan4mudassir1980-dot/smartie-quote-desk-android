package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.mapping.Money
import java.math.BigDecimal
import java.math.RoundingMode

/** Millimetres or feet, as the opening was measured. */
enum class DimensionUnit(val wireValue: String, val label: String) {
    MM("mm", "mm"),
    FT("ft", "ft");

    companion object {
        /**
         * Millimetres by default, because that is what a site measurement
         * arrives in. Anything unrecognised reads as millimetres rather than
         * throwing, the same shape as `RateTierV2.from` and `UrgencyV2.from`.
         */
        fun from(value: String?): DimensionUnit =
            entries.firstOrNull { it.wireValue.equals(value?.trim(), ignoreCase = true) } ?: MM
    }
}

/**
 * One opening, priced by the square foot rather than by the piece.
 *
 * New in N5: V8C4 has no area pricing at all. A shutter quoted at ₹450 a
 * square foot is not two of anything, so `quantity × rate` is the wrong
 * question — what is charged is the opening's area, rounded the way the trade
 * rounds it, times however many openings there are.
 */
data class AreaLine(
    val width: Double,
    val height: Double,
    val unit: DimensionUnit = DimensionUnit.MM,
    /** How many openings of this size. The stored line calls it `nos`. */
    val count: Double = 1.0,
    /** The product's minimum chargeable area, or null when it sets none. */
    val minimumSqft: Double? = null
)

/**
 * Turning an opening into a number of square feet somebody will pay for.
 *
 * **Two roundings, in this order, and the order is the whole of it.** The
 * measured area goes *up* to the next half square foot; only then does a
 * product's minimum apply. Doing it the other way round is invisible until the
 * minimum is not itself a multiple of a half — a minimum of 10.3 sq ft charges
 * 10.3 this way and 10.5 the other way — and then it is somebody's money.
 *
 * Pure, so every figure in the N5 plan is a unit test rather than an assertion
 * about a screen.
 */
object QuoteArea {

    /** The international foot, exactly. */
    const val MM_PER_FOOT: Double = 304.8

    /** Chargeable area steps in half a square foot, never finer. */
    private val STEP: BigDecimal = BigDecimal("0.5")

    /**
     * Millimetre arithmetic lands a hair either side of a whole number, and a
     * bare `ceil` turns 80.0000000001 into 80.5 — half a square foot of
     * somebody else's money, on a measurement that was exact. Six decimals is
     * far finer than any site measurement and far coarser than the noise.
     *
     * `BigDecimal(value.toString())` for the same reason `Money` uses it: the
     * string constructor gives decimal 0.1, not the binary approximation.
     */
    private const val AREA_SCALE: Int = 6

    /** The measured area, before anything is rounded. */
    fun squareFeet(line: AreaLine): Double = when (line.unit) {
        DimensionUnit.FT -> line.width * line.height
        DimensionUnit.MM -> (line.width * line.height) / (MM_PER_FOOT * MM_PER_FOOT)
    }

    /** Up to the next half square foot. An exact multiple is left alone. */
    fun roundUpToHalf(value: Double): Double =
        BigDecimal(value.toString())
            .setScale(AREA_SCALE, RoundingMode.HALF_UP)
            .divide(STEP, 0, RoundingMode.CEILING)
            .multiply(STEP)
            .toDouble()

    /** What one opening is charged at: rounded up, **then** lifted to the minimum. */
    fun chargeableSqft(line: AreaLine): Double {
        val rounded = roundUpToHalf(squareFeet(line))
        val minimum = line.minimumSqft ?: return rounded
        return maxOf(rounded, minimum)
    }

    /**
     * Every opening on the line together — and the figure stored as the line's
     * `qty`.
     *
     * Storing the total area rather than the door count is what keeps a
     * natively built line readable in the PWA: V8C4 prints `qty` against
     * `rate` and falls back to `qty × rate` when an amount is missing, so the
     * two must agree. The door count travels separately, as `nos`.
     */
    fun totalSqft(line: AreaLine): Double = chargeableSqft(line) * line.count

    /** What the line comes to, in whole rupees. */
    fun amount(line: AreaLine, rate: Double): Double =
        QuoteMath.rupees(totalSqft(line) * rate)

    /**
     * `3000 × 3500 mm = 113.5 sq ft × ₹450 × 2 nos`.
     *
     * Written into the line's `s` (spec) field as well as shown on the card,
     * because V8C4 already prints that field — so the PWA shows the working
     * even though it knows nothing about area.
     *
     * The measurements are **not** digit-grouped: `Money.formatQuantity` would
     * render a 3000 mm opening as `3,000`, which reads as a price rather than
     * a width. The area keeps its grouping, because a big job genuinely runs
     * to thousands of square feet.
     */
    fun describe(line: AreaLine, rate: Double): String = buildString {
        append(plain(line.width))
        append(" × ")
        append(plain(line.height))
        append(' ')
        append(line.unit.label)
        append(" = ")
        append(Money.formatQuantity(chargeableSqft(line)))
        append(" sq ft × ")
        append(Money.formatRupees(rate, decimals = 0))
        append(" × ")
        append(plain(line.count))
        append(" nos")
    }

    /** Why this opening cannot be priced, or null when it can. */
    fun refusal(line: AreaLine): String? = when {
        !line.width.isFinite() || !line.height.isFinite() -> NOT_A_MEASUREMENT
        line.width <= 0.0 || line.height <= 0.0 -> NOT_A_MEASUREMENT
        !line.count.isFinite() || line.count <= 0.0 -> NO_OPENINGS
        (line.minimumSqft ?: 0.0) < 0.0 -> NEGATIVE_MINIMUM
        else -> null
    }

    /** A number with no trailing zeros and no digit grouping. */
    private fun plain(value: Double): String {
        val rounded = BigDecimal(value.toString())
            .setScale(3, RoundingMode.HALF_UP)
            .stripTrailingZeros()
        // `stripTrailingZeros` turns 3000 into 3E+3; a negative scale is that.
        return if (rounded.scale() < 0) rounded.setScale(0).toPlainString()
        else rounded.toPlainString()
    }

    const val NOT_A_MEASUREMENT = "Enter a width and a height greater than zero"
    const val NO_OPENINGS = "Enter how many openings this size"
    const val NEGATIVE_MINIMUM = "A minimum chargeable area cannot be negative"
}
