package `in`.smartie.quotedesk.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.data.model.PurchaseRecord
import `in`.smartie.quotedesk.domain.PurchaseBoard
import `in`.smartie.quotedesk.ui.purchase.PurchaseCapabilities
import `in`.smartie.quotedesk.ui.screens.PurchaseBoardScreen
import `in`.smartie.quotedesk.ui.theme.SmartieTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * History on the Purchase screen: folded away, and folded again inside.
 *
 * C1. What has already happened is not what somebody standing on the shop
 * floor opened the tab for, and an always-open "Received and closed" section
 * pushed the open list up the screen. Two taps to reach a removal is the
 * point, not an accident: a removal is usually a mistake being tidied away.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w412dp-h915dp")
class PurchaseHistorySectionScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val open = requirement("pr_open", name = "Sliding gate rack")
    private val arrived = requirement(
        "pr_done",
        name = "Emergency stop button",
        received = true,
        receivedQuantity = 2.0
    )
    private val gone = requirement("pr_gone", name = "Duplicate entry", deleted = true)

    private fun show(
        received: List<PurchaseRecord> = emptyList(),
        removed: List<PurchaseRecord> = emptyList()
    ) {
        compose.setContent {
            SmartieTheme {
                PurchaseBoardScreen(
                    active = PurchaseBoard.active(listOf(open)),
                    received = received,
                    removed = removed,
                    capabilities = PurchaseCapabilities.forMember(purchaseAdmin),
                    capabilitiesFor = capabilitiesFor(purchaseAdmin)
                )
            }
        }
    }

    @Test
    fun `History is closed on arrival and holds nothing until it is opened`() {
        show(received = listOf(arrived))

        assertTrue("the open requirement is on the board", compose.boardShows("Sliding gate rack"))
        assertTrue(compose.hasHistory())
        assertFalse("and what arrived is not", compose.boardShows("Emergency stop button"))

        compose.openHistory(count = 1)
        assertTrue(compose.boardShows("Emergency stop button"))
    }

    @Test
    fun `its heading counts the received and the removed together`() {
        show(received = listOf(arrived), removed = listOf(gone))

        // Two things have happened to this board, whichever kind they are.
        assertTrue(compose.boardShows("History (2)"))
    }

    @Test
    fun `removals fold again inside it`() {
        show(received = listOf(arrived), removed = listOf(gone))

        compose.openHistory(count = 2)
        // One tap in, the removal is still not shown — only its heading.
        assertTrue(compose.boardShows("Removed (1)"))
        assertFalse(compose.boardShows("Duplicate entry"))

        compose.openRemoved(count = 1)
        assertTrue(compose.boardShows("Duplicate entry"))
    }

    @Test
    fun `a removed requirement still says what had arrived against it`() {
        // C7. Taking a requirement off the list does not un-deliver what
        // already came, and a card that hides it reads as though it did.
        val partlyThenRemoved = requirement(
            "pr_part_gone",
            name = "Half a pallet",
            quantity = 10.0,
            receivedQuantity = 2.0,
            deleted = true
        )
        show(removed = listOf(partlyThenRemoved))

        compose.openHistory(count = 1)
        compose.openRemoved(count = 1)
        assertTrue(compose.boardShows("Half a pallet"))
        assertTrue("what arrived before it was removed", compose.boardShows("2 in"))
    }

    @Test
    fun `a board with nothing behind it offers no History at all`() {
        show()

        assertFalse(compose.hasHistory())
    }

    @Test
    fun `removals alone still open History, and say nothing was received`() {
        show(removed = listOf(gone))

        assertTrue(compose.hasHistory())
        compose.openHistory(count = 1)
        assertTrue(compose.boardShows("Nothing has been received yet."))
        assertTrue(compose.boardShows("Removed (1)"))
    }
}
