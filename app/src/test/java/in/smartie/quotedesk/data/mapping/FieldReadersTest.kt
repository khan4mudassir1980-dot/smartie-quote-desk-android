package `in`.smartie.quotedesk.data.mapping

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class FieldReadersTest {

    @Test
    fun `numeric strings parse instead of becoming zero`() {
        assertEquals(5.0, "5".asDoubleOrNull()!!, 0.0)
        assertEquals(1250.5, "1,250.50".asDoubleOrNull()!!, 0.0)
        assertEquals(1250.5, "₹ 1,250.50".asDoubleOrNull()!!, 0.0)
        assertEquals(-2.0, "-2".asDoubleOrNull()!!, 0.0)
    }

    @Test
    fun `numbers keep their value whatever the wire type`() {
        assertEquals(7.0, 7.asDoubleOrNull()!!, 0.0)
        assertEquals(7.0, 7L.asDoubleOrNull()!!, 0.0)
        assertEquals(7.5, 7.5f.asDoubleOrNull()!!, 0.0001)
    }

    @Test
    fun `a missing or unset price stays null and never becomes zero`() {
        assertNull(null.asDoubleOrNull())
        assertNull("".asDoubleOrNull())
        assertNull("   ".asDoubleOrNull())
        assertNull(UNSET_MARKER.asDoubleOrNull())
        assertNull("not a number".asDoubleOrNull())
        // Only an explicit default turns a missing value into a number.
        assertEquals(0.0, null.asDouble(), 0.0)
    }

    @Test
    fun `zero and one are read as booleans`() {
        assertTrue(1.asBoolOrNull()!!)
        assertTrue("1".asBoolOrNull()!!)
        assertTrue("true".asBoolOrNull()!!)
        assertTrue("Yes".asBoolOrNull()!!)
        assertTrue(true.asBoolOrNull()!!)
        assertFalse(0.asBoolOrNull()!!)
        assertFalse("0".asBoolOrNull()!!)
        assertFalse("false".asBoolOrNull()!!)
        assertNull("maybe".asBoolOrNull())
        assertNull(null.asBoolOrNull())
    }

    @Test
    fun `timestamps read from Long, Double, String and Date`() {
        assertEquals(1712000000000L, 1712000000000L.asMillisOrNull()!!)
        assertEquals(1712000000000L, 1.712e12.asMillisOrNull()!!)
        assertEquals(1712000000000L, "1712000000000".asMillisOrNull()!!)
        assertEquals(1712000000000L, Date(1712000000000L).asMillisOrNull()!!)
        assertNull(0L.asMillisOrNull())
        assertNull("".asMillisOrNull())
    }

    @Test
    fun `strings come back trimmed and blanks are treated as missing`() {
        assertEquals("SIE1000", "  SIE1000 ".asStringOrNull())
        assertNull("".asStringOrNull())
        assertNull(UNSET_MARKER.asStringOrNull())
        assertEquals("18", 18L.asStringOrNull())
        assertEquals("18.5", 18.5.asStringOrNull())
    }

    @Test
    fun `first returns the earliest present field name`() {
        val doc = DocData("x", mapOf("qty" to 4L, "quantity" to 9L, "blank" to ""))
        assertEquals(4.0, doc.double("qty", "quantity"), 0.0)
        assertEquals(9.0, doc.double("blank", "quantity"), 0.0)
        assertEquals(0.0, doc.double("missing"), 0.0)
    }
}
