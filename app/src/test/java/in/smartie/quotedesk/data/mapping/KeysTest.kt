package `in`.smartie.quotedesk.data.mapping

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeysTest {

    @Test
    fun `a slash in a model never reaches a document path`() {
        assertEquals("hwWheel|SIEBAL58H_V", Keys.sanitiseDocId("hwWheel|SIEBAL58H/V"))
        assertEquals("_", Keys.sanitiseDocId(""))
        assertEquals("_", Keys.sanitiseDocId("."))
        assertEquals("__", Keys.sanitiseDocId(".."))
    }

    @Test
    fun `the canonical product id is one document per product`() {
        assertEquals("gateMotors__SIE1000", Keys.productDocId("gateMotors", "SIE1000"))
        assertEquals("hwWheel__SIEBAL58H_V", Keys.productDocId("hwWheel", "SIEBAL58H/V"))
        assertEquals("gateMotors|SIE1000", Keys.productKey("gateMotors", "SIE1000"))
    }

    @Test
    fun `both legacy id schemes split back into group and model`() {
        assertEquals("gateMotors" to "SIE1000", Keys.splitProductKey("gateMotors|SIE1000"))
        assertEquals("gateMotors" to "SIE1000", Keys.splitProductKey("gateMotors__SIE1000"))
        assertNull(Keys.splitProductKey("nothing"))
        assertTrue(Keys.isLegacyProductDocId("gateMotors|SIE1000"))
        assertFalse(Keys.isLegacyProductDocId("gateMotors__SIE1000"))
    }

    @Test
    fun `emails are folded before they are compared`() {
        assertEquals("asha@example.invalid", Keys.normaliseEmail(" ASHA@Example.Invalid "))
        assertEquals("", Keys.normaliseEmail(null))
    }

    @Test
    fun `generated ids carry the PWA prefix and are unique`() {
        val first = Keys.generateId("ta_")
        val second = Keys.generateId("ta_")
        assertTrue(first.startsWith("ta_"))
        assertFalse(first == second)
    }
}
