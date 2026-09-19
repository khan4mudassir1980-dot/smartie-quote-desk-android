package `in`.smartie.quotedesk.ui

import android.app.Application
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.ui.theme.SmartieTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The bottom navigation on the smallest phone the app supports, with the
 * system font turned up and three-button navigation taking 48dp.
 *
 * Five tabs across 320dp, at 1.3x text: the case where a bar that steals its
 * inset from its own content has nothing left to draw in. `SmartieTheme`
 * honours the system scale up to 1.3, so providing it here is the same path
 * a person's accessibility setting takes.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w320dp-h480dp")
class BottomNavSmallPhoneScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(fontScale: Float) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale)
            ) {
                SmartieTheme {
                    SmartieBottomBar(
                        destinations = bottomDestinations,
                        selectedRoute = "stock",
                        onSelect = {},
                        insets = WindowInsets(bottom = 48.dp),
                    )
                }
            }
        }
    }

    private fun item(label: String) = compose.onNodeWithText(label).getUnclippedBoundsInRoot()

    private fun label(text: String) =
        compose.onNodeWithText(text, useUnmergedTree = true).getUnclippedBoundsInRoot()

    @Test
    fun `at a larger font every tab keeps its label, inside the bar`() {
        show(fontScale = 1.3f)
        val bar = compose.onNodeWithContentDescription(BOTTOM_NAV_LABEL)
            .getUnclippedBoundsInRoot()

        for (destination in bottomDestinations) {
            compose.onNodeWithText(destination.label).assertIsDisplayed()
            val tab = item(destination.label)
            val text = label(destination.label)

            assertTrue("${destination.label} had no width", tab.width > 0.dp)
            assertTrue("${destination.label} had no height", tab.height > 0.dp)
            assertTrue(
                "${destination.label}'s label is clipped: $text outside $tab",
                text.top >= tab.top - 1.dp && text.bottom <= tab.bottom + 1.dp
            )
            assertTrue(
                "${destination.label} reaches into the system navigation",
                tab.bottom <= bar.bottom - 48.dp + 1.dp
            )
        }
    }

    @Test
    fun `five tabs fit across 320dp without overlapping`() {
        show(fontScale = 1.3f)
        val bounds = bottomDestinations.map { item(it.label) }

        for ((left, right) in bounds.zipWithNext()) {
            assertTrue("tabs overlap: $left and $right", left.right <= right.left + 1.dp)
        }
    }
}
