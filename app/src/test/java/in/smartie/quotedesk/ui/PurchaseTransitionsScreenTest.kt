package `in`.smartie.quotedesk.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.data.model.PurchaseRecord
import `in`.smartie.quotedesk.domain.PurchaseBoard
import `in`.smartie.quotedesk.ui.purchase.PurchaseCapabilities
import `in`.smartie.quotedesk.ui.purchase.PurchaseSheet
import `in`.smartie.quotedesk.ui.purchase.rowActionLabel
import `in`.smartie.quotedesk.ui.screens.CLOSED_SECTION
import `in`.smartie.quotedesk.ui.screens.NOTHING_WAITING
import `in`.smartie.quotedesk.ui.screens.PurchaseBoardScreen
import `in`.smartie.quotedesk.ui.theme.SmartieTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A requirement moving between the two sections as the listener reports it.
 *
 * Nothing here is optimistic, and these follow the same order the app does: a
 * write goes out, Firestore echoes the changed document back, and the board
 * re-derives both lists from it. So the test changes the **records** and
 * asserts what the board then shows — never the other way round.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w412dp-h915dp")
class PurchaseTransitionsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val rack = requirement("pr_one", name = "Sliding gate rack")

    /** The board over a list the test can change, as the listener would. */
    private fun showing(initial: List<PurchaseRecord>): (List<PurchaseRecord>) -> Unit {
        var records by mutableStateOf(initial)
        compose.setContent {
            SmartieTheme {
                PurchaseBoardScreen(
                    active = PurchaseBoard.active(records),
                    closed = PurchaseBoard.closed(records),
                    capabilities = PurchaseCapabilities.forMember(purchaseAdmin)
                )
            }
        }
        return { next -> compose.runOnIdle { records = next } }
    }

    @Test
    fun `receiving moves a requirement out of Open and into Received and closed`() {
        val listener = showing(listOf(rack))

        assertTrue(compose.boardHas(rowActionLabel(PurchaseSheet.RECEIVE, rack.name)))
        assertFalse(compose.boardShows(CLOSED_SECTION))

        // What the write puts on the wire, read back: status Received, the
        // received quantity, and who had it.
        listener(
            listOf(
                rack.copy(
                    status = "Received",
                    received = true,
                    receivedQuantity = 4.0,
                    receivedBy = "Asha Nair",
                    receivedAt = 9_000
                )
            )
        )

        assertTrue(compose.boardShows(CLOSED_SECTION))
        assertTrue("what actually arrived belongs on the card", compose.boardShows("4 in"))
        // It has left the active list, and the active list says so.
        assertTrue(compose.boardShows(NOTHING_WAITING))
        // Received is no longer offered; Reopen is.
        assertFalse(compose.boardHas(rowActionLabel(PurchaseSheet.RECEIVE, rack.name)))
        assertTrue(compose.boardHas(rowActionLabel(PurchaseSheet.REOPEN, rack.name)))
    }

    @Test
    fun `reopening brings it back to Open with no received quantity`() {
        val received = rack.copy(
            status = "Received",
            received = true,
            receivedQuantity = 4.0,
            receivedBy = "Asha Nair",
            receivedAt = 9_000
        )
        val listener = showing(listOf(received))
        assertTrue(compose.boardShows(CLOSED_SECTION))

        // Reopening removes the four `rcv*` fields outright, so the row comes
        // back exactly as it was before anyone received it.
        listener(listOf(rack))

        assertFalse("the closed section goes with its last row", compose.boardShows(CLOSED_SECTION))
        assertFalse(
            "a reopened requirement must not still claim a received quantity",
            compose.boardShows("4 in")
        )
        assertTrue(compose.boardHas(rowActionLabel(PurchaseSheet.RECEIVE, rack.name)))
        assertFalse(compose.boardHas(rowActionLabel(PurchaseSheet.REOPEN, rack.name)))
    }

    @Test
    fun `removing takes it out of both sections and it does not come back`() {
        val listener = showing(listOf(rack))
        assertTrue(compose.boardShows("Sliding gate rack"))

        // A soft delete: the document survives so a PWA device cannot
        // resurrect the row, and nobody sees it again.
        listener(listOf(rack.copy(deleted = true)))

        assertFalse(compose.boardShows("Sliding gate rack"))
        assertFalse(compose.boardShows(CLOSED_SECTION))
        assertTrue(compose.boardShows(NOTHING_WAITING))
    }
}
