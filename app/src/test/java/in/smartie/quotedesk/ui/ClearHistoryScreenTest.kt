package `in`.smartie.quotedesk.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.ui.stock.CLEAR_HISTORY
import `in`.smartie.quotedesk.ui.stock.ClearHistoryConfirmPanel
import `in`.smartie.quotedesk.ui.stock.CONFIRM_CLEAR
import `in`.smartie.quotedesk.ui.stock.StockRemovalActions
import `in`.smartie.quotedesk.ui.stock.clearHistoryQuestion
import `in`.smartie.quotedesk.ui.theme.SmartieTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Emptying the history, and the count it names before it does. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w412dp-h915dp")
class ClearHistoryScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(
        count: Int = 3,
        saving: Boolean = false,
        actions: StockRemovalActions = StockRemovalActions()
    ) {
        compose.setContent {
            SmartieTheme {
                ClearHistoryConfirmPanel(count = count, saving = saving, actions = actions)
            }
        }
    }

    @Test
    fun `it names how many are about to go`() {
        show(count = 3)
        compose.onNodeWithText("Permanently clear 3 stopped-item history entries?")
            .assertIsDisplayed()
    }

    @Test
    fun `one entry is an entry, not entries`() {
        assertEquals("Permanently clear 1 stopped-item history entry?", clearHistoryQuestion(1))
        assertEquals("Permanently clear 0 stopped-item history entries?", clearHistoryQuestion(0))
    }

    @Test
    fun `Cancel deletes nothing`() {
        var cleared = 0
        var cancelled = 0
        show(
            actions = StockRemovalActions(
                onConfirmClear = { cleared++ },
                onCancel = { cancelled++ }
            )
        )

        compose.onNodeWithText("Cancel").performClick()

        assertEquals(1, cancelled)
        assertEquals("cancelling must not reach Firestore", 0, cleared)
    }

    @Test
    fun `confirming clears, once`() {
        var cleared = 0
        show(actions = StockRemovalActions(onConfirmClear = { cleared++ }))

        compose.onNodeWithContentDescription(CONFIRM_CLEAR).performClick()

        assertEquals(1, cleared)
    }

    @Test
    fun `a clear in flight cannot be started twice`() {
        var cleared = 0
        show(saving = true, actions = StockRemovalActions(onConfirmClear = { cleared++ }))

        compose.onNodeWithText("Clearing…").assertIsNotEnabled()
        compose.onNodeWithText("Clearing…").performClick()

        assertEquals(0, cleared)
    }

    @Test
    fun `the button says what it does`() {
        show()
        compose.onNodeWithText(CLEAR_HISTORY).assertIsDisplayed()
        assertEquals("Clear history", CLEAR_HISTORY)
    }
}
