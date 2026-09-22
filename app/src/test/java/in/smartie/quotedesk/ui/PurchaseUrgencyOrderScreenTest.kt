package `in`.smartie.quotedesk.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.data.model.PurchaseRecord
import `in`.smartie.quotedesk.data.model.UrgencyV2
import `in`.smartie.quotedesk.domain.PurchaseBoard
import `in`.smartie.quotedesk.ui.purchase.PurchaseCapabilities
import `in`.smartie.quotedesk.ui.screens.PurchaseBoardScreen
import `in`.smartie.quotedesk.ui.theme.SmartieTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The order the Open list is read in: red, then yellow, then green.
 *
 * A Worker's capabilities on purpose — a Worker's cards carry no control row,
 * so more of them fit on one screen and every position compared here is a
 * position something was actually drawn at. What is being tested is the
 * order, and the order does not depend on who is looking.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w412dp-h915dp")
class PurchaseUrgencyOrderScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val staffOnly = PurchaseCapabilities.forMember(purchaseWorker)

    /** The board over a list the test can change, as the listener would. */
    private fun showing(initial: List<PurchaseRecord>): (List<PurchaseRecord>) -> Unit {
        var records by mutableStateOf(initial)
        compose.setContent {
            SmartieTheme {
                PurchaseBoardScreen(
                    active = PurchaseBoard.active(records),
                    received = PurchaseBoard.closed(records),
                    capabilities = staffOnly,
                    capabilitiesFor = capabilitiesFor(purchaseWorker)
                )
            }
        }
        return { next -> compose.runOnIdle { records = next } }
    }

    private fun topOf(name: String): Float =
        compose.onNodeWithText(name).fetchSemanticsNode().positionInRoot.y

    /** The names on the board, in the order they are drawn down the screen. */
    private fun order(vararg names: String): List<String> =
        names.sortedBy { topOf(it) }

    @Test
    fun `red is drawn above yellow, and yellow above green`() {
        showing(
            listOf(
                // The newest is the green one and the oldest is the red one,
                // so a board still ordered by the clock alone fails this.
                requirement("pr_g", name = "Green one", urgency = UrgencyV2.NORMAL, createdAt = 9_000),
                requirement("pr_r", name = "Red one", urgency = UrgencyV2.CRITICAL, createdAt = 1_000),
                requirement("pr_y", name = "Yellow one", urgency = UrgencyV2.URGENT, createdAt = 5_000)
            )
        )

        assertEquals(
            listOf("Red one", "Yellow one", "Green one"),
            order("Red one", "Yellow one", "Green one")
        )
    }

    @Test
    fun `inside one urgency the newest is drawn first`() {
        showing(
            listOf(
                requirement("pr_r1", name = "Red older", urgency = UrgencyV2.CRITICAL, createdAt = 1_000),
                requirement("pr_r2", name = "Red newer", urgency = UrgencyV2.CRITICAL, createdAt = 3_000),
                requirement("pr_y1", name = "Yellow older", urgency = UrgencyV2.URGENT, createdAt = 2_000),
                requirement("pr_y2", name = "Yellow newer", urgency = UrgencyV2.URGENT, createdAt = 6_000)
            )
        )

        assertEquals(
            listOf("Red newer", "Red older", "Yellow newer", "Yellow older"),
            order("Red newer", "Red older", "Yellow newer", "Yellow older")
        )
    }

    @Test
    fun `changing an urgency moves the row at once, with no restart`() {
        val green = requirement(
            "pr_move",
            name = "Moving one",
            urgency = UrgencyV2.NORMAL,
            createdAt = 1_000
        )
        val listener = showing(
            listOf(green, requirement("pr_y", name = "Yellow one", urgency = UrgencyV2.URGENT, createdAt = 5_000))
        )

        assertEquals(listOf("Yellow one", "Moving one"), order("Yellow one", "Moving one"))

        // What the `setUrgency` write puts on the wire, read back by the
        // listener. Nothing else changes — not the clock, not the id.
        listener(
            listOf(
                green.copy(urgency = UrgencyV2.CRITICAL),
                requirement("pr_y", name = "Yellow one", urgency = UrgencyV2.URGENT, createdAt = 5_000)
            )
        )

        assertEquals(
            "a requirement made very urgent belongs at the top immediately",
            listOf("Moving one", "Yellow one"),
            order("Yellow one", "Moving one")
        )
        compose.onNodeWithText("Very urgent").assertExists()
    }

    @Test
    fun `a partly received requirement keeps its place in its urgency group`() {
        showing(
            listOf(
                requirement("pr_g", name = "Green one", urgency = UrgencyV2.NORMAL, createdAt = 9_000),
                // Five of ten arrived: still open, still very urgent, and it
                // must not drop below a green one just because something came.
                requirement(
                    "pr_part",
                    name = "Part delivered",
                    urgency = UrgencyV2.CRITICAL,
                    createdAt = 1_000,
                    receivedQuantity = 5.0
                )
            )
        )

        assertEquals(
            listOf("Part delivered", "Green one"),
            order("Part delivered", "Green one")
        )
    }

    @Test
    fun `the colours and the wire values are untouched by the ordering`() {
        showing(
            listOf(
                requirement("pr_r", name = "Red one", urgency = UrgencyV2.CRITICAL),
                requirement("pr_y", name = "Yellow one", urgency = UrgencyV2.URGENT),
                requirement("pr_g", name = "Green one", urgency = UrgencyV2.NORMAL)
            )
        )

        // Each card still names its own urgency in the Owner's words, and the
        // wire values behind them have not moved.
        compose.onNodeWithText("Very urgent").assertExists()
        compose.onNodeWithText("Can wait 1-2 days").assertExists()
        compose.onNodeWithText("Needed, but not now").assertExists()
        assertEquals("critical", UrgencyV2.CRITICAL.wireValue)
        assertEquals("urgent", UrgencyV2.URGENT.wireValue)
        assertEquals("normal", UrgencyV2.NORMAL.wireValue)
    }
}
