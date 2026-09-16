package `in`.smartie.quotedesk.ui

import android.app.Application
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.data.mapping.Keys
import `in`.smartie.quotedesk.data.model.ProductRecord
import `in`.smartie.quotedesk.domain.Catalogue
import `in`.smartie.quotedesk.domain.QuoteDraft
import `in`.smartie.quotedesk.ui.products.ProductsCatalogue
import `in`.smartie.quotedesk.ui.theme.SmartieTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
// A plain Application: SmartieApplication would need a real Firebase project.
@Config(application = Application::class, qualifiers = "w412dp-h915dp")
class ProductsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun product(
        seedModel: String,
        name: String = "",
        categoryId: String = "cat-sliding",
        client: Double? = 25900.0,
        kg: Double? = null,
    ) = ProductRecord(
        documentId = Keys.productDocId("gate", seedModel),
        key = Keys.productKey("gate", seedModel),
        group = "gate",
        seedModel = seedModel,
        model = seedModel,
        name = name,
        categoryId = categoryId,
        client = client,
        kg = kg,
        seeded = true,
    )

    private val motor = product("SIE1000", name = "Sliding gate motor 1000 kg", kg = 1000.0)
    private val unpriced = product("SIE600", name = "Sliding gate motor 600 kg", client = null)

    private fun render(
        products: List<ProductRecord> = listOf(motor, unpriced),
        pins: List<String> = emptyList(),
        query: String = "",
        openShelves: Set<String> = emptySet(),
        draft: QuoteDraft = QuoteDraft(),
        canPin: Boolean = false,
        canView: Boolean = true,
    ) {
        val view = Catalogue.build(products, emptyList(), pins, query)
        compose.setContent {
            SmartieTheme {
                ProductsCatalogue(
                    canViewProducts = canView,
                    view = view,
                    draft = draft,
                    query = query,
                    openShelves = openShelves,
                    pinnedKeys = pins,
                    canPin = canPin,
                )
            }
        }
    }

    @Test
    fun `a worker sees the guard and nothing else`() {
        render(canView = false)
        compose.onNodeWithText("Products and prices are not part of a Worker account.").assertExists()
        assertEquals(0, compose.onAllNodesWithText("Search").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("Product Categories").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("Sliding Gate Motors").fetchSemanticsNodes().size)
    }

    @Test
    fun `the shelves carry the PWA's twelve categories and open on demand`() {
        render()
        compose.onNodeWithText("Product Categories").assertExists()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Sliding Gate Motors"))
        compose.onNodeWithText("Sliding Gate Motors").assertExists()
        // Closed: a lazy list never composes a shelf's products.
        assertEquals(0, compose.onAllNodesWithText("SIE1000").fetchSemanticsNodes().size)
    }

    @Test
    fun `an open shelf shows its products`() {
        render(openShelves = setOf("cat-sliding"))
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("SIE1000"))
        compose.onNodeWithText("SIE1000").assertExists()
        compose.onNodeWithText("Sliding gate motor 1000 kg").assertExists()
    }

    @Test
    fun `a product with no price reads Price not set, never a rupee zero`() {
        // A priced line in the draft keeps the quote bar off zero, so the only
        // place a "₹0" could come from is the unpriced product itself.
        render(openShelves = setOf("cat-sliding"), draft = QuoteDraft().add(motor))
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Price not set"))
        compose.onNodeWithText("Price not set").assertExists()
        assertEquals(0, compose.onAllNodesWithText("₹0").fetchSemanticsNodes().size)
    }

    @Test
    fun `the pinned shelf leads, with its counter`() {
        render(pins = listOf(motor.key))
        compose.onNodeWithText("Pinned Products").assertExists()
        compose.onNodeWithText("1 / 15").assertExists()
        // A pinned product is composed straight away, unlike one on a shelf.
        compose.onNodeWithText("SIE1000").assertExists()
    }

    @Test
    fun `staff see the catalogue but no pin controls`() {
        render(pins = listOf(motor.key), canPin = false)
        compose.onNodeWithText("SIE1000").assertExists()
        assertEquals(0, compose.onAllNodesWithText("★ Pinned").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("☆ Pin").fetchSemanticsNodes().size)
    }

    @Test
    fun `an administrator can unpin from the pinned shelf`() {
        render(pins = listOf(motor.key), canPin = true)
        compose.onNodeWithText("★ Pinned").assertExists()
    }

    @Test
    fun `a search that matches nothing says so in the PWA's words`() {
        render(query = "nothing here")
        compose.onNodeWithText("No products found. Nothing matches “nothing here”.").assertExists()
    }

    @Test
    fun `a search reports how many it found`() {
        render(query = "sliding gate motor")
        compose.onNodeWithText("2 products matching “sliding gate motor”").assertExists()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("SIE1000"))
        compose.onNodeWithText("SIE1000").assertExists()
    }

    @Test
    fun `the quote bar is empty until something is added`() {
        render()
        compose.onNodeWithText("Empty").assertExists()
        compose.onNodeWithText("₹0").assertExists()
    }

    @Test
    fun `the quote bar totals what the draft holds`() {
        render(draft = QuoteDraft().add(motor, quantity = 2.0))
        compose.onNodeWithText("1 in the quotation").assertExists()
        compose.onNodeWithText("₹51,800").assertExists()
    }

    @Test
    fun `the quote bar says when a line still needs a rate`() {
        render(draft = QuoteDraft().add(unpriced))
        compose.onNodeWithText("₹0 · 1 needs a rate").assertExists()
    }
}
