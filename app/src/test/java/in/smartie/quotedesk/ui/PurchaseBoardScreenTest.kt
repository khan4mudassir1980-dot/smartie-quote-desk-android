package `in`.smartie.quotedesk.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.data.model.PurchaseRecord
import `in`.smartie.quotedesk.data.model.UrgencyV2
import `in`.smartie.quotedesk.domain.PurchaseBoard
import `in`.smartie.quotedesk.ui.purchase.ADD_REQUIREMENT
import `in`.smartie.quotedesk.ui.purchase.OFFLINE
import `in`.smartie.quotedesk.ui.purchase.PurchaseActions
import `in`.smartie.quotedesk.ui.purchase.PurchaseCapabilities
import `in`.smartie.quotedesk.ui.purchase.PurchaseSheet
import `in`.smartie.quotedesk.ui.screens.CLOSED_SECTION
import `in`.smartie.quotedesk.ui.screens.LOADING
import `in`.smartie.quotedesk.ui.screens.NOTHING_WAITING
import `in`.smartie.quotedesk.ui.screens.OPEN_SECTION
import `in`.smartie.quotedesk.ui.screens.PurchaseBoardScreen
import `in`.smartie.quotedesk.ui.theme.SmartieTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** What the board shows, and what it refuses to show. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w412dp-h915dp")
class PurchaseBoardScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val open = requirement("pr_open", name = "Sliding gate rack", createdAt = 1_000)
    private val newer = requirement("pr_newer", name = "Remote handsets", createdAt = 5_000)
    private val done = requirement(
        "pr_done",
        name = "Emergency stop button",
        createdAt = 2_000,
        received = true,
        receivedQuantity = 2.0,
        receivedAt = 6_000
    )
    private val removed = requirement("pr_gone", name = "Duplicate entry", deleted = true)

    private fun show(
        records: List<PurchaseRecord>,
        loading: Boolean = false,
        online: Boolean = true,
        capabilities: PurchaseCapabilities =
            PurchaseCapabilities.forMember(purchaseAdmin),
        actions: PurchaseActions = PurchaseActions()
    ) {
        compose.setContent {
            SmartieTheme {
                PurchaseBoardScreen(
                    active = PurchaseBoard.active(records),
                    closed = PurchaseBoard.closed(records),
                    loading = loading,
                    online = online,
                    capabilities = capabilities,
                    actions = actions
                )
            }
        }
    }

    @Test
    fun `open requirements are listed newest first`() {
        show(listOf(open, newer))

        compose.onNodeWithText(OPEN_SECTION).assertIsDisplayed()
        val positions = listOf("Remote handsets", "Sliding gate rack").map { name ->
            compose.onNodeWithText(name).fetchSemanticsNode().positionInRoot.y
        }
        assertTrue(
            "the newest requirement belongs at the top of the active list",
            positions[0] < positions[1]
        )
    }

    @Test
    fun `received requirements are in the closed section, not the open one`() {
        show(listOf(open, done))

        // Two headings, each carrying its own count.
        compose.onNodeWithText(OPEN_SECTION).assertIsDisplayed()
        assertTrue(compose.boardShows(CLOSED_SECTION))
        assertTrue(compose.boardShows("Emergency stop button"))
        // The received quantity is on the card, so the closed row says what
        // actually arrived rather than what was asked for.
        assertTrue(compose.boardShows("2 in"))
    }

    @Test
    fun `a removed requirement is in neither section`() {
        show(listOf(open, removed))

        assertTrue(compose.boardShows("Sliding gate rack"))
        // The defect this closes: `filterNot { isOpen }` put a soft-deleted
        // row into "Received and closed", because a removed requirement that
        // was never received is neither open nor closed.
        assertFalse(
            "a removed requirement must not reappear as history",
            compose.boardShows("Duplicate entry")
        )
        assertFalse(
            "and there is no closed section to put it in",
            compose.boardShows(CLOSED_SECTION)
        )
    }

    @Test
    fun `a removed requirement that had been received is gone too`() {
        val removedAndReceived = requirement(
            "pr_both",
            name = "Old bracket",
            received = true,
            deleted = true
        )
        show(listOf(open, removedAndReceived))

        assertFalse(compose.boardShows("Old bracket"))
        assertFalse(compose.boardShows(CLOSED_SECTION))
    }

    @Test
    fun `a board that has not arrived does not claim to be empty`() {
        show(emptyList(), loading = true)

        compose.onNodeWithText(LOADING).assertIsDisplayed()
        assertEquals(
            "saying nothing is waiting about a list nobody has seen is a lie",
            0,
            compose.onAllNodesWithText(NOTHING_WAITING).fetchSemanticsNodes().size
        )
    }

    @Test
    fun `an empty board says so once it has arrived`() {
        show(emptyList())

        compose.onNodeWithText(NOTHING_WAITING).assertIsDisplayed()
        assertEquals(
            0,
            compose.onAllNodesWithText(LOADING).fetchSemanticsNodes().size
        )
    }

    @Test
    fun `the note and the urgency are on the card without opening Edit`() {
        // N3's T-S25. It could not be run by hand while nothing could create a
        // requirement to look at; this is the automated half.
        show(
            listOf(
                requirement(
                    "pr_note",
                    name = "Gate motor bracket",
                    note = "Two for the Andheri site",
                    urgency = UrgencyV2.NORMAL
                )
            )
        )

        compose.onNodeWithText("Two for the Andheri site").assertIsDisplayed()
        compose.onNodeWithText("Needed, but not now").assertIsDisplayed()
    }

    @Test
    fun `adding opens the add sheet`() {
        var opened: PurchaseSheet? = null
        show(
            listOf(open),
            actions = PurchaseActions(onOpen = { sheet, _ -> opened = sheet })
        )

        compose.onNodeWithContentDescription(ADD_REQUIREMENT).performClick()
        assertEquals(PurchaseSheet.ADD, opened)
    }

    @Test
    fun `offline the add control is disabled and carries the reason`() {
        var opened: PurchaseSheet? = null
        show(
            listOf(open),
            online = false,
            actions = PurchaseActions(onOpen = { sheet, _ -> opened = sheet })
        )

        compose.onNodeWithText(ADD_REQUIREMENT).assertIsNotEnabled()
        compose.onNodeWithContentDescription(OFFLINE).performClick()
        assertEquals("nothing may be opened offline", null, opened)
    }
}
