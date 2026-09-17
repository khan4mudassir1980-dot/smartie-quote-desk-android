package `in`.smartie.quotedesk.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StockPendingCodecTest {

    @Test
    fun `a pending count survives a round trip`() {
        val pending = mapOf("gateMotors|SIE1000" to 5.0, "manualstock|shed_padlock" to -2.0)
        assertEquals(pending, StockPendingCodec.decode(StockPendingCodec.encode(pending)))
    }

    /** Real keys carry `|` and `/`; neither may break the format. */
    @Test
    fun `a key carrying a pipe or a slash round-trips`() {
        val pending = mapOf("hwWheel|SIEBAL58H/V" to 3.0, "gate|SIE2.5MSMALL" to -1.0)
        assertEquals(pending, StockPendingCodec.decode(StockPendingCodec.encode(pending)))
    }

    @Test
    fun `nothing pending encodes to nothing`() {
        assertEquals("", StockPendingCodec.encode(emptyMap()))
        assertTrue(StockPendingCodec.decode("").isEmpty())
        assertTrue(StockPendingCodec.decode(null).isEmpty())
    }

    @Test
    fun `unreadable storage decodes to nothing pending rather than throwing`() {
        assertTrue(StockPendingCodec.decode("rubbish").isEmpty())
        assertTrue(StockPendingCodec.decode("v9key1").isEmpty())
        assertTrue(StockPendingCodec.decode("v1notanumber").isEmpty())
        assertTrue(StockPendingCodec.decode("v1keynope").isEmpty())
    }

    @Test
    fun `a zero or non-finite delta is never stored`() {
        assertEquals("", StockPendingCodec.encode(mapOf("k" to 0.0)))
        assertEquals("", StockPendingCodec.encode(mapOf("k" to Double.NaN)))
        assertEquals("", StockPendingCodec.encode(mapOf("k" to Double.POSITIVE_INFINITY)))
        assertEquals("", StockPendingCodec.encode(mapOf("" to 2.0)))
    }

    @Test
    fun `changing accumulates`() {
        var pending = StockPendingCodec.change(emptyMap(), "k", 1.0)
        pending = StockPendingCodec.change(pending, "k", 1.0)
        pending = StockPendingCodec.change(pending, "k", 1.0)
        assertEquals(mapOf("k" to 3.0), pending)
    }

    /** Stepping back to zero must leave no empty draft behind. */
    @Test
    fun `a delta returning to zero is removed rather than stored as zero`() {
        val pending = StockPendingCodec.change(mapOf("k" to 1.0), "k", -1.0)
        assertTrue(pending.isEmpty())
    }

    @Test
    fun `changing by nothing, or on no key, changes nothing`() {
        assertEquals(mapOf("k" to 1.0), StockPendingCodec.change(mapOf("k" to 1.0), "k", 0.0))
        assertEquals(mapOf("k" to 1.0), StockPendingCodec.change(mapOf("k" to 1.0), "", 5.0))
    }

    @Test
    fun `one row's pending count is independent of another's`() {
        val pending = StockPendingCodec.change(StockPendingCodec.change(emptyMap(), "a", 2.0), "b", -3.0)
        assertEquals(mapOf("a" to 2.0, "b" to -3.0), pending)
    }
}
