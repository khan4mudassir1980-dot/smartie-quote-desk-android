package `in`.smartie.quotedesk.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.data.model.PurchaseRecord
import `in`.smartie.quotedesk.data.model.UrgencyV2
import `in`.smartie.quotedesk.domain.PurchaseBoard
import `in`.smartie.quotedesk.ui.purchase.PurchaseCapabilities
import `in`.smartie.quotedesk.ui.purchase.PurchaseSheet
import `in`.smartie.quotedesk.ui.purchase.rowActionLabel
import `in`.smartie.quotedesk.ui.screens.CLOSED_SECTION
import `in`.smartie.quotedesk.ui.screens.PurchaseBoardScreen
import `in`.smartie.quotedesk.ui.theme.SmartieTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A requirement that has arrived **in part**, on the board.
 *
 * The defect behind this class: the first delivery closed the requirement
 * whatever its size, so five of ten arriving took the other five off the shop
 * floor's list entirely. A part delivery has to stay open, stay where its
 * urgency puts it, and say on the card what is still outstanding — that last
 * part being the whole reason anybody looks at the list.
 *
 * Its own small class, like the rest of the purchase screen tests:
 * Robolectric's native-object registry is a fixed array per JVM and a Compose
 * composition consumes a great many entries.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w412dp-h915dp")
class PurchasePartialReceiptScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val partly = requirement(
        "pr_partly",
        name = "Sliding gate rack",
        quantity = 10.0,
        urgency = UrgencyV2.URGENT,
        createdAt = 3_000,
        receivedQuantity = 4.0
    )

    private fun show(records: List<PurchaseRecord>) {
        compose.setContent {
            SmartieTheme {
                PurchaseBoardScreen(
                    active = PurchaseBoard.active(records),
                    closed = PurchaseBoard.closed(records),
                    capabilities = PurchaseCapabilities.forMember(purchaseAdmin)
                )
            }
        }
    }

    @Test
    fun `a part delivery leaves the requirement open and says what is left`() {
        show(listOf(partly))

        compose.onNodeWithText("10 required · 4 received · 6 remaining").assertIsDisplayed()
        assertFalse(
            "a part delivery must not close anything",
            compose.boardShows(CLOSED_SECTION)
        )
        // Still receivable, which is the point: the rest is still coming.
        assertTrue(compose.boardHas(rowActionLabel(PurchaseSheet.RECEIVE, partly.name)))
    }

    @Test
    fun `it keeps its place inside its own urgency, not the bottom of the list`() {
        // Red above yellow above green, and a part delivery changes nothing
        // about how badly the rest is needed.
        val critical = requirement(
            "pr_critical",
            name = "Emergency stop button",
            urgency = UrgencyV2.CRITICAL,
            createdAt = 1_000
        )
        val normal = requirement(
            "pr_normal",
            name = "Remote handsets",
            urgency = UrgencyV2.NORMAL,
            createdAt = 9_000
        )
        show(listOf(normal, partly, critical))

        val order = listOf("Emergency stop button", "Sliding gate rack", "Remote handsets")
            .map { compose.onNodeWithText(it).fetchSemanticsNode().positionInRoot.y }

        assertTrue("red before yellow", order[0] < order[1])
        assertTrue("yellow before green", order[1] < order[2])
    }

    @Test
    fun `a card nobody has delivered against stays on one short figure`() {
        // Three numbers on every card, for the ordinary case, would be three
        // ways of saying one thing.
        show(listOf(requirement("pr_fresh", name = "Remote handsets", quantity = 6.0)))

        compose.onNodeWithText("6 needed").assertIsDisplayed()
        assertFalse(compose.boardShows("6 required"))
    }

    @Test
    fun `an open card does not also carry the closed card's received figure`() {
        show(listOf(partly))

        // "4 in" belongs to a finished requirement. Saying it beside
        // "4 received" is how a card starts being read as closed.
        assertTrue(
            "the received figure is said once, in the line that has the context",
            compose.onAllNodesWithText("4 in", substring = true).fetchSemanticsNodes().isEmpty()
        )
    }

    @Test
    fun `the last delivery moves it to the closed section with the running total`() {
        val finished = partly.copy(
            receivedQuantity = 10.0,
            received = true,
            status = "Received",
            receivedAt = 8_000
        )
        show(listOf(finished))

        assertTrue(compose.boardShows(CLOSED_SECTION))
        // The cumulative total, not the size of the delivery that closed it.
        assertTrue(compose.boardShows("10 in"))
        assertFalse(
            "a closed requirement is not still counting down",
            compose.boardShows("remaining")
        )
    }
}
