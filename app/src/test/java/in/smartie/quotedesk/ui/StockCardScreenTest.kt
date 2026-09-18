package `in`.smartie.quotedesk.ui

import android.app.Application
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.ui.components.NOTE_TAG
import `in`.smartie.quotedesk.ui.stock.stockItemLabel
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * One card per item: its details, its pending line and its controls.
 *
 * The first staging pass found the controls in a second card that read as an
 * unrelated thing, and a saved note that was never displayed at all.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w412dp-h915dp")
class StockCardScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val card = stockItemLabel("Sliding gate motor")

    @Test
    fun `the controls belong to the same card as the item`() {
        compose.showStock(
            stock = listOf(stockRecord("SIE1000", "Sliding gate motor", quantity = 9.0))
        )
        // Not a proximity check: each control has to be a descendant of this
        // item's own card.
        for (control in listOf(
            "Change Sliding gate motor by one",
            "Pin Sliding gate motor",
            "Edit Sliding gate motor",
            "History for Sliding gate motor"
        )) {
            compose.onNode(
                hasContentDescription(control) and hasAnyAncestor(hasContentDescription(card))
            ).assertExists()
        }
    }

    @Test
    fun `the stored quantity is captioned and the pending figure is not`() {
        compose.showStock(
            stock = listOf(stockRecord("SIE1000", "Sliding gate motor", quantity = 9.0)),
            pending = mapOf("gateMotors|SIE1000" to -4.0)
        )
        compose.onNodeWithText("9 each").assertExists()
        compose.onNodeWithText("in stock").assertExists()
        compose.onNodeWithText("Pending change -4 · 5 each after Done").assertExists()
    }

    @Test
    fun `a saved note is shown on the card`() {
        compose.showStock(
            stock = listOf(
                stockRecord("SIE1000", "Sliding gate motor", note = "Kept in the back rack")
            )
        )
        // On the item's own card, not somewhere else on the screen.
        compose.onNode(
            hasText("Kept in the back rack") and hasAnyAncestor(hasContentDescription(card))
        ).assertExists()
        compose.onAllNodesWithTag(NOTE_TAG).assertCountEquals(1)
    }

    @Test
    fun `an item with no note shows no empty note line`() {
        compose.showStock(
            stock = listOf(stockRecord("SIE1000", "Sliding gate motor", reorder = 0.0))
        )
        compose.onNodeWithText("Sliding gate motor").assertExists()
        // Nothing but the name, the model and the tags: no blank note line,
        // and no reorder line either when the level is zero.
        compose.onAllNodesWithTag(NOTE_TAG).assertCountEquals(0)
        assertTrue(
            compose.onAllNodesWithText("Reorder at", substring = true)
                .fetchSemanticsNodes().isEmpty()
        )
    }

    @Test
    fun `History is offered on every tracked item`() {
        compose.showStock()
        for (name in listOf("Sliding gate motor", "Swing gate motor")) {
            compose.onNodeWithContentDescription("History for $name").assertExists()
        }
        // The third card sits below the fold on a phone-sized screen.
        compose.scrollToText("Boom barrier")
        compose.onNodeWithContentDescription("History for Boom barrier").assertExists()
    }

    @Test
    fun `a Worker gets no History control`() {
        compose.showStock(capabilities = workerCaps)
        compose.onNodeWithText("Sliding gate motor").assertExists()
        assertTrue(compose.onAllNodesWithText("History").fetchSemanticsNodes().isEmpty())
    }
}
