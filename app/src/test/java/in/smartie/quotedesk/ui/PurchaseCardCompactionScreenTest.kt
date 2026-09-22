package `in`.smartie.quotedesk.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.data.model.PurchaseRecord
import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.ui.components.NOTE_TAG
import `in`.smartie.quotedesk.ui.purchase.PurchaseCapabilities
import `in`.smartie.quotedesk.ui.purchase.PurchaseSheet
import `in`.smartie.quotedesk.ui.purchase.quantityLine
import `in`.smartie.quotedesk.ui.purchase.rowActionLabel
import `in`.smartie.quotedesk.ui.screens.PurchaseRow
import `in`.smartie.quotedesk.ui.theme.SmartieTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The card after C2 to C5: tighter, with the figure that matters set large,
 * the note in its own box, and its controls on one row.
 *
 * All at 360dp, because that is where the room runs out.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w360dp-h640dp")
class PurchaseCardCompactionScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val untouched = requirement(
        "pr_new",
        name = "Sliding gate rack",
        quantity = 10.0,
        note = "For the Kandivali site, second floor"
    )

    private val partly = untouched.copy(receivedQuantity = 4.0)

    private fun card(record: PurchaseRecord, viewer: Member = purchaseOwner) {
        compose.setContent {
            SmartieTheme {
                Box(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                    Box(Modifier.testTag(CARD_HOST_TAG)) {
                        PurchaseRow(
                            item = record,
                            capabilities = PurchaseCapabilities.forRecord(viewer, record)
                        )
                    }
                }
            }
        }
    }

    private fun bounds(sheet: PurchaseSheet, record: PurchaseRecord) =
        compose.onNodeWithContentDescription(rowActionLabel(sheet, record.name))
            .fetchSemanticsNode()
            .unclippedBounds()

    private fun actionTop(sheet: PurchaseSheet, record: PurchaseRecord) =
        bounds(sheet, record).top

    @Test
    fun `an Owner's four controls on an untouched card share one row`() {
        card(untouched)

        // Received, Edit, Urgency and Remove. Compact enough that 360dp holds
        // them side by side, which is what C5 asked for.
        val tops = listOf(
            PurchaseSheet.RECEIVE,
            PurchaseSheet.EDIT,
            PurchaseSheet.URGENCY,
            PurchaseSheet.REMOVE
        ).map { actionTop(it, untouched) }

        assertEquals("four controls, one row", 1, tops.distinct().size)
    }

    @Test
    fun `Received leads the row for a Manager as it does for an Owner`() {
        // The order is fixed so a card looks like a card for every role: only
        // what is on it differs, never where the shared controls sit.
        card(untouched, viewer = purchaseStaff)

        val received = bounds(PurchaseSheet.RECEIVE, untouched).left
        val edit = bounds(PurchaseSheet.EDIT, untouched).left
        assertTrue("Received comes before Edit, at $received against $edit", received < edit)
    }

    @Test
    fun `the quantity line is the thing set large, and is not cut off`() {
        card(partly)

        // Three figures on a 360dp card. Ellipsized, this reads as a shorter
        // and wrong sentence, so it wraps instead.
        val line = quantityLine(partly)
        assertEquals("10 required · 4 received · 6 remaining", line)
        compose.onNodeWithText(line).assertIsDisplayed()
    }

    @Test
    fun `the note sits in its own box, inside the card`() {
        card(untouched)

        val note = compose.onNodeWithTag(NOTE_TAG).fetchSemanticsNode()
        val card = compose.cardBounds()
        assertTrue("the note is on the card", note.unclippedBounds().height > 0f)
        assertTrue(
            "and inside it",
            note.unclippedBounds().bottom <= card.bottom &&
                note.unclippedBounds().top >= card.top
        )
    }

    @Test
    fun `and every control is still painted whole at a real size`() {
        // The compaction must not undo B1: smaller padding, same target.
        card(partly)

        compose.assertFooterPaintedInsideCard(
            minimumTarget = 44.dp,
            descriptions = listOf(
                PurchaseSheet.RECEIVE,
                PurchaseSheet.EDIT,
                PurchaseSheet.URGENCY,
                PurchaseSheet.SHORTFALL,
                PurchaseSheet.REMOVE
            ).map { rowActionLabel(it, partly.name) }
        )
    }
}
