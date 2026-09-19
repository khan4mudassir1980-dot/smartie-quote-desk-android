package `in`.smartie.quotedesk.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.ui.stock.stoppedHeading
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Where the history sits on the real board, rather than on its own.
 *
 * A `LazyColumn` renders in declaration order, so "at the very bottom" is a
 * property of the screen and not of the section — it was above the rows once,
 * and looked perfectly correct in isolation.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w412dp-h915dp")
class StockBoardHistoryScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val stopped = listOf(
        stoppedRecord("sr_1", "SIE-EXT", "Extra receiver", quantity = 5.0, manual = true)
    )

    @Test
    fun `it is the last thing on the board, after every row`() {
        compose.showStock(capabilities = adminCaps, stopped = stopped)

        compose.onNode(hasScrollAction())
            .performScrollToNode(hasContentDescription(stoppedHeading(1)))

        val history = compose.onNodeWithContentDescription(stoppedHeading(1))
        history.assertIsDisplayed()

        // Below the last stock row, not above the first.
        val lastRow = compose.onNodeWithText("Swing gate motor").getUnclippedBoundsInRoot().top
        assertTrue(
            "the history must come after the rows",
            history.getUnclippedBoundsInRoot().top > lastRow
        )
    }

    @Test
    fun `every role that may see stock may read it`() {
        // The rules say `member()` for `/stoppedStock`, the same as `/stock`.
        // A Worker gets no control anywhere on this board and still gets this.
        compose.showStock(capabilities = workerCaps, stopped = stopped)

        compose.onNode(hasScrollAction())
            .performScrollToNode(hasContentDescription(stoppedHeading(1)))
        compose.onNodeWithContentDescription(stoppedHeading(1)).assertIsDisplayed()
    }

    @Test
    fun `expanded on the board it shows the removed item`() {
        compose.showStock(capabilities = adminCaps, stopped = stopped, historyExpanded = true)

        compose.onNode(hasScrollAction())
            .performScrollToNode(hasContentDescription(stoppedHeading(1)))
        compose.scrollToText("Extra receiver")
        compose.onNodeWithText("Extra receiver").assertIsDisplayed()
        compose.onNodeWithText("Last: 5 each").assertIsDisplayed()
    }

    @Test
    fun `an empty history is still there, saying nothing has been removed`() {
        compose.showStock(capabilities = adminCaps, stopped = emptyList())

        compose.onNode(hasScrollAction())
            .performScrollToNode(hasContentDescription(stoppedHeading(0)))
        compose.onNodeWithContentDescription(stoppedHeading(0)).assertIsDisplayed()
    }
}
