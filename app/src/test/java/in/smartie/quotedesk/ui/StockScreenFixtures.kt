package `in`.smartie.quotedesk.ui

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.performScrollToNode
import `in`.smartie.quotedesk.data.mapping.Keys
import `in`.smartie.quotedesk.data.model.ProductRecord
import `in`.smartie.quotedesk.data.model.StockMove
import `in`.smartie.quotedesk.data.model.StockRecord
import `in`.smartie.quotedesk.domain.StockBoard
import `in`.smartie.quotedesk.domain.StockFilter
import `in`.smartie.quotedesk.ui.stock.StockActions
import `in`.smartie.quotedesk.ui.stock.StockBoardScreen
import `in`.smartie.quotedesk.ui.stock.StockCapabilities
import `in`.smartie.quotedesk.ui.theme.SmartieTheme

/**
 * Shared by the Our Stock screen tests.
 *
 * They are **several small classes rather than one large one** on purpose.
 * Robolectric's native-object registry is a fixed 16,777,216-entry array per
 * JVM, and a Compose composition consumes a great many entries; twenty-odd
 * compositions in one class overflow it, which surfaces as
 * ArrayIndexOutOfBoundsException in whichever test happens to run last. With
 * `forkEvery(1)` each class gets a fresh JVM, so keeping classes small is what
 * keeps the registry inside its bounds. Add a class rather than a tenth test.
 */
internal fun stockRecord(
    model: String,
    name: String,
    quantity: Double = 7.0,
    reorder: Double = 0.0,
    pinned: Boolean = false,
    pinOrder: Double = 0.0,
    note: String = "",
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
        note = note,
        unit = "each"
    )
}

internal val stockShelf = listOf(
    stockRecord("SIE1000", "Sliding gate motor", quantity = 9.0, reorder = 2.0),
    stockRecord("SIE2000", "Swing gate motor", quantity = 2.0, reorder = 2.0),
    stockRecord("SIE3000", "Boom barrier", quantity = 0.0, reorder = 2.0)
)

internal val staffCaps =
    StockCapabilities(adjust = true, reorderLevel = true, pin = true, history = true)

internal val adminCaps = StockCapabilities(
    adjust = true,
    exactQuantity = true,
    reorderLevel = true,
    pin = true,
    stopTracking = true,
    history = true
)

/** A Worker: `/stock` is readable, `/stockMoves` is not, nothing is writable. */
internal val workerCaps = StockCapabilities()

internal fun ComposeContentTestRule.showStock(
    stock: List<StockRecord> = stockShelf,
    query: String = "",
    filter: StockFilter = StockFilter.ALL,
    pending: Map<String, Double> = emptyMap(),
    online: Boolean = true,
    saving: Set<String> = emptySet(),
    capabilities: StockCapabilities = staffCaps,
    products: List<ProductRecord> = emptyList(),
    movements: List<StockMove> = emptyList(),
    actions: StockActions = StockActions()
) {
    setContent {
        SmartieTheme {
            StockBoardScreen(
                view = StockBoard.build(stock, query, filter, pending),
                online = online,
                saving = saving,
                capabilities = capabilities,
                products = products,
                movements = movements,
                actions = actions
            )
        }
    }
}

internal fun ComposeContentTestRule.scrollToText(text: String) {
    onNode(hasScrollAction()).performScrollToNode(hasText(text, substring = true))
}

/**
 * The editable node inside the field whose wrapper carries [label].
 *
 * `SmartieField` puts the caller's modifier — and so the content description —
 * on the column that holds the label and the input, because the label is part
 * of the field. Typing has to reach the input itself.
 */
internal fun ComposeContentTestRule.field(label: String): SemanticsNodeInteraction =
    onNode(hasSetTextAction() and hasAnyAncestor(hasContentDescription(label)))
