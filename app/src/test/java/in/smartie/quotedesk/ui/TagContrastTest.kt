package `in`.smartie.quotedesk.ui

import androidx.compose.ui.graphics.Color
import `in`.smartie.quotedesk.ui.components.TagTone
import `in`.smartie.quotedesk.ui.theme.SmartieColors
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * The Ordered tag's blue, checked (the advisor's decision 9 of 2026-10-05).
 *
 * The app had no blue token, so N5.10b added one. A tag's words are small,
 * so they are held to WCAG AA for normal text — 4.5:1 — against the tag's own
 * fill, and so is the toggle's text against its fill while selected. Plain
 * arithmetic on the colours, so no screen is needed.
 */
class TagContrastTest {

    private fun channel(c: Float): Double =
        if (c <= 0.03928f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    private fun luminance(color: Color): Double =
        0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)

    private fun contrast(a: Color, b: Color): Double {
        val (light, dark) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (light + 0.05) / (dark + 0.05)
    }

    @Test
    fun `the Ordered tag's text meets AA on its fill`() {
        val ratio = contrast(TagTone.BLUE.content, TagTone.BLUE.background)
        assertTrue("Ordered tag contrast $ratio", ratio >= 4.5)
    }

    @Test
    fun `and on the card behind it`() {
        val ratio = contrast(TagTone.BLUE.content, SmartieColors.Panel)
        assertTrue("against the card $ratio", ratio >= 4.5)
    }

    @Test
    fun `it is blue, and not the received green`() {
        val blue = TagTone.BLUE.content
        assertTrue("blue is the strongest channel", blue.blue > blue.red && blue.blue > blue.green)
        assertNotEquals(TagTone.GREEN.content, blue)
        assertNotEquals(TagTone.GREEN.background, TagTone.BLUE.background)
    }
}
