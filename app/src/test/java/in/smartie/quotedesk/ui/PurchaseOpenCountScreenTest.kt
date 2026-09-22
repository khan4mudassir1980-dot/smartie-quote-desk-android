package `in`.smartie.quotedesk.ui

import android.app.Application
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodes
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.data.model.PurchaseRecord
import `in`.smartie.quotedesk.domain.PurchaseBoard
import `in`.smartie.quotedesk.ui.purchase.PurchaseCapabilities
import `in`.smartie.quotedesk.ui.purchase.PurchaseSheet
import `in`.smartie.quotedesk.ui.purchase.rowActionLabel
import `in`.smartie.quotedesk.ui.screens.PurchaseBoardScreen
import `in`.smartie.quotedesk.ui.theme.SmartieTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The number beside **Open** is the number of open cards.
 *
 * The phone pass read 8 against 9 cards, all of them above the closed
 * heading. Nothing in the code explains that: the header is `active.size` and
 * `items(...)` iterates the same list. The header may have read 9 at that
 * resolution — but "may have" is not a finding, so this asserts the property
 * instead of arguing about the screenshot.
 *
 * Counted by row action rather than by card, because every open card carries
 * exactly one Edit for an Administrator and a `LazyColumn` composes only what
 * is on screen — so the board is kept short enough to hold at once, and the
 * count is of what is really there.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w412dp-h915dp")
class PurchaseOpenCountScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(records: List<PurchaseRecord>) {
        compose.setContent {
            SmartieTheme {
                PurchaseBoardScreen(
                    active = PurchaseBoard.active(records),
                    closed = PurchaseBoard.closed(records),
                    capabilities = PurchaseCapabilities.forMember(purchaseAdmin),
                    capabilitiesFor = capabilitiesFor(purchaseAdmin)
                )
            }
        }
    }

    /** Every card that drew an Edit control, which is every open card. */
    private fun openCardsOnScreen(records: List<PurchaseRecord>): Int =
        records.count { record ->
            compose.onAllNodes(
                hasContentDescription(rowActionLabel(PurchaseSheet.EDIT, record.name))
            ).fetchSemanticsNodes().isNotEmpty()
        }

    @Test
    fun `the Open header counts the cards the board drew`() {
        val records = (1..3).map { requirement("pr_$it", name = "Requirement $it") }
        show(records)

        compose.onNodeWithText("3").assertExists()
        assertEquals(3, openCardsOnScreen(records))
    }

    @Test
    fun `two documents that look alike are two cards and are counted twice`() {
        // The "yysh" pair: same name, same author, same day, different
        // documents. Both are real requirements and both must be counted —
        // the header disagreeing with the list is the defect, not the pair.
        val twins = listOf(
            requirement("pr_a", name = "yysh", createdAt = 1_700_000_000_000L),
            requirement("pr_b", name = "yysh", createdAt = 1_700_000_001_000L)
        )
        show(twins)

        compose.onNodeWithText("2").assertExists()
        assertEquals(
            "both cards drew their controls",
            2,
            compose.onAllNodes(
                hasContentDescription(rowActionLabel(PurchaseSheet.EDIT, "yysh"))
            ).fetchSemanticsNodes().size
        )
    }

    @Test
    fun `a removed requirement is counted in neither section`() {
        val records = listOf(
            requirement("pr_open", name = "Still waiting"),
            requirement("pr_gone", name = "Taken off", deleted = true)
        )
        show(records)

        compose.onNodeWithText("1").assertExists()
        assertEquals(1, openCardsOnScreen(records))
    }
}
