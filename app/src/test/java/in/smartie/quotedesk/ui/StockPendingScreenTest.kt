package `in`.smartie.quotedesk.ui

import android.app.Application
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.data.model.StockRecord
import `in`.smartie.quotedesk.ui.stock.StockActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A pending `+`/`−` count never moves the stored numbers. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w412dp-h915dp")
class StockPendingScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a pending delta is shown beside the stored quantity, never instead of it`() {
        compose.showStock(pending = mapOf("gateMotors|SIE1000" to 5.0))
        compose.onNodeWithText("9 each").assertExists()
        compose.onNodeWithText("+5 pending").assertExists()
        assertTrue(compose.onAllNodesWithText("14 each").fetchSemanticsNodes().isEmpty())
    }

    /** A pending −2 on a count of 2 must not read Out of stock. */
    @Test
    fun `the status badge ignores a pending delta entirely`() {
        compose.showStock(
            stock = listOf(stockRecord("SIE2000", "Swing gate motor", quantity = 2.0, reorder = 2.0)),
            pending = mapOf("gateMotors|SIE2000" to -2.0)
        )
        // Two nodes read "Low": the tile caption and this row's tag. What
        // matters is that the row is not Out of stock.
        assertEquals(2, compose.onAllNodesWithText("Low").fetchSemanticsNodes().size)
        assertTrue(compose.onAllNodesWithText("Out of stock").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `plus and minus ask for a pending change and Done appears only with one`() {
        val changes = mutableListOf<String>()
        compose.showStock(
            stock = listOf(stockRecord("SIE1000", "Sliding gate motor", quantity = 9.0)),
            actions = StockActions(
                onIncrement = { changes += "+$it" },
                onDecrement = { changes += "-$it" }
            )
        )
        assertTrue(
            "Done appears only with a pending change",
            compose.onAllNodesWithText("Done").fetchSemanticsNodes().isEmpty()
        )

        compose.onNodeWithText("+").performClick()
        compose.onNodeWithText("−").performClick()
        assertEquals(listOf("+gateMotors|SIE1000", "-gateMotors|SIE1000"), changes)
    }

    @Test
    fun `Done saves that row and Clear discards its draft`() {
        var saved: StockRecord? = null
        var cleared: String? = null
        compose.showStock(
            pending = mapOf("gateMotors|SIE1000" to 3.0),
            actions = StockActions(onDone = { saved = it }, onClearPending = { cleared = it })
        )
        compose.scrollToText("Done")
        compose.onNodeWithContentDescription("Done, save Sliding gate motor").performClick()
        assertEquals("gateMotors|SIE1000", saved?.key)

        compose.onNodeWithContentDescription("Clear pending change for Sliding gate motor")
            .performClick()
        assertEquals("gateMotors|SIE1000", cleared)
    }

    @Test
    fun `a row already saving shows Saving and cannot be tapped again`() {
        compose.showStock(
            pending = mapOf("gateMotors|SIE1000" to 3.0),
            saving = setOf("gateMotors|SIE1000")
        )
        compose.scrollToText("Saving")
        compose.onNodeWithContentDescription("Saving Sliding gate motor").assertIsNotEnabled()
    }
}
