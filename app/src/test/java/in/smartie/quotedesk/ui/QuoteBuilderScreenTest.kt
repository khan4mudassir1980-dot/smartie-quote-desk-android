package `in`.smartie.quotedesk.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.data.mapping.Keys
import `in`.smartie.quotedesk.data.model.ProductRecord
import `in`.smartie.quotedesk.domain.Catalogue
import `in`.smartie.quotedesk.domain.QuoteDraft
import `in`.smartie.quotedesk.ui.products.BACK_TO_PRODUCTS
import `in`.smartie.quotedesk.ui.products.BUILDER_CLEAR_KEY
import `in`.smartie.quotedesk.ui.products.BUILDER_HEADING
import `in`.smartie.quotedesk.ui.products.BUILDER_TAIL_KEY
import `in`.smartie.quotedesk.ui.products.CLEAR_LINES
import `in`.smartie.quotedesk.ui.products.moreOf
import `in`.smartie.quotedesk.ui.products.NOTHING_ON_IT
import `in`.smartie.quotedesk.ui.products.ProductsActions
import `in`.smartie.quotedesk.ui.products.ProductsCatalogue
import `in`.smartie.quotedesk.ui.products.QuoteBuilderPanel
import `in`.smartie.quotedesk.ui.products.QUOTE_BUILDER_TAG
import `in`.smartie.quotedesk.ui.products.RATE_NEEDED
import `in`.smartie.quotedesk.ui.theme.SmartieTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The builder panel: opening it, reading it, and getting back out.
 *
 * **It is a plain composable, not a sheet, and this file is the reason.** A
 * `ModalBottomSheet` is a Compose `Dialog`, which opens a recomposer
 * Robolectric's clock does not drive — the lesson N4 wrote down at
 * `PurchasePanels.kt:60-64`. Every assertion below would have had to be an
 * assertion about a view model instead.
 *
 * **Every assertion scrolls to its key first.** A `LazyColumn` never composes
 * an off-screen item, so an un-scrolled lookup cannot tell a missing control
 * from one below the fold — five CI cycles were lost to exactly that. The
 * absence checks scroll to [BUILDER_TAIL_KEY], whose only job is to be the end
 * of the list, and then also assert that a neighbouring node *was* found, so
 * "not there" can never quietly mean "nothing composed at all".
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w412dp-h915dp")
class QuoteBuilderScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun product(seedModel: String, client: Double? = 22200.0) = ProductRecord(
        documentId = Keys.productDocId("gate", seedModel),
        key = Keys.productKey("gate", seedModel),
        group = "gate",
        seedModel = seedModel,
        model = seedModel,
        categoryId = "cat-sliding",
        client = client
    )

    private val motor = product("SIE1000")
    private val unpriced = product("SIE600", client = null)

    private var changed: Pair<String, Double>? = null
    private var cleared = 0

    /** A draft holding one priced line, the way a catalogue tap leaves it. */
    private fun oneLine() = QuoteDraft(id = "qd_1").add(motor, quantity = 2.0, id = "ln_1")

    private fun render(draft: QuoteDraft, open: Boolean = true) {
        val view = Catalogue.build(listOf(motor, unpriced), emptyList(), emptyList(), "")
        compose.setContent {
            SmartieTheme {
                ProductsCatalogue(
                    canViewProducts = true,
                    view = view,
                    draft = draft,
                    actions = ProductsActions(
                        onChangeLineQuantity = { id, delta -> changed = id to delta },
                        onClearDraft = { cleared++ }
                    )
                )
            }
        }
        if (open) compose.onNodeWithText("View quote").performClick()
    }

    private fun scrollTo(key: String) =
        compose.onNodeWithTag(QUOTE_BUILDER_TAG).performScrollToKey(key)

    @Test
    fun `the quote bar opens the builder in place of the catalogue`() {
        render(oneLine(), open = false)
        // The catalogue is what is on screen until the bar is tapped.
        compose.onNodeWithText("Search").assertExists()
        assertEquals(0, compose.onAllNodesWithTag(QUOTE_BUILDER_TAG).fetchSemanticsNodes().size)

        compose.onNodeWithText("View quote").performClick()

        compose.onNodeWithTag(QUOTE_BUILDER_TAG).assertExists()
        compose.onNodeWithText(BUILDER_HEADING).assertExists()
        // Replaced, not floated over: the catalogue's own search box is gone.
        assertEquals(0, compose.onAllNodesWithText("Search").fetchSemanticsNodes().size)
    }

    @Test
    fun `back to products puts the catalogue back`() {
        render(oneLine())
        compose.onNodeWithContentDescription(BACK_TO_PRODUCTS).performClick()

        compose.onNodeWithText("Search").assertExists()
        assertEquals(0, compose.onAllNodesWithTag(QUOTE_BUILDER_TAG).fetchSemanticsNodes().size)
    }

    @Test
    fun `a line shows what it is, what it comes to, and a stepper`() {
        render(oneLine())
        scrollTo("ln_1")

        compose.onNodeWithText("SIE1000").assertExists()
        // 2 each at 22,200 is 44,400 — the working, not just the answer.
        compose.onNodeWithText("2 each × ₹22,200 = ₹44,400").assertExists()
    }

    @Test
    fun `the stepper addresses the line by its id, never by a product key`() {
        render(oneLine())
        scrollTo("ln_1")
        compose.onNodeWithContentDescription(moreOf("SIE1000")).performClick()

        // `ln_1`, not `gate|SIE1000`. A hand-typed line has no key at all, so
        // a key-addressed stepper could not drive one.
        assertEquals("ln_1" to 1.0, changed)
    }

    @Test
    fun `an unpriced line says so rather than showing a rupee zero`() {
        render(QuoteDraft(id = "qd_1").add(unpriced, id = "ln_2"))
        scrollTo("ln_2")

        compose.onNodeWithText(RATE_NEEDED).assertExists()
        assertEquals(0, compose.onAllNodesWithText("₹0").fetchSemanticsNodes().size)
    }

    @Test
    fun `clearing empties the lines and stays on the builder`() {
        render(oneLine())
        scrollTo(BUILDER_CLEAR_KEY)
        compose.onNodeWithContentDescription(CLEAR_LINES).performClick()

        assertEquals(1, cleared)
        // The panel is still the thing on screen: the party and the charges on
        // this quotation are not lines and were not thrown away with them.
        compose.onNodeWithTag(QUOTE_BUILDER_TAG).assertExists()
    }

    @Test
    fun `the empty panel says what to do and draws no Clear`() {
        compose.setContent {
            SmartieTheme {
                QuoteBuilderPanel(
                    draft = QuoteDraft(id = "qd_1"),
                    onBack = {},
                    onChangeLineQuantity = { _, _ -> },
                    onClear = {}
                )
            }
        }
        scrollTo(BUILDER_TAIL_KEY)

        compose.onNodeWithText(NOTHING_ON_IT).assertExists()
        // The absence proves its own reach: the heading is still found, so the
        // list composed and simply has no Clear on it.
        assertEquals(
            0,
            compose.onAllNodesWithContentDescription(CLEAR_LINES).fetchSemanticsNodes().size
        )
        assertTrue(
            compose.onAllNodesWithContentDescription(BACK_TO_PRODUCTS)
                .fetchSemanticsNodes().isNotEmpty()
        )
    }
}
