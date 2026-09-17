package `in`.smartie.quotedesk.ui

import android.app.Application
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.data.mapping.Keys
import `in`.smartie.quotedesk.data.model.ProductRecord
import `in`.smartie.quotedesk.ui.stock.StockActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** What each role is offered, and what Add stock does. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w412dp-h915dp")
class StockRolesScreenTest {

    @get:Rule
    val compose = createComposeRule()

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
        compose.showStock(capabilities = staffCaps)
        compose.onNodeWithContentDescription("Edit Sliding gate motor").performClick()
        compose.onNodeWithText("Only an Owner or Administrator can set an exact quantity.")
            .assertExists()
        assertTrue(compose.onAllNodesWithText("Exact quantity").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithContentDescription("Reorder level").assertExists()
    }

    @Test
    fun `an Administrator gets the exact quantity field and Stop tracking`() {
        compose.showStock(capabilities = adminCaps)
        compose.onNodeWithContentDescription("Edit Sliding gate motor").performClick()
        compose.onNodeWithContentDescription("Exact quantity").assertExists()
        compose.onNodeWithContentDescription("Stop tracking this item").assertExists()
    }

    @Test
    fun `saving an edit passes the stored numbers straight through`() {
        var edited: Triple<Double, Double, String>? = null
        compose.showStock(
            capabilities = adminCaps,
            actions = StockActions(onEdit = { _, q, r, n -> edited = Triple(q, r, n) })
        )
        compose.onNodeWithContentDescription("Edit Sliding gate motor").performClick()
        compose.onNodeWithText("Save").performClick()
        // Untouched fields must arrive unchanged, so an edit nobody altered
        // reaches the repository as the no-op it is.
        assertEquals(Triple(9.0, 2.0, ""), edited)
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
        compose.showStock(
            products = listOf(renamed),
            actions = StockActions(onAddProduct = { p, _, _, _ -> added = p.stockKey })
        )
        compose.onNodeWithContentDescription("Add stock").performClick()
        compose.onNodeWithText("Barrier arm").performClick()
        assertEquals("gateMotors|SIE9000", added)
    }

    @Test
    fun `Add item stays disabled until a manual item is named`() {
        compose.showStock()
        compose.onNodeWithContentDescription("Add stock").performClick()
        compose.onNodeWithText("Add item").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Manual model or code").assertExists()
        compose.onNodeWithContentDescription("Manual item name").assertExists()
    }
}
