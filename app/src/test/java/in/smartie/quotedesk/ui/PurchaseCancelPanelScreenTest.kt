package `in`.smartie.quotedesk.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.ui.purchase.CANCEL_REQUIREMENT
import `in`.smartie.quotedesk.ui.purchase.CONFIRM_CANCEL
import `in`.smartie.quotedesk.ui.purchase.CancelConfirmPanel
import `in`.smartie.quotedesk.ui.purchase.KEEP_IT
import `in`.smartie.quotedesk.ui.purchase.OFFLINE
import `in`.smartie.quotedesk.ui.purchase.PurchaseActions
import `in`.smartie.quotedesk.ui.purchase.cancelQuestion
import `in`.smartie.quotedesk.ui.theme.SmartieTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The cancel confirm, in the advisor's words of 2026-10-05 and answered
 * "Cancel requirement" or "Keep it".
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w360dp-h640dp")
class PurchaseCancelPanelScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val needed = requirement("pr_one", name = "Sliding gate rack", quantity = 10.0)

    @Test
    fun `it asks in the advisor's words, word for word`() {
        compose.setContent { SmartieTheme { CancelConfirmPanel(record = needed) } }

        val expected = "Cancel the requirement for “Sliding gate rack”? It moves to history " +
            "and leaves the Open list. Nothing is marked as received."
        assertEquals(expected, cancelQuestion(needed.name))
        compose.onNodeWithText(expected).assertIsDisplayed()
        compose.onNodeWithText(CANCEL_REQUIREMENT).assertIsDisplayed()
        compose.onNodeWithText(KEEP_IT).assertIsDisplayed()
    }

    @Test
    fun `Keep it closes the panel and cancels nothing`() {
        var cancels = 0
        var dismissed = 0
        compose.setContent {
            SmartieTheme {
                CancelConfirmPanel(
                    record = needed,
                    actions = PurchaseActions(onCancel = { cancels++ }, onDismiss = { dismissed++ })
                )
            }
        }

        compose.onNodeWithContentDescription(KEEP_IT).performClick()

        assertEquals(1, dismissed)
        assertEquals(0, cancels)
    }

    @Test
    fun `Cancel requirement cancels this one`() {
        var cancelled = emptyList<String>()
        compose.setContent {
            SmartieTheme {
                CancelConfirmPanel(
                    record = needed,
                    actions = PurchaseActions(onCancel = { cancelled = cancelled + it.id })
                )
            }
        }

        compose.onNodeWithContentDescription(CONFIRM_CANCEL).performClick()

        assertEquals(listOf("pr_one"), cancelled)
    }

    @Test
    fun `offline it says why and cannot be confirmed`() {
        var cancels = 0
        compose.setContent {
            SmartieTheme {
                CancelConfirmPanel(
                    record = needed,
                    online = false,
                    actions = PurchaseActions(onCancel = { cancels++ })
                )
            }
        }

        compose.onNodeWithText(OFFLINE).assertIsDisplayed()
        compose.onNodeWithContentDescription(CONFIRM_CANCEL).assertIsNotEnabled()
        compose.onNodeWithContentDescription(CONFIRM_CANCEL).performClick()
        assertEquals(0, cancels)
    }
}
