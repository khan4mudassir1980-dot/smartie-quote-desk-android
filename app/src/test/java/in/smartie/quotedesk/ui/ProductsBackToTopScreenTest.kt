package `in`.smartie.quotedesk.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.data.mapping.Keys
import `in`.smartie.quotedesk.data.model.ProductRecord
import `in`.smartie.quotedesk.data.model.RateTierV2
import `in`.smartie.quotedesk.domain.Catalogue
import `in`.smartie.quotedesk.domain.QuoteDraft
import `in`.smartie.quotedesk.domain.ScrollToTop
import `in`.smartie.quotedesk.ui.products.ProductsCatalogue
import `in`.smartie.quotedesk.ui.theme.SmartieTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Getting back to the top of a long catalogue.
 *
 * Products only, and only once there is somewhere to come back from. The
 * assertions are on **bounds**, not on the semantics tree alone: a control
 * can be present, described and clickable while being zero pixels wide, which
 * is exactly how the Photo control shipped once.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w412dp-h915dp")
class ProductsBackToTopScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val shelf = (1..60).map { number ->
        ProductRecord(
            documentId = Keys.productDocId("gate", "SIE$number"),
            key = Keys.productKey("gate", "SIE$number"),
            group = "gate",
            seedModel = "SIE$number",
            model = "SIE$number",
            name = "Sliding gate motor $number",
            categoryId = "cat-sliding",
            client = 25_900.0,
            seeded = true,
        )
    }

    private fun render(query: String = "", draft: QuoteDraft = QuoteDraft()) {
        val view = Catalogue.build(shelf, emptyList(), emptyList(), query)
        compose.setContent {
            SmartieTheme {
                ProductsCatalogue(
                    canViewProducts = true,
                    view = view,
                    draft = draft,
                    query = query,
                    openShelves = setOf("cat-sliding"),
                )
            }
        }
    }

    private fun backToTop() = compose.onAllNodesWithContentDescription(ScrollToTop.LABEL)

    private fun scrollDown() {
        compose.onNode(hasScrollAction())
            .performScrollToNode(hasText("SIE40"))
        compose.waitForIdle()
    }

    @Test
    fun `at the top there is no control at all`() {
        render()
        assertEquals(
            "nothing floats over a catalogue nobody has scrolled",
            0,
            backToTop().fetchSemanticsNodes().size
        )
    }

    @Test
    fun `it appears after a real scroll, at a finger's size`() {
        render()
        scrollDown()

        val control = compose.onNodeWithContentDescription(ScrollToTop.LABEL)
        control.assertIsDisplayed()

        val bounds = control.getUnclippedBoundsInRoot()
        assertTrue("width was ${bounds.width}", bounds.width >= 44.dp)
        assertTrue("height was ${bounds.height}", bounds.height >= 44.dp)
    }

    @Test
    fun `it sits above the quote bar, never over it`() {
        render()
        scrollDown()

        val control = compose.onNodeWithContentDescription(ScrollToTop.LABEL)
            .getUnclippedBoundsInRoot()
        val quoteBar = compose.onNodeWithText("View quote").getUnclippedBoundsInRoot()

        assertTrue(
            "the control overlaps the quote bar: $control over $quoteBar",
            control.bottom <= quoteBar.top
        )
    }

    @Test
    fun `tapping it goes to the absolute top, and it goes away`() {
        render()
        scrollDown()
        compose.onNodeWithContentDescription(ScrollToTop.LABEL).performClick()
        compose.waitForIdle()

        // The first item of the catalogue is the search box.
        compose.onNodeWithText("Search").assertIsDisplayed()
        assertEquals(
            "it hides again once there is nowhere to go",
            0,
            backToTop().fetchSemanticsNodes().size
        )
    }

    @Test
    fun `it scrolls and changes nothing else`() {
        val draft = QuoteDraft(tier = RateTierV2.CONTRACTOR).add(shelf.first(), 3.0)
        render(query = "motor", draft = draft)

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("SIE40"))
        compose.waitForIdle()
        compose.onNodeWithContentDescription(ScrollToTop.LABEL).performClick()
        compose.waitForIdle()

        // The search text, the tier and the draft are all held elsewhere and
        // a scroll must not disturb any of them.
        compose.onNodeWithText("motor").assertIsDisplayed()
        compose.onNodeWithText("1 in the quotation").assertIsDisplayed()
    }
}
