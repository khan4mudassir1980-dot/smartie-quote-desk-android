package `in`.smartie.quotedesk.ui

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.data.model.PurchaseRecord
import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.ui.purchase.NOT_ORDERED_STATE
import `in`.smartie.quotedesk.ui.purchase.ORDER
import `in`.smartie.quotedesk.ui.purchase.ORDERED
import `in`.smartie.quotedesk.ui.purchase.ORDERED_STATE
import `in`.smartie.quotedesk.ui.purchase.PurchaseActions
import `in`.smartie.quotedesk.ui.purchase.PurchaseCapabilities
import `in`.smartie.quotedesk.ui.purchase.orderToggleLabel
import `in`.smartie.quotedesk.ui.screens.PurchaseRow
import `in`.smartie.quotedesk.ui.screens.orderedLine
import `in`.smartie.quotedesk.ui.theme.SmartieTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * "Ordered" on a requirement's card — N5.10b, the Owner's design approved on
 * 2026-10-05.
 *
 * The tag is blue and everybody sees it, Staff included, with "Ordered by
 * <name> · <date>" under "Added by" — and **only while the status says
 * Ordered**, never from a stamp an undo or a reopen left behind. The one
 * Order / Not ordered button is an Owner's or an Administrator's, shows its
 * state, and says it to TalkBack.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w412dp-h915dp")
class PurchaseOrderedScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val needed = requirement("pr_one", name = "Sliding gate rack", quantity = 10.0)

    private val stamp: (PurchaseRecord) -> PurchaseRecord = {
        it.copy(orderedBy = "Asha", orderedByUid = purchaseAdmin.uid, orderedAt = 1_712_500_000_000L)
    }

    private val ordered = stamp(needed.copy(status = "Ordered"))

    private fun card(record: PurchaseRecord, viewer: Member, actions: PurchaseActions = PurchaseActions()) {
        compose.setContent {
            SmartieTheme {
                PurchaseRow(
                    item = record,
                    capabilities = PurchaseCapabilities.forRecord(viewer, record),
                    actions = actions
                )
            }
        }
    }

    private fun shows(text: String, substring: Boolean = false): Boolean =
        compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    private fun offersToggle(record: PurchaseRecord): Boolean =
        compose.onAllNodesWithContentDescription(orderToggleLabel(record.name))
            .fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `Staff see the Ordered tag and who ordered it, and no toggle`() {
        card(ordered, purchaseWorker)

        val line = orderedLine(ordered)
        assertNotNull(line)
        assertTrue("the line names who and when: $line", line!!.startsWith("Ordered by Asha · "))
        compose.onNodeWithText(line).assertIsDisplayed()
        // The tag carries the same words for TalkBack.
        compose.onNodeWithContentDescription(line).assertIsDisplayed()
        assertTrue(!offersToggle(ordered))
    }

    @Test
    fun `a Manager sees the same, and no toggle either`() {
        card(ordered, purchaseStaff)

        compose.onNodeWithText(orderedLine(ordered)!!).assertIsDisplayed()
        assertTrue(!offersToggle(ordered))
    }

    @Test
    fun `a Needed card carrying an Ordered stamp left behind shows neither tag nor line`() {
        val leftover = stamp(needed)
        card(leftover, purchaseWorker)

        assertNull(orderedLine(leftover))
        assertTrue("no Ordered tag", !shows(ORDERED))
        assertTrue("no Ordered by line", !shows("Ordered by", substring = true))
    }

    @Test
    fun `nor does a Received one`() {
        val received = stamp(
            requirement("pr_done", name = "Sliding gate rack", received = true, receivedQuantity = 10.0)
        )
        card(received, purchaseWorker)

        assertTrue(!shows(ORDERED))
        assertTrue(!shows("Ordered by", substring = true))
    }

    @Test
    fun `an old V8C4 Ordered row with no stamp shows the tag alone`() {
        val v8c4 = needed.copy(status = "Ordered")
        card(v8c4, purchaseWorker)

        assertNull(orderedLine(v8c4))
        compose.onNodeWithContentDescription(ORDERED).assertIsDisplayed()
        assertTrue(!shows("Ordered by", substring = true))
    }

    @Test
    fun `on a Needed card the Administrator's toggle is off, says so, and a tap asks to order`() {
        var tapped: PurchaseRecord? = null
        card(needed, purchaseAdmin, PurchaseActions(onToggleOrdered = { tapped = it }))

        compose.onNodeWithContentDescription(orderToggleLabel(needed.name))
            .assertIsNotSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, NOT_ORDERED_STATE))
            .performClick()

        assertTrue(shows(ORDER))
        assertEquals(needed, tapped)
    }

    @Test
    fun `on an Ordered card it is on, says Ordered, and a tap asks to take it back`() {
        var tapped: PurchaseRecord? = null
        card(ordered, purchaseOwner, PurchaseActions(onToggleOrdered = { tapped = it }))

        compose.onNodeWithContentDescription(orderToggleLabel(ordered.name))
            .assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, ORDERED_STATE))
            .performClick()

        assertEquals(ordered, tapped)
    }
}
