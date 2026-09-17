package `in`.smartie.quotedesk.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.domain.StockFilter
import `in`.smartie.quotedesk.ui.stock.StockActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The board itself: what is listed, the tiles, the filters and the order. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w412dp-h915dp")
class StockBoardScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `every tracked item is listed with its stored quantity`() {
        compose.showStock()
        compose.onNodeWithText("Sliding gate motor").assertExists()
        compose.onNodeWithText("9 each").assertExists()
        compose.onNodeWithText("Swing gate motor").assertExists()
    }

    @Test
    fun `the tiles count tracked, low and out`() {
        compose.showStock()
        compose.onNodeWithContentDescription("Show all 3 tracked items").assertExists()
        compose.onNodeWithContentDescription("Show 1 low stock items").assertExists()
        compose.onNodeWithContentDescription("Show 1 out of stock items").assertExists()
    }

    @Test
    fun `out of stock is shown only at zero`() {
        compose.showStock()
        // The row sitting exactly on its reorder level is Low, not Out.
        assertEquals(1, compose.onAllNodesWithText("Out of stock").fetchSemanticsNodes().size)
        // The tile caption and the one low row.
        assertEquals(2, compose.onAllNodesWithText("Low").fetchSemanticsNodes().size)
    }

    @Test
    fun `tapping a summary tile asks for that filter`() {
        var asked: StockFilter? = null
        compose.showStock(actions = StockActions(onFilter = { asked = it }))
        compose.onNodeWithContentDescription("Show 1 low stock items").performClick()
        assertEquals(StockFilter.LOW, asked)
    }

    @Test
    fun `the low filter shows the affected products and names the section`() {
        compose.showStock(filter = StockFilter.LOW)
        compose.onNodeWithText("Low stock").assertExists()
        compose.onNodeWithText("Swing gate motor").assertExists()
        assertTrue(compose.onAllNodesWithText("Sliding gate motor").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `the out filter shows the affected products`() {
        compose.showStock(filter = StockFilter.OUT)
        compose.onNodeWithText("Boom barrier").assertExists()
        assertTrue(compose.onAllNodesWithText("Swing gate motor").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `search narrows the list`() {
        compose.showStock(query = "boom")
        compose.onNodeWithText("Boom barrier").assertExists()
        assertTrue(compose.onAllNodesWithText("Sliding gate motor").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `a search that matches nothing says so`() {
        compose.showStock(query = "nothing here at all")
        compose.onNodeWithText("Nothing matches that search.").assertExists()
    }

    @Test
    fun `pinned items lead the list, oldest pin first`() {
        compose.showStock(
            stock = listOf(
                stockRecord("AAA", "Anvil bracket"),
                stockRecord("ZZZ", "Zebra rail", pinned = true, pinOrder = 200.0),
                stockRecord("MMM", "Middle plate", pinned = true, pinOrder = 100.0)
            ),
            capabilities = workerCaps
        )
        fun top(text: String) = compose.onNodeWithText(text).fetchSemanticsNode().positionInRoot.y

        assertTrue("the first pin leads", top("Middle plate") < top("Zebra rail"))
        assertTrue("both pins lead the unpinned row", top("Zebra rail") < top("Anvil bracket"))
    }
}
