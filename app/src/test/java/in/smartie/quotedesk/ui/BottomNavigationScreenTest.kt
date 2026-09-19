package `in`.smartie.quotedesk.ui

import android.app.Application
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.ui.theme.SmartieDimens
import `in`.smartie.quotedesk.ui.theme.SmartieTheme
import `in`.smartie.quotedesk.ui.theme.bottomNavMinHeight
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * The bottom navigation against the system navigation.
 *
 * The defect these cover: the bar was pinned to a fixed height and
 * `NavigationBar` applies the navigation-bar inset **inside** its own height,
 * so on a phone with three-button navigation the items were laid out in what
 * was left and finished up under the system navigation. The inset is handed
 * in here, so the three-button case is a test rather than a second phone.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w412dp-h915dp")
class BottomNavigationScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val floor = bottomNavMinHeight(SmartieDimens().bottomNavHeight, 1f)

    private var inset by mutableStateOf(0.dp)

    private fun show(bottomInset: Dp = 0.dp) {
        inset = bottomInset
        compose.setContent {
            SmartieTheme {
                SmartieBottomBar(
                    destinations = bottomDestinations,
                    selectedRoute = "stock",
                    onSelect = {},
                    insets = WindowInsets(bottom = inset),
                )
            }
        }
    }

    /** The whole tab. `NavigationBarItem` merges its icon and its label. */
    private fun item(label: String) = compose.onNodeWithText(label).getUnclippedBoundsInRoot()

    /** The label alone, which is what a fixed height used to cut off. */
    private fun label(text: String) =
        compose.onNodeWithText(text, useUnmergedTree = true).getUnclippedBoundsInRoot()

    private fun bar() =
        compose.onNodeWithContentDescription(BOTTOM_NAV_LABEL).getUnclippedBoundsInRoot()

    @Test
    fun `every tab is there, with a size, and no shorter than the compact floor`() {
        show()
        for (destination in bottomDestinations) {
            compose.onNodeWithText(destination.label).assertIsDisplayed()
            val bounds = item(destination.label)
            assertTrue("${destination.label} had no width", bounds.width > 0.dp)
            assertTrue(
                "${destination.label} was ${bounds.height}, under the $floor floor",
                bounds.height >= floor - 1.dp
            )
        }
    }

    @Test
    fun `every label is inside its tab rather than cut off below it`() {
        show()
        for (destination in bottomDestinations) {
            val tab = item(destination.label)
            val text = label(destination.label)
            assertTrue(
                "${destination.label}'s label is clipped: $text outside $tab",
                text.top >= tab.top - 1.dp && text.bottom <= tab.bottom + 1.dp
            )
            assertTrue("${destination.label}'s label has no height", text.height > 0.dp)
        }
    }

    @Test
    fun `three-button navigation takes nothing out of the bar's content`() {
        show(bottomInset = 0.dp)
        val gestureBar = bar().height
        val gestureTab = item("Our Stock").height

        // The same bar on a phone with three-button navigation.
        inset = 48.dp
        compose.waitForIdle()

        val threeButtonBar = bar().height
        val threeButtonTab = item("Our Stock").height

        assertTrue(
            "expected $gestureBar + 48dp, got $threeButtonBar",
            abs((threeButtonBar - gestureBar - 48.dp).value) < 1f
        )
        assertTrue(
            "the tab lost height to the inset: $threeButtonTab was $gestureTab",
            abs((threeButtonTab - gestureTab).value) < 1f
        )
    }

    @Test
    fun `nothing sits in the strip the system navigation owns`() {
        show(bottomInset = 48.dp)
        val bar = bar()

        for (destination in bottomDestinations) {
            val bounds = item(destination.label)
            assertTrue(
                "${destination.label} reaches into the system navigation",
                bounds.bottom <= bar.bottom - 48.dp + 1.dp
            )
        }
    }

    @Test
    fun `the tabs are evenly spaced across the bar`() {
        show()
        val centres = bottomDestinations.map {
            val bounds = item(it.label)
            (bounds.left + bounds.right).value / 2f
        }
        val gaps = centres.zipWithNext { a, b -> b - a }

        assertTrue("the tabs are not in order: $centres", gaps.all { it > 0f })
        assertTrue("uneven spacing: $gaps", gaps.max() - gaps.min() < 2f)
    }
}
