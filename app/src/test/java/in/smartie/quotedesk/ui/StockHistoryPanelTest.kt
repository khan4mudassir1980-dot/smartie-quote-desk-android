package `in`.smartie.quotedesk.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.data.model.StockMove
import `in`.smartie.quotedesk.domain.StockBoard
import `in`.smartie.quotedesk.ui.stock.StockHistoryPanel
import `in`.smartie.quotedesk.ui.theme.SmartieTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What History shows. The **panel**, not the dialog, for the reason recorded
 * in `docs/PROJECT-STATUS.md`: a Compose `Dialog` runs its own recomposer,
 * which the Robolectric clock does not drive.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w412dp-h915dp")
class StockHistoryPanelTest {

    @get:Rule
    val compose = createComposeRule()

    private fun move(id: String, delta: Double, previous: Double, at: Long, note: String = "") =
        StockMove(
            id = id,
            key = "gateMotors|SIE1000",
            action = "adjust",
            previous = previous,
            delta = delta,
            next = previous + delta,
            note = note,
            by = "Asha",
            at = at
        )

    @Test
    fun `movements read newest first, with who and why`() {
        var closed = false
        compose.setContent {
            SmartieTheme {
                StockHistoryPanel(
                    name = "Sliding gate motor",
                    movements = StockBoard.historyFor(
                        listOf(
                            move("m1", 5.0, 0.0, at = 1_000L, note = "Opening count"),
                            move("m2", -2.0, 5.0, at = 2_000L, note = "Site fitting")
                        ),
                        "gateMotors|SIE1000"
                    ),
                    onClose = { closed = true }
                )
            }
        }
        compose.onNodeWithText("ADJUST -2 · 5 → 3").assertExists()
        compose.onNodeWithText("Asha · Site fitting").assertExists()
        compose.onNodeWithText("ADJUST +5 · 0 → 5").assertExists()

        val newest = compose.onNodeWithText("ADJUST -2 · 5 → 3")
            .fetchSemanticsNode().positionInRoot.y
        val oldest = compose.onNodeWithText("ADJUST +5 · 0 → 5")
            .fetchSemanticsNode().positionInRoot.y
        assertTrue("the newest movement leads", newest < oldest)

        compose.onNodeWithContentDescription("Close history for Sliding gate motor")
            .performClick()
        assertTrue(closed)
    }

    @Test
    fun `an item nothing has happened to says so`() {
        compose.setContent {
            SmartieTheme {
                StockHistoryPanel(name = "Boom barrier", movements = emptyList(), onClose = {})
            }
        }
        compose.onNodeWithText("Nothing has moved yet.").assertExists()
    }
}
