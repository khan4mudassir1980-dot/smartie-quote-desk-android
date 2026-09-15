package `in`.smartie.quotedesk.data.mapping

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Money and quantity formatting.
 *
 * The beta used `Double.toInt()` for display, which truncates and overflows
 * (audit C12). Everything here goes through [BigDecimal] and uses Indian
 * digit grouping, as the PWA's `toLocaleString("en-IN")` does.
 */
object Money {

    const val RUPEE = "₹"

    fun round(value: Double, decimals: Int = 2): BigDecimal =
        BigDecimal(value.toString()).setScale(decimals, RoundingMode.HALF_UP)

    /** `123456.5` becomes `1,23,456.50`. */
    fun formatAmount(value: Double, decimals: Int = 2): String =
        groupIndian(round(value, decimals).abs().toPlainString(), value < 0)

    fun formatRupees(value: Double, decimals: Int = 2): String =
        RUPEE + formatAmount(value, decimals)

    /** Quantities keep up to three decimals (metres) without trailing zeros. */
    fun formatQuantity(value: Double): String {
        val rounded = BigDecimal(value.toString())
            .setScale(3, RoundingMode.HALF_UP)
            .stripTrailingZeros()
        val plain = if (rounded.scale() < 0) rounded.setScale(0).toPlainString() else rounded.toPlainString()
        return groupIndian(plain.removePrefix("-"), value < 0)
    }

    /** Signed delta for stock movement rows: `+3`, `-2`. */
    fun formatDelta(value: Double): String {
        val body = formatQuantity(kotlin.math.abs(value))
        return when {
            value > 0 -> "+$body"
            value < 0 -> "-$body"
            else -> body
        }
    }

    private fun groupIndian(plain: String, negative: Boolean): String {
        val dot = plain.indexOf('.')
        val whole = if (dot >= 0) plain.substring(0, dot) else plain
        val fraction = if (dot >= 0) plain.substring(dot) else ""
        val grouped = if (whole.length <= 3) whole else {
            val head = whole.dropLast(3)
            val tail = whole.takeLast(3)
            val pairs = StringBuilder()
            var index = head.length
            while (index > 0) {
                val start = maxOf(0, index - 2)
                if (pairs.isNotEmpty()) pairs.insert(0, ',')
                pairs.insert(0, head.substring(start, index))
                index = start
            }
            "$pairs,$tail"
        }
        return (if (negative) "-" else "") + grouped + fraction
    }
}
