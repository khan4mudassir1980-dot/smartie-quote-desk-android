package `in`.smartie.quotedesk.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.ui.stock.EditStockPanel
import `in`.smartie.quotedesk.ui.stock.OFFLINE_LABEL
import `in`.smartie.quotedesk.ui.stock.REMOVE_FROM_STOCK
import `in`.smartie.quotedesk.ui.theme.SmartieTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What each role is offered, and what Edit does for them.
 *
 * These drive the dialog **panels** rather than the dialogs. A Compose
 * `Dialog` opens its own window with its own recomposer, which the Robolectric
 * test clock does not drive, so `waitForIdle` spins until Espresso gives up
 * whatever the content is. The panels are the whole body, actions included;
 * only the AlertDialog wrapper is untested, and it holds no logic.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w412dp-h915dp")
class StockRolesScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val motor = stockRecord("SIE1000", "Sliding gate motor", quantity = 9.0, reorder = 2.0)

    @Test
    fun `a Worker sees stock and names but no control at all`() {
        compose.showStock(capabilities = workerCaps)
        compose.onNodeWithText("Sliding gate motor").assertExists()
        compose.onNodeWithText("9 each").assertExists()
        for (control in listOf(
            "Done", "Edit", "Pin", "History", "Add stock", "Clear", "+", "−"
        )) {
            assertTrue(
                "a Worker must not see $control",
                compose.onAllNodesWithText(control).fetchSemanticsNodes().isEmpty()
            )
        }
    }

    @Test
    fun `Staff get Edit but no exact quantity field`() {
        compose.setContent {
            SmartieTheme {
                EditStockPanel(motor, staffCaps, online = true, onSave = { _, _, _ -> }, onRemove = {}, onCancel = {})
            }
        }
        compose.onNodeWithText("Only an Owner or Administrator can set an exact quantity.")
            .assertExists()
        assertTrue(compose.onAllNodesWithText("Exact quantity").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithContentDescription("Reorder level").assertExists()
        // Removing an item is an Owner-and-Administrator action, as
        // stopping tracking was. The permission did not move.
        assertTrue(
            compose.onAllNodesWithText(REMOVE_FROM_STOCK).fetchSemanticsNodes().isEmpty()
        )
    }

    @Test
    fun `an Administrator gets the exact quantity field and Remove from stock`() {
        var removed = 0
        compose.setContent {
            SmartieTheme {
                EditStockPanel(
                    motor, adminCaps, online = true,
                    onSave = { _, _, _ -> }, onRemove = { removed++ }, onCancel = {}
                )
            }
        }
        compose.onNodeWithContentDescription("Exact quantity").assertExists()

        // Displayed, not merely present: the zero-width Photo control passed
        // an existence check while being invisible and unreachable.
        val remove = compose.onNodeWithContentDescription(REMOVE_FROM_STOCK)
        remove.assertIsDisplayed()

        // And a finger's worth of it, however small it looks.
        val bounds = remove.getUnclippedBoundsInRoot()
        assertTrue("width was ${bounds.width}", bounds.width >= 48.dp)
        assertTrue("height was ${bounds.height}", bounds.height >= 48.dp)

        remove.performClick()
        assertEquals("the sheet asks; it does not remove", 1, removed)
    }

    @Test
    fun `offline the Administrator cannot reach Remove from stock`() {
        var removed = 0
        compose.setContent {
            SmartieTheme {
                EditStockPanel(
                    motor, adminCaps, online = false,
                    onSave = { _, _, _ -> }, onRemove = { removed++ }, onCancel = {}
                )
            }
        }
        // Still there, still readable, and it does nothing — removing needs a
        // transaction, and there is no offline queue for one.
        assertTrue(
            "the control says why it does nothing",
            compose.onAllNodesWithContentDescription(OFFLINE_LABEL)
                .fetchSemanticsNodes().isNotEmpty()
        )
        compose.onNodeWithText(REMOVE_FROM_STOCK).performClick()
        assertEquals(0, removed)
    }

    @Test
    fun `saving an edit passes the stored numbers straight through`() {
        var edited: Triple<Double, Double, String>? = null
        compose.setContent {
            SmartieTheme {
                EditStockPanel(
                    motor, adminCaps, online = true,
                    onSave = { q, r, n -> edited = Triple(q, r, n) },
                    onRemove = {}, onCancel = {}
                )
            }
        }
        compose.onNodeWithText("Save").performClick()
        // Untouched fields must arrive unchanged, so an edit nobody altered
        // reaches the repository as the no-op it is.
        assertEquals(Triple(9.0, 2.0, ""), edited)
    }

    @Test
    fun `offline the edit panel cannot be saved and says why`() {
        compose.setContent {
            SmartieTheme {
                EditStockPanel(motor, adminCaps, online = false, onSave = { _, _, _ -> }, onRemove = {}, onCancel = {})
            }
        }
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Internet required to change stock").assertExists()
    }
}
