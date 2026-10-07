package `in`.smartie.quotedesk.ui

import android.app.Application
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.ui.screens.BrandBackgroundKey
import `in`.smartie.quotedesk.ui.screens.IntroScreen
import `in`.smartie.quotedesk.ui.screens.SignInScreen
import `in`.smartie.quotedesk.ui.theme.SmartieColors
import `in`.smartie.quotedesk.ui.theme.SmartieTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * With the phone in dark mode, the intro and sign-in still paint the light
 * icon background, `#F7F4FF` (N5.12b).
 *
 * Each screen reports the colour it paints through [BrandBackgroundKey], set
 * by the same call that paints it. The test first proves it is running in
 * dark mode — `isSystemInDarkTheme()` is true — so a pass is not a light-mode
 * pass by accident.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w360dp-h640dp-night")
class BrandScreensDarkModeTest {

    @get:Rule
    val compose = createComposeRule()

    private fun painted() = compose.onNode(SemanticsMatcher.keyIsDefined(BrandBackgroundKey))
        .fetchSemanticsNode().config[BrandBackgroundKey]

    @Test
    fun `the intro stays light in dark mode`() {
        var dark = false
        compose.setContent {
            dark = isSystemInDarkTheme()
            SmartieTheme { IntroScreen() }
        }
        compose.waitForIdle()
        assertTrue("the phone is in dark mode", dark)
        assertEquals(SmartieColors.IconBackground, painted())
    }

    @Test
    fun `sign-in stays light in dark mode`() {
        var dark = false
        compose.setContent {
            dark = isSystemInDarkTheme()
            SmartieTheme {
                SignInScreen(
                    state = SignInUiState(),
                    onGoogleSignIn = {},
                    onEmailSignIn = { _, _ -> },
                    onPasswordReset = {},
                )
            }
        }
        compose.waitForIdle()
        assertTrue("the phone is in dark mode", dark)
        assertEquals(SmartieColors.IconBackground, painted())
    }
}
