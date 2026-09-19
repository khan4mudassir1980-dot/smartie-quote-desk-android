package `in`.smartie.quotedesk.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.data.model.StoppedStockRecord
import `in`.smartie.quotedesk.ui.stock.CLEAR_HISTORY
import `in`.smartie.quotedesk.ui.stock.NOTHING_STOPPED
import `in`.smartie.quotedesk.ui.stock.StockRemovalActions
import `in`.smartie.quotedesk.ui.stock.StoppedHistorySection
import `in`.smartie.quotedesk.ui.stock.stoppedHeading
import `in`.smartie.quotedesk.ui.stock.stoppedRowLabel
import `in`.smartie.quotedesk.ui.theme.SmartieTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What is left of an item after it is gone.
 *
 * The strongest tests here are the negative ones. A history entry outlives
 * the promise that the quantity, the note and the photo were deleted, so
 * what it *does not* show matters more than what it does.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w412dp-h915dp")
class StoppedHistoryScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val entries = listOf(
        stoppedRecord("sr_2", "SIE2000", "Swing gate motor", quantity = 2.0, at = 2_000L),
        stoppedRecord("sr_1", "SIE1000", "Sliding gate motor", quantity = 9.0, at = 1_000L),
        stoppedRecord("sr_0", "SIE-EXT", "Extra receiver", quantity = 5.0, manual = true, at = 500L)
    )

    private fun show(
        expanded: Boolean = false,
        canClear: Boolean = false,
        online: Boolean = true,
        rows: List<StoppedStockRecord> = entries,
        actions: StockRemovalActions = StockRemovalActions()
    ) {
        compose.setContent {
            SmartieTheme {
                StoppedHistorySection(
                    entries = rows,
                    expanded = expanded,
                    canClear = canClear,
                    online = online,
                    actions = actions
                )
            }
        }
    }

    @Test
    fun `it is collapsed, counted, and opens only at the section`() {
        var toggles = 0
        show(actions = StockRemovalActions(onToggleHistory = { toggles++ }))

        compose.onNodeWithText(stoppedHeading(3)).assertIsDisplayed()
        assertEquals("Stopped items (3)", stoppedHeading(3))
        // Nothing inside it until asked for.
        assertTrue(
            compose.onAllNodesWithText("Sliding gate motor").fetchSemanticsNodes().isEmpty()
        )

        compose.onNodeWithContentDescription(stoppedHeading(3)).performClick()
        assertEquals(1, toggles)
    }

    @Test
    fun `expanded it shows the name, the source and the last quantity`() {
        show(expanded = true)

        compose.onNodeWithText("Sliding gate motor").assertIsDisplayed()
        compose.onNodeWithText("Last: 9 each").assertIsDisplayed()
        // Manual and catalogue items are told apart, and nothing else is.
        compose.onNodeWithText("Extra receiver").assertIsDisplayed()
        assertTrue(
            compose.onAllNodesWithText("Manual").fetchSemanticsNodes().isNotEmpty()
        )
        assertTrue(
            compose.onAllNodesWithText("Catalogue").fetchSemanticsNodes().isNotEmpty()
        )
    }

    @Test
    fun `a row is a record, not a control`() {
        show(expanded = true)

        val row = compose.onNodeWithContentDescription(stoppedRowLabel("Sliding gate motor"))
        row.assertIsDisplayed()
        // Displayed with a real height, not a collapsed sliver.
        assertTrue(row.getUnclippedBoundsInRoot().height > 24.dp)

        for (action in listOf("Restore", "Reactivate", "Edit", "Photo", "Pin", "History")) {
            assertTrue(
                "a stopped row must offer no $action",
                compose.onAllNodesWithText(action).fetchSemanticsNodes().isEmpty()
            )
        }
    }

    @Test
    fun `it shows no date, no time, no one's name and no price`() {
        show(expanded = true)

        // The documents carry `at`, `serverAt` and `byUid`; ordering needs the
        // first two and the rules need the third. None of them is rendered.
        for (leak in listOf("2026", "1970", "uid_admin", "Asha", "₹", "Removed by", "Stopped by")) {
            assertTrue(
                "the history must not show $leak",
                compose.onAllNodesWithText(leak, substring = true)
                    .fetchSemanticsNodes().isEmpty()
            )
        }
    }

    @Test
    fun `the latest removal is at the top`() {
        show(expanded = true)

        // The order is the query's — `serverAt` descending — and the section
        // renders what it is handed without re-sorting it.
        val latest = compose.onNodeWithContentDescription(stoppedRowLabel("Swing gate motor"))
            .getUnclippedBoundsInRoot().top
        val older = compose.onNodeWithContentDescription(stoppedRowLabel("Sliding gate motor"))
            .getUnclippedBoundsInRoot().top
        val oldest = compose.onNodeWithContentDescription(stoppedRowLabel("Extra receiver"))
            .getUnclippedBoundsInRoot().top

        assertTrue("$latest should be above $older", latest < older)
        assertTrue("$older should be above $oldest", older < oldest)
    }

    @Test
    fun `only an Owner or Administrator is offered Clear history`() {
        show(expanded = true, canClear = false)
        assertTrue(
            "the displayed Manager and Staff read it and nothing more",
            compose.onAllNodesWithText(CLEAR_HISTORY).fetchSemanticsNodes().isEmpty()
        )
    }

    @Test
    fun `an Administrator gets Clear history, and it only asks`() {
        var asked = 0
        show(
            expanded = true,
            canClear = true,
            actions = StockRemovalActions(onClearHistory = { asked++ })
        )

        compose.onNodeWithText(CLEAR_HISTORY).assertIsDisplayed()
        compose.onNodeWithText(CLEAR_HISTORY).performClick()
        assertEquals(1, asked)
    }

    @Test
    fun `an empty history says so and offers nothing to clear`() {
        show(expanded = true, canClear = true, rows = emptyList())

        compose.onNodeWithText(stoppedHeading(0)).assertIsDisplayed()
        compose.onNodeWithText(NOTHING_STOPPED).assertIsDisplayed()
        assertTrue(
            compose.onAllNodesWithText(CLEAR_HISTORY).fetchSemanticsNodes().isEmpty()
        )
    }

    @Test
    fun `the only thing that can be clicked is the section itself`() {
        show(expanded = true)
        // One clickable node: the heading row. No row, and no Clear for a
        // reader who may not clear.
        assertEquals(
            1,
            compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().size
        )
    }
}
