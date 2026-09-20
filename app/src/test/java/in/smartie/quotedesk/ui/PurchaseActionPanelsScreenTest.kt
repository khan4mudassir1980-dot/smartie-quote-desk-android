package `in`.smartie.quotedesk.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.data.model.PurchaseRecord
import `in`.smartie.quotedesk.data.model.UrgencyV2
import `in`.smartie.quotedesk.domain.PurchaseWrite
import `in`.smartie.quotedesk.ui.purchase.CANCEL
import `in`.smartie.quotedesk.ui.purchase.CONFIRM_RECEIVE
import `in`.smartie.quotedesk.ui.purchase.CONFIRM_REMOVE
import `in`.smartie.quotedesk.ui.purchase.CONFIRM_REOPEN
import `in`.smartie.quotedesk.ui.purchase.MARK_RECEIVED
import `in`.smartie.quotedesk.ui.purchase.MarkReceivedPanel
import `in`.smartie.quotedesk.ui.purchase.OFFLINE
import `in`.smartie.quotedesk.ui.purchase.PurchaseActions
import `in`.smartie.quotedesk.ui.purchase.ALREADY_RECEIVED
import `in`.smartie.quotedesk.ui.purchase.RECEIVED_LABEL
import `in`.smartie.quotedesk.ui.purchase.REMAINING
import `in`.smartie.quotedesk.ui.purchase.TOTAL_REQUIRED
import `in`.smartie.quotedesk.ui.purchase.summaryLine
import `in`.smartie.quotedesk.ui.purchase.REMOVE
import `in`.smartie.quotedesk.ui.purchase.REMOVE_WARNING
import `in`.smartie.quotedesk.ui.purchase.REOPEN_WARNING
import `in`.smartie.quotedesk.ui.purchase.RemoveConfirmPanel
import `in`.smartie.quotedesk.ui.purchase.ReopenConfirmPanel
import `in`.smartie.quotedesk.ui.theme.SmartieTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Receiving, reopening and removing — the three that change what a
 * requirement *is*, and the three a confirmation stands in front of.
 *
 * Its own class: Robolectric's native-object registry is a fixed array per
 * JVM and a Compose composition consumes a great many entries, so the purchase
 * screen tests are several small classes rather than one large one.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w360dp-h640dp")
class PurchaseActionPanelsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val open = PurchaseRecord(
        id = "pr_one",
        name = "Sliding gate rack",
        quantity = 6.0,
        urgency = UrgencyV2.URGENT
    )

    private val received = open.copy(
        status = "Received",
        received = true,
        receivedQuantity = 6.0,
        receivedBy = "Asha Nair"
    )

    @Test
    fun `receiving is pre-filled with what is outstanding and sends what arrived`() {
        var quantity: Double? = null
        compose.setContent {
            SmartieTheme {
                MarkReceivedPanel(
                    record = open,
                    actions = PurchaseActions(onReceive = { _, q -> quantity = q })
                )
            }
        }

        // All three figures, because what is still to come is the number
        // somebody is standing in front of a delivery trying to work out.
        compose.onNodeWithContentDescription(summaryLine(TOTAL_REQUIRED, 6.0)).assertIsDisplayed()
        compose.onNodeWithContentDescription(summaryLine(ALREADY_RECEIVED, 0.0)).assertIsDisplayed()
        compose.onNodeWithContentDescription(summaryLine(REMAINING, 6.0)).assertIsDisplayed()

        compose.field(RECEIVED_LABEL).performTextClearance()
        compose.field(RECEIVED_LABEL).performTextInput("4")
        compose.onNodeWithContentDescription(CONFIRM_RECEIVE).performClick()

        // A part delivery is the ordinary case, so what arrived is asked for
        // rather than assumed from what was needed.
        assertEquals(4.0, quantity)
    }

    @Test
    fun `a partly received requirement counts what is left, and offers it`() {
        var quantity: Double? = null
        val partly = open.copy(quantity = 10.0, receivedQuantity = 4.0)
        compose.setContent {
            SmartieTheme {
                MarkReceivedPanel(
                    record = partly,
                    actions = PurchaseActions(onReceive = { _, q -> quantity = q })
                )
            }
        }

        compose.onNodeWithContentDescription(summaryLine(TOTAL_REQUIRED, 10.0)).assertIsDisplayed()
        compose.onNodeWithContentDescription(summaryLine(ALREADY_RECEIVED, 4.0)).assertIsDisplayed()
        compose.onNodeWithContentDescription(summaryLine(REMAINING, 6.0)).assertIsDisplayed()

        // The default is the rest of the order, which is what usually turns
        // up — and it is a default, not a fixed figure.
        compose.onNodeWithContentDescription(CONFIRM_RECEIVE).performClick()
        assertEquals(6.0, quantity)
    }

    @Test
    fun `more than is outstanding cannot be confirmed`() {
        var receives = 0
        val partly = open.copy(quantity = 10.0, receivedQuantity = 8.0)
        compose.setContent {
            SmartieTheme {
                MarkReceivedPanel(
                    record = partly,
                    actions = PurchaseActions(onReceive = { _, _ -> receives++ })
                )
            }
        }

        compose.field(RECEIVED_LABEL).performTextClearance()
        compose.field(RECEIVED_LABEL).performTextInput("5")

        // The planner's own sentence, so the panel cannot drift away from
        // what a write would actually come back with.
        compose.onNodeWithText(PurchaseWrite.moreThanRemaining(2.0)).assertIsDisplayed()
        compose.onNodeWithText(MARK_RECEIVED).assertIsNotEnabled()
        compose.onNodeWithContentDescription(CONFIRM_RECEIVE).performClick()

        assertEquals(0, receives)
    }

    @Test
    fun `nothing arriving cannot be confirmed`() {
        var receives = 0
        compose.setContent {
            SmartieTheme {
                MarkReceivedPanel(
                    record = open,
                    actions = PurchaseActions(onReceive = { _, _ -> receives++ })
                )
            }
        }

        compose.field(RECEIVED_LABEL).performTextClearance()
        compose.field(RECEIVED_LABEL).performTextInput("0")
        compose.onNodeWithText(MARK_RECEIVED).assertIsNotEnabled()
        compose.onNodeWithContentDescription(CONFIRM_RECEIVE).performClick()

        assertEquals(0, receives)
    }

    @Test
    fun `reopening says what it will clear, and names who had it`() {
        var reopens = 0
        compose.setContent {
            SmartieTheme {
                ReopenConfirmPanel(
                    record = received,
                    actions = PurchaseActions(onReopen = { reopens++ })
                )
            }
        }

        compose.onNodeWithText(REOPEN_WARNING).assertIsDisplayed()
        assertTrue(
            "the received quantity does not come back, so it has to say so",
            REOPEN_WARNING.contains("cleared")
        )
        compose.onNodeWithText("Received by Asha Nair").assertIsDisplayed()

        compose.onNodeWithContentDescription(CONFIRM_REOPEN).performClick()
        assertEquals(1, reopens)
    }

    @Test
    fun `removing says it cannot be brought back`() {
        var removes = 0
        compose.setContent {
            SmartieTheme {
                RemoveConfirmPanel(
                    record = open,
                    actions = PurchaseActions(onRemove = { removes++ })
                )
            }
        }

        compose.onNodeWithText(REMOVE_WARNING).assertIsDisplayed()
        assertTrue(REMOVE_WARNING.contains("no way to bring it back"))

        compose.onNodeWithContentDescription(CONFIRM_REMOVE).performClick()
        assertEquals(1, removes)
    }

    @Test
    fun `Cancel closes the panel and writes nothing`() {
        var removes = 0
        var dismissed = 0
        compose.setContent {
            SmartieTheme {
                RemoveConfirmPanel(
                    record = open,
                    actions = PurchaseActions(
                        onRemove = { removes++ },
                        onDismiss = { dismissed++ }
                    )
                )
            }
        }

        compose.onNodeWithText(CANCEL).performClick()

        assertEquals(1, dismissed)
        assertEquals("cancelling a sheet must not reach the repository", 0, removes)
    }

    @Test
    fun `offline a removal says why and cannot be confirmed`() {
        var removes = 0
        compose.setContent {
            SmartieTheme {
                RemoveConfirmPanel(
                    record = open,
                    online = false,
                    actions = PurchaseActions(onRemove = { removes++ })
                )
            }
        }

        compose.onNodeWithText(OFFLINE).assertIsDisplayed()
        compose.onNodeWithText(REMOVE).assertIsNotEnabled()
        compose.onNodeWithContentDescription(CONFIRM_REMOVE).performClick()

        assertEquals(0, removes)
    }

    @Test
    fun `no panel offers to cancel a requirement`() {
        compose.setContent { SmartieTheme { RemoveConfirmPanel(record = open) } }
        // N4 has no Cancel action and no generic status setter. A `Cancelled`
        // requirement written by the PWA still reads correctly; nothing here
        // writes one, and nothing here offers to.
        for (word in listOf("Cancel requirement", "Cancelled", "Mark as cancelled")) {
            assertTrue(
                "no purchase panel may offer $word",
                compose.onAllNodesWithText(word, substring = true)
                    .fetchSemanticsNodes().isEmpty()
            )
        }
    }
}
