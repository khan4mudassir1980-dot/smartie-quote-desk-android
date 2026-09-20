package `in`.smartie.quotedesk.ui.theme

import androidx.compose.ui.graphics.Color
import `in`.smartie.quotedesk.data.model.UrgencyV2
import `in`.smartie.quotedesk.ui.components.urgencyColour
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The two colour decisions the first staging pass sent back: the urgency
 * colours, which were there but invisible, and the header rule, which was a
 * tricolour.
 *
 * A rendered colour is not in the Compose semantics tree, so the mapping is a
 * plain function and this is a plain unit test.
 */
class AppearanceTest {

    @Test
    fun `very urgent is red`() {
        assertEquals(Color(0xFFB91C1C), urgencyColour(UrgencyV2.CRITICAL))
    }

    @Test
    fun `can wait one to two days is yellow`() {
        assertEquals(Color(0xFFEAB308), urgencyColour(UrgencyV2.URGENT))
    }

    @Test
    fun `needed but not now is green`() {
        assertEquals(Color(0xFF15803D), urgencyColour(UrgencyV2.NORMAL))
    }

    @Test
    fun `the three urgencies are three different colours`() {
        assertEquals(3, UrgencyV2.entries.map { urgencyColour(it) }.toSet().size)
    }

    @Test
    fun `the urgency wording is the Owner's, and the wire values are the PWA's`() {
        assertEquals("Very urgent", UrgencyV2.CRITICAL.label)
        assertEquals("Can wait 1-2 days", UrgencyV2.URGENT.label)
        assertEquals("Needed, but not now", UrgencyV2.NORMAL.label)
        // The labels are display text; these are what Firestore holds.
        assertEquals(
            listOf("critical", "urgent", "normal"),
            UrgencyV2.entries.map { it.wireValue }
        )
    }

    @Test
    fun `the header rule is one solid purple`() {
        assertEquals(SmartieColors.Purple, SmartieColors.HeaderRule)
        assertEquals(3, SmartieDimens().headerRuleHeight.value.toInt())
    }

    @Test
    fun `no tricolour segment is left anywhere in the rule`() {
        // Saffron, white and green: the three the strip used to draw.
        assertNotEquals(Color(0xFFFF9933), SmartieColors.HeaderRule)
        assertNotEquals(Color(0xFFFFFFFF), SmartieColors.HeaderRule)
        assertNotEquals(Color(0xFF138808), SmartieColors.HeaderRule)
    }
}
