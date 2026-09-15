package `in`.smartie.quotedesk.data.mapping

import org.junit.Assert.assertEquals
import org.junit.Test

class MoneyTest {

    @Test
    fun `amounts use Indian digit grouping`() {
        assertEquals("1,23,456.50", Money.formatAmount(123456.5))
        assertEquals("999.00", Money.formatAmount(999.0))
        assertEquals("52,982.00", Money.formatAmount(52982.0))
        assertEquals("₹44,400.00", Money.formatRupees(44400.0))
    }

    @Test
    fun `large values do not overflow as they did with toInt`() {
        assertEquals("3,00,00,00,000.00", Money.formatAmount(3_000_000_000.0))
    }

    @Test
    fun `quantities keep metres and drop trailing zeros`() {
        assertEquals("12.5", Money.formatQuantity(12.5))
        assertEquals("3", Money.formatQuantity(3.0))
        assertEquals("0.125", Money.formatQuantity(0.125))
    }

    @Test
    fun `stock deltas are signed`() {
        assertEquals("+3", Money.formatDelta(3.0))
        assertEquals("-2", Money.formatDelta(-2.0))
        assertEquals("0", Money.formatDelta(0.0))
    }
}
