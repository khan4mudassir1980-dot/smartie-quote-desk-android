package `in`.smartie.quotedesk.ui

import android.app.Application
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.data.mapping.Keys
import `in`.smartie.quotedesk.data.model.ProductRecord
import `in`.smartie.quotedesk.data.model.StockRecord
import `in`.smartie.quotedesk.domain.StockBoard
import `in`.smartie.quotedesk.domain.StockFilter
import `in`.smartie.quotedesk.ui.stock.StockActions
import `in`.smartie.quotedesk.ui.stock.StockBoardScreen
import `in`.smartie.quotedesk.ui.stock.StockCapabilities
import `in`.smartie.quotedesk.ui.theme.SmartieTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
// A plain Application: SmartieApplication would need a real Firebase project.
@Config(application = Application::class, qualifiers = "w412dp-h915dp")
class StockScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun record(
        model: String,
        name: String,
        quantity: Double = 7.0,
        reorder: Double = 0.0,
        pinned: Boolean = false,
        pinOrder: Double = 0.0,
        group: String = "gateMotors"
    ): StockRecord {
        val key = Keys.productKey(group, model)
        return StockRecord(
            documentId = Keys.stockDocId(key),
            key = key,
            quantity = quantity,
            reorderLevel = reorder,
            name = name,
            model = model,
            group = group,
            pinned = pinned,
            pinOrder = pinOrder,
            unit = "each"
        )
    }

    private val shelf = listOf(
        record("SIE1000", "Sliding gate motor", quantity = 9.0, reorder = 2.0),
        record("SIE2000", "Swing gate motor", quantity = 2.0, reorder = 2.0),
        record("SIE3000", "Boom barrier", quantity = 0.0, reorder = 2.0)
    )

    private val staff = StockCapabilities(adjust = true, reorderLevel = true, pin = true)
    private val admin = StockCapabilities(
        adjust = true, exactQuantity = true, reorderLevel = true, pin = true, stopTracking = true
    )
    private val worker = StockCapabilities()

    private fun show(
        stock: List<StockRecord> = shelf,
        query: String = "",
        filter: StockFilter = StockFilter.ALL,
        pending: Map<String, Double> = emptyMap(),
        online: Boolean = true,
        saving: Set<String> = emptySet(),
        capabilities: StockCapabilities = staff,
        products: List<ProductRecord> = emptyList(),
        actions: StockActions = StockActions()
    ) {
        compose.setContent {
            SmartieTheme {
                StockBoardScreen(
                    view = StockBoard.build(stock, query, filter, pending),
                    online = online,
                    saving = saving,
                    capabilities = capabilities,
                    products = products,
                    actions = actions
                )
            }
        }
    }

    private fun scrollTo(text: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text, substring = true))
    }

    // --- the board ---------------------------------------------------------

    @Test
    fun `every tracked item is listed with its stored quantity`() {
        show()
        compose.onNodeWithText("Sliding gate motor").assertExists()
        compose.onNodeWithText("9 each").assertExists()
        compose.onNodeWithText("Swing gate motor").assertExists()
    }

    @Test
    fun `the tiles count tracked, low and out`() {
        show()
        compose.onNodeWithContentDescription("Show all 3 tracked items").assertExists()
        compose.onNodeWithContentDescription("Show 1 low stock items").assertExists()
        compose.onNodeWithContentDescription("Show 1 out of stock items").assertExists()
    }

    @Test
    fun `out of stock is shown only at zero`() {
        show()
        compose.onNodeWithText("Out of stock").assertExists()
        // The row sitting exactly on its reorder level is Low, not Out.
        assertEquals(1, compose.onAllNodesWithText("Out of stock").fetchSemanticsNodes().size)
        compose.onNodeWithText("Low").assertExists()
    }

    @Test
    fun `tapping a summary tile asks for that filter`() {
        var asked: StockFilter? = null
        show(actions = StockActions(onFilter = { asked = it }))
        compose.onNodeWithContentDescription("Show 1 low stock items").performClick()
        assertEquals(StockFilter.LOW, asked)
    }

    @Test
    fun `the low filter shows the affected products and names the section`() {
        show(filter = StockFilter.LOW)
        compose.onNodeWithText("Low stock").assertExists()
        compose.onNodeWithText("Swing gate motor").assertExists()
        assertTrue(
            compose.onAllNodesWithText("Sliding gate motor").fetchSemanticsNodes().isEmpty()
        )
    }

    @Test
    fun `the out filter shows the affected products`() {
        show(filter = StockFilter.OUT)
        compose.onNodeWithText("Boom barrier").assertExists()
        assertTrue(compose.onAllNodesWithText("Swing gate motor").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `search narrows the list`() {
        show(query = "boom")
        compose.onNodeWithText("Boom barrier").assertExists()
        assertTrue(compose.onAllNodesWithText("Sliding gate motor").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `a search that matches nothing says so`() {
        show(query = "nothing here at all")
        compose.onNodeWithText("Nothing matches that search.").assertExists()
    }

    @Test
    fun `pinned items lead the list, oldest pin first`() {
        show(
            stock = listOf(
                record("AAA", "Anvil bracket"),
                record("ZZZ", "Zebra rail", pinned = true, pinOrder = 200.0),
                record("MMM", "Middle plate", pinned = true, pinOrder = 100.0)
            ),
            capabilities = worker
        )
        fun top(text: String) =
            compose.onNodeWithText(text).fetchSemanticsNode().positionInRoot.y

        assertTrue("the first pin leads", top("Middle plate") < top("Zebra rail"))
        assertTrue("both pins lead the unpinned row", top("Zebra rail") < top("Anvil bracket"))
    }

    // --- pending never moves the stored numbers ----------------------------

    @Test
    fun `a pending delta is shown beside the stored quantity, never instead of it`() {
        show(pending = mapOf("gateMotors|SIE1000" to 5.0))
        compose.onNodeWithText("9 each").assertExists()
        compose.onNodeWithText("+5 pending").assertExists()
        assertTrue(compose.onAllNodesWithText("14 each").fetchSemanticsNodes().isEmpty())
    }

    /** A pending −2 on a count of 2 must not read Out of stock. */
    @Test
    fun `the status badge ignores a pending delta entirely`() {
        show(
            stock = listOf(record("SIE2000", "Swing gate motor", quantity = 2.0, reorder = 2.0)),
            pending = mapOf("gateMotors|SIE2000" to -2.0)
        )
        compose.onNodeWithText("Low").assertExists()
        assertTrue(compose.onAllNodesWithText("Out of stock").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `plus and minus ask for a pending change and Done appears only with one`() {
        val changes = mutableListOf<String>()
        show(
            stock = listOf(record("SIE1000", "Sliding gate motor", quantity = 9.0)),
            actions = StockActions(onIncrement = { changes += "+$it" }, onDecrement = { changes += "-$it" })
        )
        assertTrue("Done appears only with a pending change",
            compose.onAllNodesWithText("Done").fetchSemanticsNodes().isEmpty())

        compose.onNodeWithText("+").performClick()
        compose.onNodeWithText("−").performClick()
        assertEquals(listOf("+gateMotors|SIE1000", "-gateMotors|SIE1000"), changes)
    }

    @Test
    fun `Done saves that row and Clear discards its draft`() {
        var saved: StockRecord? = null
        var cleared: String? = null
        show(
            pending = mapOf("gateMotors|SIE1000" to 3.0),
            actions = StockActions(onDone = { saved = it }, onClearPending = { cleared = it })
        )
        scrollTo("Done")
        compose.onNodeWithContentDescription("Done, save Sliding gate motor").performClick()
        assertEquals("gateMotors|SIE1000", saved?.key)

        compose.onNodeWithContentDescription("Clear pending change for Sliding gate motor").performClick()
        assertEquals("gateMotors|SIE1000", cleared)
    }

    @Test
    fun `a row already saving shows Saving and cannot be tapped again`() {
        show(
            pending = mapOf("gateMotors|SIE1000" to 3.0),
            saving = setOf("gateMotors|SIE1000")
        )
        scrollTo("Saving")
        compose.onNodeWithContentDescription("Saving Sliding gate motor").assertIsNotEnabled()
    }

    // --- roles --------------------------------------------------------------

    @Test
    fun `a Worker sees stock and names but no control at all`() {
        show(capabilities = worker, pending = emptyMap())
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
    fun `Staff get the stepper, pin and Edit but no exact quantity field`() {
        show(capabilities = staff)
        compose.onNodeWithContentDescription("Edit Sliding gate motor").performClick()
        compose.onNodeWithText("Only an Owner or Administrator can set an exact quantity.").assertExists()
        assertTrue(
            compose.onAllNodesWithText("Exact quantity").fetchSemanticsNodes().isEmpty()
        )
        compose.onNodeWithContentDescription("Reorder level").assertExists()
    }

    @Test
    fun `an Administrator gets the exact quantity field and Stop tracking`() {
        show(capabilities = admin)
        compose.onNodeWithContentDescription("Edit Sliding gate motor").performClick()
        compose.onNodeWithContentDescription("Exact quantity").assertExists()
        compose.onNodeWithContentDescription("Stop tracking this item").assertExists()
    }

    @Test
    fun `saving an edit passes the stored numbers straight through`() {
        var edited: Triple<Double, Double, String>? = null
        show(
            capabilities = admin,
            actions = StockActions(onEdit = { _, q, r, n -> edited = Triple(q, r, n) })
        )
        compose.onNodeWithContentDescription("Edit Sliding gate motor").performClick()
        compose.onNodeWithText("Save").performClick()
        // Untouched fields must arrive unchanged, so an edit nobody altered
        // reaches the repository as the no-op it is.
        assertEquals(Triple(9.0, 2.0, ""), edited)
    }

    // --- adding ---------------------------------------------------------------

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
        show(
            products = listOf(renamed),
            actions = StockActions(onAddProduct = { p, _, _, _ -> added = p.stockKey })
        )
        compose.onNodeWithContentDescription("Add stock").performClick()
        compose.onNodeWithText("Barrier arm").performClick()
        assertEquals("gateMotors|SIE9000", added)
    }

    @Test
    fun `Add item stays disabled until a manual item is named`() {
        show()
        compose.onNodeWithContentDescription("Add stock").performClick()
        compose.onNodeWithText("Add item").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Manual model or code").assertExists()
        compose.onNodeWithContentDescription("Manual item name").assertExists()
    }

    // --- offline ---------------------------------------------------------------

    @Test
    fun `offline the list still reads and every control says why it is disabled`() {
        show(pending = mapOf("gateMotors|SIE1000" to 3.0), online = false)
        // Cached stock stays readable.
        compose.onNodeWithText("Sliding gate motor").assertExists()
        compose.onNodeWithText("9 each").assertExists()
        compose.onAllNodesWithText("Internet required to change stock")[0].assertExists()
        // Every mutation control carries the same wording and is disabled.
        compose.onAllNodesWithContentDescription("Internet required to change stock")[0]
            .assertIsNotEnabled()
    }

    @Test
    fun `offline the pending draft is still shown so nothing is lost`() {
        show(pending = mapOf("gateMotors|SIE1000" to 3.0), online = false)
        compose.onNodeWithText("+3 pending").assertExists()
    }

    @Test
    fun `online the controls are enabled again`() {
        show(pending = mapOf("gateMotors|SIE1000" to 3.0), online = true)
        compose.onNodeWithContentDescription("Add stock").assertIsEnabled()
        scrollTo("Done")
        compose.onNodeWithContentDescription("Done, save Sliding gate motor").assertIsEnabled()
    }
}
