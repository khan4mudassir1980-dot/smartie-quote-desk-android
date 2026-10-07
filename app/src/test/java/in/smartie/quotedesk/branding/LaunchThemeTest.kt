package `in`.smartie.quotedesk.branding

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The launch theme on Android 14 (N5.12b): Android 12's splash is the icon on
 * `#F7F4FF`, the window under the first frame is the same colour, and Force
 * Dark is refused — in light mode and in dark mode alike.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [34])
class LaunchThemeTest {

    private val iconBackground = 0xFFF7F4FF.toInt()

    private fun colour(attr: Int): Int {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val theme = context.resources.newTheme().apply { applyStyle(R.style.Theme_SmartieQuoteDesk, true) }
        val values = theme.obtainStyledAttributes(intArrayOf(attr))
        try {
            assertTrue("the theme sets attribute $attr", values.hasValue(0))
            return values.getColor(0, 0)
        } finally {
            values.recycle()
        }
    }

    private fun forceDarkAllowed(): Boolean {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val theme = context.resources.newTheme().apply { applyStyle(R.style.Theme_SmartieQuoteDesk, true) }
        val values = theme.obtainStyledAttributes(intArrayOf(android.R.attr.forceDarkAllowed))
        try {
            assertTrue("the theme sets forceDarkAllowed", values.hasValue(0))
            return values.getBoolean(0, true)
        } finally {
            values.recycle()
        }
    }

    @Test
    fun `the splash and the window are the icon background, and Force Dark is refused`() {
        assertEquals("splash", iconBackground, colour(android.R.attr.windowSplashScreenBackground))
        assertEquals("window", iconBackground, colour(android.R.attr.windowBackground))
        assertFalse(forceDarkAllowed())
    }

    @Test
    @Config(qualifiers = "night")
    fun `and the same in dark mode`() {
        assertEquals("splash", iconBackground, colour(android.R.attr.windowSplashScreenBackground))
        assertEquals("window", iconBackground, colour(android.R.attr.windowBackground))
        assertFalse(forceDarkAllowed())
    }
}
