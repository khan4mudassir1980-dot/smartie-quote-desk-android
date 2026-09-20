package `in`.smartie.quotedesk.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.data.model.PurchaseRecord
import `in`.smartie.quotedesk.data.model.UrgencyV2
import `in`.smartie.quotedesk.ui.components.ListRow
import `in`.smartie.quotedesk.ui.components.NOTE_TAG
import `in`.smartie.quotedesk.ui.purchase.PurchaseCapabilities
import `in`.smartie.quotedesk.ui.purchase.PurchaseSheet
import `in`.smartie.quotedesk.ui.purchase.rowActionLabel
import `in`.smartie.quotedesk.ui.purchase.quantityLine
import `in`.smartie.quotedesk.ui.screens.PurchaseRow
import `in`.smartie.quotedesk.ui.theme.SmartieTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Where a requirement's controls sit relative to its information.
 *
 * **The test the first staging pass needed and did not have.** Every other
 * purchase screen test asserts that a control `assertIsDisplayed()` at 48dp —
 * and all of them passed while the controls were drawn *on top of* the name,
 * the quantity, the note and the author line on a real phone.
 * `assertIsDisplayed()` reports whether a node's bounds are inside the window;
 * it says nothing about what is painted over it. So these compare **bounds
 * against bounds**: nothing in the footer may begin above where the
 * information ends.
 *
 * The cause was `SmartieCard` placing its content in a `Box`, which stacks
 * its children. At 360dp, the narrowest phone the app supports, with the
 * longest text a card can hold.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w360dp-h640dp")
class PurchaseCardLayoutScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private companion object {
        const val CARD_HOST = "card-host"

        /** Floats from a layout pass do not land on exact integers. */
        const val EPSILON = 0.5f

        const val LONG_NAME =
            "Sliding gate rack 1 m galvanised, heavy duty, for the Kandivali site"

        const val THREE_LINE_NOTE =
            "Two for the Andheri site and one spare for the yard. Ask Ravi before " +
                "ordering because the last batch came in the wrong finish and had " +
                "to go back to the supplier twice."
    }

    private val admin = PurchaseCapabilities.forMember(purchaseAdmin)

    private fun card(record: PurchaseRecord) {
        compose.setContent {
            SmartieTheme {
                Box(Modifier.testTag(CARD_HOST).fillMaxWidth().wrapContentHeight()) {
                    PurchaseRow(item = record, capabilities = admin)
                }
            }
        }
    }

    private fun boundsOfText(text: String) =
        compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot

    private fun boundsOfAction(sheet: PurchaseSheet, name: String) =
        compose.onNodeWithContentDescription(rowActionLabel(sheet, name))
            .fetchSemanticsNode().boundsInRoot

    private fun host() = compose.onNodeWithTag(CARD_HOST).fetchSemanticsNode().boundsInRoot

    /** The lowest edge of anything that describes the requirement. */
    private fun informationBottom(record: PurchaseRecord): Float = listOf(
        boundsOfText(record.name).bottom,
        boundsOfText(quantityLine(record)).bottom,
        compose.onNodeWithTag(NOTE_TAG).fetchSemanticsNode().boundsInRoot.bottom,
        boundsOfText(record.urgency.label).bottom
    ).maxOrNull() ?: 0f

    private val open = requirement(
        "pr_open",
        name = LONG_NAME,
        note = THREE_LINE_NOTE,
        urgency = UrgencyV2.CRITICAL
    )

    private val closed = requirement(
        "pr_done",
        name = LONG_NAME,
        note = THREE_LINE_NOTE,
        urgency = UrgencyV2.NORMAL,
        received = true,
        receivedQuantity = 6.0
    )

    @Test
    fun `an open card puts its actions below its information, never over it`() {
        card(open)

        val information = informationBottom(open)
        for (sheet in listOf(PurchaseSheet.EDIT, PurchaseSheet.URGENCY, PurchaseSheet.RECEIVE)) {
            val action = boundsOfAction(sheet, open.name)
            assertTrue(
                "$sheet starts at ${action.top}, above the information ending at $information",
                action.top >= information - EPSILON
            )
        }
    }

    @Test
    fun `a received card puts its actions below its information too`() {
        card(closed)

        val information = informationBottom(closed)
        for (sheet in listOf(PurchaseSheet.REOPEN, PurchaseSheet.REMOVE)) {
            val action = boundsOfAction(sheet, closed.name)
            assertTrue(
                "$sheet starts at ${action.top}, above the information ending at $information",
                action.top >= information - EPSILON
            )
        }
    }

    @Test
    fun `the author line is above the actions, not under them`() {
        card(open)

        // "Added by Asha · 14 Nov 2023" is the last line of the information
        // block, so it is the one a stacked footer covered first.
        val author = compose.onNodeWithText("Added by", substring = true)
            .fetchSemanticsNode().boundsInRoot
        val action = boundsOfAction(PurchaseSheet.EDIT, open.name)

        assertTrue(
            "the author line ends at ${author.bottom}, the first action starts at ${action.top}",
            action.top >= author.bottom - EPSILON
        )
    }

    @Test
    fun `a three-line note is clear of the actions`() {
        card(open)

        val note = compose.onNodeWithTag(NOTE_TAG).fetchSemanticsNode().boundsInRoot
        val action = boundsOfAction(PurchaseSheet.EDIT, open.name)

        assertTrue("a note must not be painted under a button", action.top >= note.bottom - EPSILON)
        assertTrue("and it must have room to be three lines", note.height > 0f)
    }

    @Test
    fun `the card is tall enough to hold both sections`() {
        card(open)

        val host = host()
        val title = boundsOfText(open.name)
        val lowest = listOf(
            PurchaseSheet.EDIT,
            PurchaseSheet.URGENCY,
            PurchaseSheet.RECEIVE,
            PurchaseSheet.REMOVE
        ).maxOf { boundsOfAction(it, open.name).bottom }

        // Both ends inside the card: the information starts inside it and the
        // last action finishes inside it. A stacked layout failed this because
        // `IntrinsicSize.Min` sized the card to the taller child, not the sum.
        assertTrue("the title starts above the card", title.top >= host.top - EPSILON)
        assertTrue(
            "the last action ends at $lowest, past the card ending at ${host.bottom}",
            lowest <= host.bottom + EPSILON
        )
        assertTrue(host.height >= (lowest - title.top) - EPSILON)
    }

    @Test
    fun `the actions wrap at 360dp rather than clipping`() {
        card(open)

        val host = host()
        for (sheet in listOf(
            PurchaseSheet.EDIT,
            PurchaseSheet.URGENCY,
            PurchaseSheet.RECEIVE,
            PurchaseSheet.REMOVE
        )) {
            val label = rowActionLabel(sheet, open.name)
            compose.onNodeWithContentDescription(label)
                .assertIsDisplayed()
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
            // Nothing runs off the side: a `Row` would have measured the
            // overflow at zero and clipped it while leaving it in the tree.
            assertTrue(
                "$sheet runs past the card's right edge",
                boundsOfAction(sheet, open.name).right <= host.right + EPSILON
            )
        }
    }

    @Test
    fun `a card with no footer gains no space for one`() {
        // The three existing callers — Team, More and Quotations — pass no
        // footer, and must lay out exactly as they did before the slot
        // existed. Null composes nothing, so there is no gap under the tags.
        compose.setContent {
            SmartieTheme {
                Box(Modifier.testTag(CARD_HOST).fillMaxWidth().wrapContentHeight()) {
                    ListRow(title = "Plain row", secondary = "No footer here")
                }
            }
        }

        // In dp, not pixels: this one is a budget rather than a comparison,
        // so it must not move with the screen density.
        val hostBottom = compose.onNodeWithTag(CARD_HOST).getUnclippedBoundsInRoot().bottom
        val contentBottom = compose.onNodeWithText("No footer here")
            .getUnclippedBoundsInRoot().bottom
        val gap = (hostBottom - contentBottom).value

        // The card's own 15dp padding plus the 4dp empty tag box, and nothing
        // else. Eight more would mean every card in the app had grown.
        assertTrue("a footer-less card gained ${gap}dp under its content", gap <= 24f)
    }
}
