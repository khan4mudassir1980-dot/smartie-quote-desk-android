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
import `in`.smartie.quotedesk.ui.stock.CONFIRM_REMOVE
import `in`.smartie.quotedesk.ui.stock.REMOVE_FROM_STOCK
import `in`.smartie.quotedesk.ui.stock.REMOVE_OFFLINE
import `in`.smartie.quotedesk.ui.stock.REMOVE_WARNING
import `in`.smartie.quotedesk.ui.stock.StockRemovalActions
import `in`.smartie.quotedesk.ui.stock.StockRemoveConfirmPanel
import `in`.smartie.quotedesk.ui.theme.SmartieTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The confirmation, which is the last thing between an item and its deletion.
 *
 * The panel rather than the dialog, for the reason in `StockScreenFixtures`:
 * a Compose `Dialog` has its own recomposer that the test clock does not
 * drive. The wrapper holds no logic.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w412dp-h915dp")
class StockRemovalScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(
        online: Boolean = true,
        saving: Boolean = false,
        actions: StockRemovalActions = StockRemovalActions()
    ) {
        compose.setContent {
            SmartieTheme {
                StockRemoveConfirmPanel(online = online, saving = saving, actions = actions)
            }
        }
    }

    @Test
    fun `it says exactly what is lost`() {
        show()
        // Word for word. "Remove" on its own does not tell somebody their
        // photograph is about to go with it.
        compose.onNodeWithText(REMOVE_WARNING).assertIsDisplayed()
        assertEquals(
            "Its quantity, note and photo will be permanently deleted. " +
                "A basic record will remain in stopped-item history.",
            REMOVE_WARNING
        )
    }

    @Test
    fun `Cancel writes nothing`() {
        var removed = 0
        var cancelled = 0
        show(actions = StockRemovalActions(onRemove = { removed++ }, onCancel = { cancelled++ }))

        compose.onNodeWithText("Cancel").performClick()

        assertEquals(1, cancelled)
        assertEquals("cancelling must not reach the repository", 0, removed)
    }

    @Test
    fun `confirming removes, once`() {
        var removed = 0
        show(actions = StockRemovalActions(onRemove = { removed++ }))

        compose.onNodeWithContentDescription(CONFIRM_REMOVE).performClick()

        assertEquals(1, removed)
    }

    @Test
    fun `offline it says why and cannot be confirmed`() {
        var removed = 0
        show(online = false, actions = StockRemovalActions(onRemove = { removed++ }))

        compose.onNodeWithText(REMOVE_OFFLINE).assertIsDisplayed()
        compose.onNodeWithText(REMOVE_FROM_STOCK).assertIsNotEnabled()
        compose.onNodeWithText(REMOVE_FROM_STOCK).performClick()
        assertEquals(0, removed)
    }

    @Test
    fun `a removal in flight cannot be started twice`() {
        var removed = 0
        show(saving = true, actions = StockRemovalActions(onRemove = { removed++ }))

        compose.onNodeWithText("Removing…").assertIsNotEnabled()
        compose.onNodeWithText("Removing…").performClick()

        assertEquals(0, removed)
        // Cancel goes too: the transaction is already on the wire.
        compose.onNodeWithText("Cancel").assertIsNotEnabled()
    }

    @Test
    fun `the confirmation never mentions restoring`() {
        show()
        // There is no Restore, by the Owner's decision. A confirmation that
        // hinted at one would make "permanently deleted" a lie.
        for (word in listOf("Restore", "Reactivate", "Undo", "Stop tracking")) {
            assertTrue(
                "the confirmation must not offer $word",
                compose.onAllNodesWithText(word, substring = true)
                    .fetchSemanticsNodes().isEmpty()
            )
        }
    }
}
