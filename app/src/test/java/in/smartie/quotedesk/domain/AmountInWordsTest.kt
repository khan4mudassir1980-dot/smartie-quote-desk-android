package `in`.smartie.quotedesk.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The AMOUNT IN WORDS box (N5.11 commit 1): V8C4's Indian-system wording, and
 * the 1000-crore case V8C4 breaks on. The Owner's list first — 0, 1, 19, 20,
 * 21, 100, 101, 999, 1000, 1,00,000, 1,00,00,000 and 1000 crore — then the
 * boundaries between them.
 */
class AmountInWordsTest {

    private fun words(amount: Double) = AmountInWords.rupees(amount)

    @Test
    fun `the Owner's list`() {
        assertEquals("Zero rupees only", words(0.0))
        assertEquals("One rupees only", words(1.0))
        assertEquals("Nineteen rupees only", words(19.0))
        assertEquals("Twenty rupees only", words(20.0))
        assertEquals("Twenty-one rupees only", words(21.0))
        assertEquals("One hundred rupees only", words(100.0))
        assertEquals("One hundred and one rupees only", words(101.0))
        assertEquals("Nine hundred and ninety-nine rupees only", words(999.0))
        assertEquals("One thousand rupees only", words(1_000.0))
        assertEquals("One lakh rupees only", words(1_00_000.0))
        assertEquals("One crore rupees only", words(1_00_00_000.0))
        assertEquals("One thousand crore rupees only", words(1_000_00_00_000.0))
    }

    @Test
    fun `below a hundred — the teens, round tens and hyphenated tens`() {
        assertEquals("Eleven rupees only", words(11.0))
        assertEquals("Ninety rupees only", words(90.0))
        assertEquals("Ninety-nine rupees only", words(99.0))
        assertEquals("Forty-five rupees only", words(45.0))
    }

    @Test
    fun `and comes after hundred, and only there`() {
        assertEquals("One hundred and ten rupees only", words(110.0))
        assertEquals("One thousand one rupees only", words(1_001.0))
        assertEquals("One lakh one rupees only", words(1_00_001.0))
        assertEquals("Two thousand three hundred rupees only", words(2_300.0))
        assertEquals("Two thousand three hundred and four rupees only", words(2_304.0))
    }

    @Test
    fun `lakh and crore at their boundaries`() {
        assertEquals("Ninety-nine thousand nine hundred and ninety-nine rupees only", words(99_999.0))
        assertEquals(
            "Nine lakh ninety-nine thousand nine hundred and ninety-nine rupees only",
            words(9_99_999.0)
        )
        assertEquals(
            "Ninety-nine lakh ninety-nine thousand nine hundred and ninety-nine rupees only",
            words(99_99_999.0)
        )
        assertEquals(
            "One lakh twenty-three thousand four hundred and fifty-six rupees only",
            words(1_23_456.0)
        )
        assertEquals(
            "Twelve crore thirty-four lakh fifty-six thousand seven hundred and eighty-nine rupees only",
            words(12_34_56_789.0)
        )
    }

    @Test
    fun `a thousand crore and beyond is worded, not broken`() {
        // The crore count is worded in the same system.
        assertEquals("Ninety-nine crore rupees only", words(99_00_00_000.0))
        assertEquals("One hundred crore rupees only", words(100_00_00_000.0))
        assertEquals(
            "Twelve thousand three hundred and forty-five crore six lakh " +
                "seventy-eight thousand nine hundred and one rupees only",
            words(12_345_06_78_901.0)
        )
        assertEquals("One lakh crore rupees only", words(1_00_000_00_00_000.0))
    }

    @Test
    fun `rounded half up to the rupee`() {
        assertEquals("One hundred rupees only", words(99.5))
        assertEquals("Ninety-nine rupees only", words(99.49))
        assertEquals(
            "One lakh twenty-three thousand four hundred and fifty-seven rupees only",
            words(1_23_456.5)
        )
        assertEquals("Zero rupees only", words(0.4))
    }
}
