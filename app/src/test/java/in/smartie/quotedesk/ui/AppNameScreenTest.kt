package `in`.smartie.quotedesk.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.ui.more.AboutScreen
import `in`.smartie.quotedesk.ui.theme.SmartieTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Where a screen shows the app's own name, it is "Quote Desk" (the Owner,
 * 2026-10-07): the About screen's heading, and the header's title where no
 * screen has one. The firm's name on the About screen stays.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w412dp-h915dp")
class AppNameScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the About screen is headed Quote Desk, and still names the firm`() {
        compose.setContent { SmartieTheme { AboutScreen() } }

        compose.onNodeWithText("Quote Desk").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("SMARTIE Quote Desk").fetchSemanticsNodes().size)
        compose.onNodeWithText("Smart India Enterprises").assertExists()
    }

    @Test
    fun `the header says Quote Desk where no screen has a title of its own`() {
        assertEquals("Quote Desk", titleFor(null))
        assertEquals("Quote Desk", titleFor("more/no-such-screen"))
        // A screen's own title is untouched.
        assertEquals("Our Stock", titleFor("stock"))
    }
}
