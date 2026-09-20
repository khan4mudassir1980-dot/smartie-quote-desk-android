package `in`.smartie.quotedesk.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.data.model.PurchaseRecord
import `in`.smartie.quotedesk.data.model.UrgencyV2
import `in`.smartie.quotedesk.ui.purchase.CANCEL
import `in`.smartie.quotedesk.ui.purchase.CONFIRM_SHORTFALL
import `in`.smartie.quotedesk.ui.purchase.CloseShortfallPanel
import `in`.smartie.quotedesk.ui.purchase.OFFLINE
import `in`.smartie.quotedesk.ui.purchase.PurchaseActions
import `in`.smartie.quotedesk.ui.purchase.closeWithLabel
import `in`.smartie.quotedesk.ui.purchase.shortfallWarning
import `in`.smartie.quotedesk.ui.theme.SmartieTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What somebody reads before they write off a delivery that is not coming.
 *
 * This is the one control in the tab that **changes a stored quantity and
 * loses the difference**, so the wording is the feature: both figures, and
 * the one being written off, in a sentence rather than in a number beside a
 * word. The button names the figure too, because "Confirm" on its own is a
 * button people press twice trying to work out what it meant.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w360dp-h640dp")
class PurchaseShortfallPanelScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val partly = PurchaseRecord(
        id = "pr_one",
        name = "Sliding gate rack",
        quantity = 10.0,
        urgency = UrgencyV2.URGENT,
        receivedQuantity = 7.0,
        receivedBy = "Sam"
    )

    @Test
    fun `it says what the quantity becomes and what is written off`() {
        compose.setContent { SmartieTheme { CloseShortfallPanel(record = partly) } }

        compose.onNodeWithText(
            "Required quantity will change from 10 to 7 and the requirement will be " +
                "closed. The remaining 3 will be written off."
        ).assertIsDisplayed()
        // And the constant the panel actually uses agrees with that sentence,
        // so neither can be corrected without the other.
        assertEquals(
            "Required quantity will change from 10 to 7 and the requirement will be " +
                "closed. The remaining 3 will be written off.",
            shortfallWarning(partly)
        )
    }

    @Test
    fun `the button carries the figure it will write`() {
        compose.setContent { SmartieTheme { CloseShortfallPanel(record = partly) } }

        compose.onNodeWithText("Close with 7 received").assertIsDisplayed()
        assertEquals("Close with 7 received", closeWithLabel(partly))
    }

    @Test
    fun `confirming asks for the record and nothing else`() {
        // There is no quantity field, here or in the action: the new total is
        // the stored receipt, decided inside the transaction.
        var closed: PurchaseRecord? = null
        compose.setContent {
            SmartieTheme {
                CloseShortfallPanel(
                    record = partly,
                    actions = PurchaseActions(onCloseShortfall = { closed = it })
                )
            }
        }

        compose.onNodeWithContentDescription(CONFIRM_SHORTFALL).performClick()

        assertEquals(partly, closed)
    }

    @Test
    fun `cancelling writes nothing`() {
        var closed = 0
        var dismissed = 0
        compose.setContent {
            SmartieTheme {
                CloseShortfallPanel(
                    record = partly,
                    actions = PurchaseActions(
                        onCloseShortfall = { closed++ },
                        onDismiss = { dismissed++ }
                    )
                )
            }
        }

        compose.onNodeWithText(CANCEL).performClick()

        assertEquals(0, closed)
        assertEquals(1, dismissed)
    }

    @Test
    fun `offline it says why it cannot be used`() {
        compose.setContent { SmartieTheme { CloseShortfallPanel(record = partly, online = false) } }

        compose.onNodeWithText(OFFLINE).assertIsDisplayed()
        compose.onNodeWithText(closeWithLabel(partly)).assertIsNotEnabled()
    }

    @Test
    fun `a write in flight cannot be confirmed twice`() {
        var closed = 0
        compose.setContent {
            SmartieTheme {
                CloseShortfallPanel(
                    record = partly,
                    saving = true,
                    actions = PurchaseActions(onCloseShortfall = { closed++ })
                )
            }
        }

        compose.onNodeWithContentDescription(CONFIRM_SHORTFALL).performClick()

        assertEquals("a busy confirm is disabled, not merely ignored", 0, closed)
    }
}
