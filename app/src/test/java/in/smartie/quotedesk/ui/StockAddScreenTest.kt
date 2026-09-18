package `in`.smartie.quotedesk.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.data.mapping.Keys
import `in`.smartie.quotedesk.data.model.ProductRecord
import `in`.smartie.quotedesk.ui.stock.ADD_ITEM
import `in`.smartie.quotedesk.ui.stock.AddStockPanel
import `in`.smartie.quotedesk.ui.stock.FROM_PRODUCTS
import `in`.smartie.quotedesk.ui.stock.KEEP_WHAT_IS_TYPED
import `in`.smartie.quotedesk.ui.stock.MANUAL_ITEM
import `in`.smartie.quotedesk.ui.theme.SmartieTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Add stock, in its two modes.
 *
 * The first staging pass found one panel showing the catalogue search, its
 * results and the manual-item fields at once, with the buttons below a list.
 * These drive the **panel**: a Compose `Dialog` runs its own recomposer, which
 * the Robolectric clock does not drive.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w412dp-h915dp")
class StockAddScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun product(seedModel: String, name: String, model: String = seedModel) =
        ProductRecord(
            documentId = Keys.productDocId("gateMotors", seedModel),
            key = Keys.productKey("gateMotors", seedModel),
            group = "gateMotors",
            seedModel = seedModel,
            model = model,
            name = name
        )

    private val catalogue = listOf(
        product("SIE1000", "Sliding gate motor 1000 kg"),
        // Renamed since seeding: the stock key must not follow the new model.
        product("SIE9000", "Barrier arm", model = "SIE-9000 PRO")
    )

    private var added: Triple<String, Double, Double>? = null
    private var manual: List<Any>? = null

    private fun show(products: List<ProductRecord> = catalogue, online: Boolean = true) {
        compose.setContent {
            SmartieTheme {
                AddStockPanel(
                    products = products,
                    online = online,
                    onAddProduct = { p, q, r, _ -> added = Triple(p.stockKey, q, r) },
                    onAddManual = { m, n, u, q, r, note -> manual = listOf(m, n, u, q, r, note) },
                    onCancel = {}
                )
            }
        }
    }

    @Test
    fun `the choice comes first, and no fields with it`() {
        show()
        compose.onNodeWithText("What are you adding?").assertExists()
        compose.onNodeWithContentDescription("Add stock from the product catalogue").assertExists()
        compose.onNodeWithContentDescription("Add a manual stock item").assertExists()
        for (field in listOf("Starting quantity", "Item name", "Find a product", "Unit")) {
            assertTrue(
                "$field must not be shown before a mode is chosen",
                compose.onAllNodesWithText(field).fetchSemanticsNodes().isEmpty()
            )
        }
    }

    @Test
    fun `From Products shows the catalogue and none of the manual fields`() {
        show()
        compose.onNodeWithText(FROM_PRODUCTS).performClick()
        compose.onNodeWithContentDescription("Find a catalogue product").assertExists()
        // Each result carries its model or code and its product name.
        compose.onNodeWithText("SIE1000").assertExists()
        compose.onNodeWithText("Sliding gate motor 1000 kg").assertExists()
        for (field in listOf("Item name", "Model or code")) {
            assertTrue(
                "$field belongs to Manual Item only",
                compose.onAllNodesWithText(field).fetchSemanticsNodes().isEmpty()
            )
        }
    }

    @Test
    fun `choosing a product reveals its fields and adds it on Add item`() {
        show()
        compose.onNodeWithText(FROM_PRODUCTS).performClick()
        // Nothing to fill in until a product is picked.
        assertTrue(
            compose.onAllNodesWithText("Starting quantity").fetchSemanticsNodes().isEmpty()
        )

        compose.onNodeWithContentDescription("Choose SIE-9000 PRO").performScrollTo().performClick()

        // The choice is unmistakable, and only now do the fields appear.
        compose.onNodeWithText("Selected").assertExists()
        compose.onNodeWithContentDescription("Change the chosen product").assertExists()
        compose.onNodeWithContentDescription("Starting quantity").performScrollTo().assertExists()

        compose.field("Starting quantity").performTextReplacement("6")
        compose.onNodeWithText(ADD_ITEM).performClick()
        // Keyed by immutable identity, never by the renamed display model.
        assertEquals(Triple("gateMotors|SIE9000", 6.0, 0.0), added)
    }

    @Test
    fun `the buttons stay on screen however long the catalogue is`() {
        show(products = (1..200).map { product("SIE$it", "Gate motor $it") })
        compose.onNodeWithText(FROM_PRODUCTS).performClick()
        // The results list is bounded and scrolls inside itself, so Back and
        // Cancel are never pushed below the fold.
        compose.onNodeWithText("Back").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    fun `Manual Item shows its own fields and no catalogue results`() {
        show()
        compose.onNodeWithText(MANUAL_ITEM).performClick()
        compose.onNodeWithContentDescription("Manual model or code").assertExists()
        compose.onNodeWithContentDescription("Manual item name").assertExists()
        compose.onNodeWithContentDescription("Unit").assertExists()
        compose.onNodeWithContentDescription("Starting quantity").performScrollTo().assertExists()
        for (catalogueText in listOf("Sliding gate motor 1000 kg", "Find a product", "SIE1000")) {
            assertTrue(
                "$catalogueText belongs to From Products only",
                compose.onAllNodesWithText(catalogueText).fetchSemanticsNodes().isEmpty()
            )
        }
    }

    @Test
    fun `Add item stays disabled until a manual item is named`() {
        show()
        compose.onNodeWithText(MANUAL_ITEM).performClick()
        compose.onNodeWithText(ADD_ITEM).assertIsNotEnabled()
        compose.field("Manual item name").performTextInput("Anchor bolt")
        compose.onNodeWithText(ADD_ITEM).performClick()
        assertEquals(listOf("", "Anchor bolt", "each", 0.0, 0.0, ""), manual)
    }

    @Test
    fun `a typed draft survives going back to the choice and in again`() {
        show()
        compose.onNodeWithText(MANUAL_ITEM).performClick()
        compose.field("Manual item name").performTextInput("Anchor bolt")
        compose.onNodeWithContentDescription("Note").performScrollTo()
        compose.field("Note").performTextInput("Top shelf")
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText(MANUAL_ITEM).performClick()
        compose.onNodeWithText("Anchor bolt").assertExists()
        compose.onNodeWithText("Top shelf").performScrollTo().assertExists()
    }

    @Test
    fun `neither back nor a tap outside closes a sheet holding typed values`() {
        // The properties are the mechanism, so they are what is asserted:
        // a Compose dialog window cannot be driven by the Robolectric clock.
        assertFalse(KEEP_WHAT_IS_TYPED.dismissOnBackPress)
        assertFalse(KEEP_WHAT_IS_TYPED.dismissOnClickOutside)
    }
}
