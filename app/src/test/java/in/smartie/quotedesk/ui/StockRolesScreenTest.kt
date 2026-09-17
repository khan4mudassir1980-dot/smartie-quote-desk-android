package `in`.smartie.quotedesk.ui

import android.app.Application
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.data.mapping.Keys
import `in`.smartie.quotedesk.data.model.ProductRecord
import `in`.smartie.quotedesk.ui.stock.AddStockPanel
import `in`.smartie.quotedesk.ui.stock.EditStockPanel
import `in`.smartie.quotedesk.ui.theme.SmartieTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What each role is offered, and what Add stock does.
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
        for (control in listOf("Done", "Edit", "Pin", "Add stock", "Clear", "+", "−")) {
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
                EditStockPanel(motor, staffCaps, online = true, onSave = { _, _, _ -> }, onStopTracking = {}, onCancel = {})
            }
        }
        compose.onNodeWithText("Only an Owner or Administrator can set an exact quantity.")
            .assertExists()
        assertTrue(compose.onAllNodesWithText("Exact quantity").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithContentDescription("Reorder level").assertExists()
        // Stop tracking is an Administrator action.
        assertTrue(compose.onAllNodesWithText("Stop tracking").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `an Administrator gets the exact quantity field and Stop tracking`() {
        compose.setContent {
            SmartieTheme {
                EditStockPanel(motor, adminCaps, online = true, onSave = { _, _, _ -> }, onStopTracking = {}, onCancel = {})
            }
        }
        compose.onNodeWithContentDescription("Exact quantity").assertExists()
        compose.onNodeWithContentDescription("Stop tracking this item").assertExists()
    }

    @Test
    fun `saving an edit passes the stored numbers straight through`() {
        var edited: Triple<Double, Double, String>? = null
        compose.setContent {
            SmartieTheme {
                EditStockPanel(
                    motor, adminCaps, online = true,
                    onSave = { q, r, n -> edited = Triple(q, r, n) },
                    onStopTracking = {}, onCancel = {}
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
                EditStockPanel(motor, adminCaps, online = false, onSave = { _, _, _ -> }, onStopTracking = {}, onCancel = {})
            }
        }
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Internet required to change stock").assertExists()
    }

    @Test
    fun `Add stock offers the catalogue, keyed by immutable identity`() {
        val renamed = ProductRecord(
            documentId = Keys.productDocId("gateMotors", "SIE9000"),
            key = Keys.productKey("gateMotors", "SIE9000"),
            group = "gateMotors",
            seedModel = "SIE9000",
            // Renamed since seeding: the stock key must not follow.
            model = "SIE-9000 PRO",
            name = "Barrier arm"
        )
        var added: String? = null
        compose.setContent {
            SmartieTheme {
                AddStockPanel(
                    products = listOf(renamed),
                    online = true,
                    onAddProduct = { p, _, _, _ -> added = p.stockKey },
                    onAddManual = { _, _, _, _, _, _ -> },
                    onCancel = {}
                )
            }
        }
        // The panel scrolls, and the catalogue sits below the quantity fields,
        // so the row has to be brought into view before it can be clicked.
        compose.onNodeWithText("Barrier arm").performScrollTo().performClick()
        assertEquals("gateMotors|SIE9000", added)
    }

    @Test
    fun `Add item stays disabled until a manual item is named`() {
        compose.setContent {
            SmartieTheme {
                AddStockPanel(
                    products = emptyList(),
                    online = true,
                    onAddProduct = { _, _, _, _ -> },
                    onAddManual = { _, _, _, _, _, _ -> },
                    onCancel = {}
                )
            }
        }
        compose.onNodeWithText("Add item").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Manual model or code").assertExists()
        compose.onNodeWithContentDescription("Manual item name").assertExists()
    }
}
