package `in`.smartie.quotedesk.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * The brand colours (the Owner, N5.12b), and every pair they are drawn in.
 *
 * WCAG AA: 4.5:1 for text, 3:1 for a line or a shape that has to be seen.
 * Plain arithmetic on the tokens — the formula `TagContrastTest` uses — so no
 * screen is needed. A pair is here because a screen draws it; the comment says
 * where.
 */
class BrandContrastTest {

    private fun channel(c: Float): Double =
        if (c <= 0.03928f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    private fun luminance(r: Float, g: Float, b: Float): Double =
        0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)

    private fun luminance(color: Color): Double = luminance(color.red, color.green, color.blue)

    private fun ratio(a: Double, b: Double): Double {
        val (light, dark) = listOf(a, b).sortedDescending()
        return (light + 0.05) / (dark + 0.05)
    }

    private fun contrast(a: Color, b: Color): Double = ratio(luminance(a), luminance(b))

    /** [tint] at [alpha] over [under] — how a chosen segment's wash is painted. */
    private fun wash(tint: Color, alpha: Float, under: Color): Double = luminance(
        tint.red * alpha + under.red * (1 - alpha),
        tint.green * alpha + under.green * (1 - alpha),
        tint.blue * alpha + under.blue * (1 - alpha),
    )

    private fun assertAtLeast(need: Double, what: String, ratio: Double) =
        assertTrue("$what: ${"%.2f".format(ratio)} < $need", ratio >= need)

    @Test
    fun `the five brand colours are the Owner's`() {
        assertEquals(Color(0xFF581FEB), SmartieColors.Purple)
        assertEquals(Color(0xFF3212BD), SmartieColors.PurpleDark)
        assertEquals(Color(0xFFA138FC), SmartieColors.Highlight)
        assertEquals(Color(0xFF08162C), SmartieColors.Navy)
        assertEquals(Color(0xFFF7F4FF), SmartieColors.IconBackground)
    }

    @Test
    fun `the primary button is the purple, and the deep indigo while pressed`() {
        assertEquals(SmartieColors.Purple, SmartieColors.primaryButton(pressed = false))
        assertEquals(SmartieColors.PurpleDark, SmartieColors.primaryButton(pressed = true))
    }

    @Test
    fun `white text reads on the button, pressed or not`() {
        // The label, and a switch's thumb on its track.
        for (pressed in listOf(false, true)) {
            assertAtLeast(4.5, "white on the button, pressed=$pressed", contrast(Color.White, SmartieColors.primaryButton(pressed)))
        }
    }

    @Test
    fun `purple text reads on every surface it is drawn on`() {
        // A dialog's confirm, "Send with", Hide/Show, a pending line, the photo
        // button — on cards, the page, the pinned wash and the purple tag.
        for ((surface, colour) in listOf(
            "the card" to SmartieColors.Panel,
            "the page" to SmartieColors.Paper,
            "Panel2" to SmartieColors.Panel2,
            "the pinned wash" to SmartieColors.PurpleTint,
            "PurpleLight" to SmartieColors.PurpleLight,
            "the icon background" to SmartieColors.IconBackground,
        )) {
            assertAtLeast(4.5, "purple on $surface", contrast(SmartieColors.Purple, colour))
        }
        // A chosen segment: its own colour at 12% over the card.
        val segment = wash(SmartieColors.Purple, 0.12f, SmartieColors.Panel)
        assertAtLeast(4.5, "purple on its 12% wash", ratio(luminance(SmartieColors.Purple), segment))
    }

    @Test
    fun `the deep indigo reads as text where the old dark purple was`() {
        // The purple tag and the busy chip; sign-in's note on its card.
        assertAtLeast(4.5, "on PurpleLight", contrast(SmartieColors.PurpleDark, SmartieColors.PurpleLight))
        assertAtLeast(4.5, "on the card", contrast(SmartieColors.PurpleDark, SmartieColors.Panel))
        assertAtLeast(4.5, "on the pinned wash", contrast(SmartieColors.PurpleDark, SmartieColors.PurpleTint))
    }

    @Test
    fun `the highlight is seen as a line on everything it borders`() {
        // The field being typed in, a stepper holding a change, a dragged card.
        for ((surface, colour) in listOf(
            "the card" to SmartieColors.Panel,
            "the page" to SmartieColors.Paper,
            "the pinned wash" to SmartieColors.PurpleTint,
        )) {
            assertAtLeast(3.0, "highlight on $surface", contrast(SmartieColors.Highlight, colour))
        }
    }

    @Test
    fun `the intro's line reads on the icon background`() {
        assertAtLeast(4.5, "navy on #F7F4FF", contrast(SmartieColors.Navy, SmartieColors.IconBackground))
    }
}
