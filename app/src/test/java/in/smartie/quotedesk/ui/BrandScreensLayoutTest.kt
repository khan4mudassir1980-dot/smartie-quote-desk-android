package `in`.smartie.quotedesk.ui

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.ui.screens.INTRO_BYLINE
import `in`.smartie.quotedesk.ui.screens.INTRO_LOGO_SIZE
import `in`.smartie.quotedesk.ui.screens.IntroScreen
import `in`.smartie.quotedesk.ui.screens.LOGO_LABEL
import `in`.smartie.quotedesk.ui.screens.SIGN_IN_LOGO_SIZE
import `in`.smartie.quotedesk.ui.screens.SignInScreen
import `in`.smartie.quotedesk.ui.theme.MAX_SYSTEM_FONT_SCALE
import `in`.smartie.quotedesk.ui.theme.SmartieTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The logo on a 360dp-wide phone with the system text at 1.3x (N5.12b): on the
 * intro and on sign-in it paints whole, inside the screen, at its full size —
 * not pushed off an edge, and not squeezed to fit. `SmartieTheme` honours the
 * system scale up to 1.3, so providing it here is the path a person's
 * accessibility setting takes.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w360dp-h640dp")
class BrandScreensLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, MAX_SYSTEM_FONT_SCALE)) {
                SmartieTheme { content() }
            }
        }
    }

    private fun screen(): DpRect = compose.onRoot().getUnclippedBoundsInRoot().also {
        assertEquals("a 360dp-wide screen", 360f, it.width.value, 1f)
    }

    private fun assertInside(what: String, part: DpRect, screen: DpRect) {
        assertTrue(
            "$what at $part, outside the screen $screen",
            part.left >= screen.left && part.top >= screen.top &&
                part.right <= screen.right && part.bottom <= screen.bottom,
        )
    }

    @Test
    fun `the intro's logo and line paint whole inside the screen, at 360dp and 1-3x text`() {
        show { IntroScreen() }
        val screen = screen()
        val logo = compose.onNodeWithContentDescription(LOGO_LABEL).getUnclippedBoundsInRoot()
        val line = compose.onNodeWithText(INTRO_BYLINE).getUnclippedBoundsInRoot()

        assertInside("the logo", logo, screen)
        assertInside("the line", line, screen)
        assertEquals("the logo's width", INTRO_LOGO_SIZE.value, logo.width.value, 0.5f)
        assertEquals("the logo's height", INTRO_LOGO_SIZE.value, logo.height.value, 0.5f)
        assertTrue("the line is below the logo, not over it", line.top >= logo.bottom)
        // Centred: the same space on each side, and above as below.
        assertEquals("centred across", logo.left.value, (screen.right - logo.right).value, 1f)
        assertEquals("centred down", logo.top.value, (screen.bottom - logo.bottom).value, 1f)
        compose.onNodeWithContentDescription(LOGO_LABEL).assertIsDisplayed()
        compose.onNodeWithText(INTRO_BYLINE).assertIsDisplayed()
    }

    @Test
    fun `sign-in shows the logo, whole and above its button, at 360dp and 1-3x text`() {
        show {
            SignInScreen(
                state = SignInUiState(),
                onGoogleSignIn = {},
                onEmailSignIn = { _, _ -> },
                onPasswordReset = {},
            )
        }
        val screen = screen()
        val logo = compose.onNodeWithContentDescription(LOGO_LABEL).getUnclippedBoundsInRoot()
        val button = compose.onNodeWithText("Continue with Google").getUnclippedBoundsInRoot()

        compose.onNodeWithContentDescription(LOGO_LABEL).assertIsDisplayed()
        assertInside("the logo", logo, screen)
        assertEquals("the logo's width", SIGN_IN_LOGO_SIZE.value, logo.width.value, 0.5f)
        assertTrue("the logo $logo is above the button $button", logo.bottom <= button.top)
        assertTrue("and the button is on screen too", button.bottom <= screen.bottom && button.top >= 0.dp)
    }
}
