package `in`.smartie.quotedesk.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.data.mapping.Keys
import `in`.smartie.quotedesk.data.model.ProductRecord
import `in`.smartie.quotedesk.ui.stock.AddStockPanel
import `in`.smartie.quotedesk.ui.stock.EditStockPanel
import `in`.smartie.quotedesk.ui.stock.FROM_PRODUCTS
import `in`.smartie.quotedesk.ui.theme.SmartieTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The smallest phone the app supports, where the sheets are most likely to
 * lose their buttons off the bottom.
 *
 * A Material dialog caps its own height and clips what does not fit rather
 * than scrolling it, so a body sized for a tall phone loses its actions on a
 * short one. The bodies take a share of the window instead, which is what
 * these assert — at 640dp rather than the 915dp the other classes use.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w360dp-h640dp")
class StockSmallPhoneScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val catalogue = (1..120).map { number ->
        ProductRecord(
            documentId = Keys.productDocId("gateMotors", "SIE$number"),
            key = Keys.productKey("gateMotors", "SIE$number"),
            group = "gateMotors",
            seedModel = "SIE$number",
            model = "SIE$number",
            name = "Gate motor $number"
        )
    }

    @Test
    fun `Add stock keeps its actions on screen on a small phone`() {
        compose.setContent {
            SmartieTheme {
                AddStockPanel(
                    products = catalogue,
                    online = true,
                    onAddProduct = { _, _, _, _ -> },
                    onAddManual = { _, _, _, _, _, _ -> },
                    onCancel = {}
                )
            }
        }
        compose.onNodeWithText(FROM_PRODUCTS).performClick()
        compose.onNodeWithText("Back").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    fun `Edit keeps Save and Cancel on screen on a small phone`() {
        val motor = stockRecord("SIE1000", "Sliding gate motor", quantity = 9.0, reorder = 2.0)
        compose.setContent {
            SmartieTheme {
                EditStockPanel(
                    record = motor,
                    capabilities = adminCaps,
                    online = true,
                    onSave = { _, _, _ -> },
                    onStopTracking = {},
                    onCancel = {}
                )
            }
        }
        compose.onNodeWithText("Save").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
    }
}
