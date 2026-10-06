package `in`.smartie.quotedesk.domain

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The grand total in words, for the PDF's AMOUNT IN WORDS box — V8C4's
 * wording, in the **Indian system**: crore, lakh, thousand, hundred; "and"
 * after hundred; the tens hyphenated ("twenty-one"); rounded to the rupee;
 * the first letter capitalised; ending "rupees only" (V8C4's output facts,
 * recorded 2026-10-06). So `123456` is "One lakh twenty-three thousand four
 * hundred and fifty-six rupees only", and `0` is "Zero rupees only".
 *
 * **One thing V8C4 gets wrong is not copied:** it breaks at 1000 crore or
 * more. Here the crore count is itself worded in the Indian system, so
 * 1000 crore is "One thousand crore rupees only" and nothing has an upper
 * bound short of `Long`.
 *
 * "One rupees only" is V8C4's, kept: the phrase always ends "rupees only".
 */
object AmountInWords {

    private val ONES = arrayOf(
        "", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
        "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
        "seventeen", "eighteen", "nineteen"
    )
    private val TENS = arrayOf(
        "", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"
    )

    private const val CRORE = 10_000_000L
    private const val LAKH = 100_000L
    private const val THOUSAND = 1_000L
    private const val HUNDRED = 100L

    /** [amount], rounded half up to the rupee, in words. */
    fun rupees(amount: Double): String {
        val whole = BigDecimal(amount.toString()).setScale(0, RoundingMode.HALF_UP).toLong()
        val words = when {
            whole == 0L -> "zero"
            whole < 0L -> "minus " + words(-whole)
            else -> words(whole)
        }
        return "$words rupees only".replaceFirstChar { it.uppercaseChar() }
    }

    /** [n] in words, lower case, for `n >= 1`. */
    internal fun words(n: Long): String {
        require(n >= 1) { "words($n)" }
        val parts = mutableListOf<String>()
        // The crore count is worded the same way, so 1000 crore and beyond
        // read "one thousand crore" rather than breaking.
        val crore = n / CRORE
        var rest = n % CRORE
        if (crore > 0) parts += words(crore) + " crore"
        val lakh = rest / LAKH
        rest %= LAKH
        if (lakh > 0) parts += belowHundred(lakh.toInt()) + " lakh"
        val thousand = rest / THOUSAND
        rest %= THOUSAND
        if (thousand > 0) parts += belowHundred(thousand.toInt()) + " thousand"
        val hundred = rest / HUNDRED
        rest %= HUNDRED
        if (hundred > 0) parts += ONES[hundred.toInt()] + " hundred"
        if (rest > 0) {
            // "and" after hundred, and only there: 101 is "one hundred and
            // one", 1001 is "one thousand one".
            parts += (if (hundred > 0) "and " else "") + belowHundred(rest.toInt())
        }
        return parts.joinToString(" ")
    }

    private fun belowHundred(n: Int): String = when {
        n < 20 -> ONES[n]
        n % 10 == 0 -> TENS[n / 10]
        else -> TENS[n / 10] + "-" + ONES[n % 10]
    }
}
