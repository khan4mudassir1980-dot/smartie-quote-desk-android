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
import `in`.smartie.quotedesk.data.model.StoppedStockRecord
import `in`.smartie.quotedesk.domain.StockBoard
import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.domain.Role
import `in`.smartie.quotedesk.domain.StockFilter
import `in`.smartie.quotedesk.ui.stock.StockActions
import `in`.smartie.quotedesk.ui.stock.StockBoardScreen
import `in`.smartie.quotedesk.ui.stock.StockCapabilities
import `in`.smartie.quotedesk.ui.stock.StockPhotoUi
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
    group: String = "gateMotors",
    hasPhoto: Boolean = false,
    photoRev: Double = 0.0
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
        unit = "each",
        hasPhoto = hasPhoto,
        photoRev = photoRev
    )
}

internal val stockShelf = listOf(
    stockRecord("SIE1000", "Sliding gate motor", quantity = 9.0, reorder = 2.0),
    stockRecord("SIE2000", "Swing gate motor", quantity = 2.0, reorder = 2.0),
    stockRecord("SIE3000", "Boom barrier", quantity = 0.0, reorder = 2.0)
)

/**
 * The four roles, so a test can go through the same mapping the screen uses
 * rather than hand-assembling a capability set that agrees with nothing.
 */
internal val owner = Member(uid = "uid_owner", name = "Omar", role = Role.OWNER)
internal val admin = Member(uid = "uid_admin", name = "Asha", role = Role.ADMIN)
internal val staff = Member(uid = "uid_staff", name = "Sam", role = Role.STAFF)
internal val worker = Member(uid = "uid_worker", name = "Wes", role = Role.WORKER)

/**
 * Capability sets **derived from the real mapping**, not written out by hand.
 *
 * They used to be hand-assembled literals, which meant every screen test
 * agreed with a second copy of the rules rather than with the copy the app
 * runs. Going through `forMember` is what makes these tests cover the wiring:
 * a role that quietly loses a control loses it here too.
 *
 * For the record, what they come to — Staff: adjust, reorder level, pin,
 * history, and photos, but no exact quantity and no stop-tracking.
 * Administrator: all of it. Worker: nothing writable, `/stockMoves`
 * unreadable, and `photoView` on, because a Worker on the shelf is exactly
 * who a picture is for — no control, rather than a disabled one.
 */
internal val ownerCaps = StockCapabilities.forMember(owner)

internal val adminCaps = StockCapabilities.forMember(admin)

internal val staffCaps = StockCapabilities.forMember(staff)

internal val workerCaps = StockCapabilities.forMember(worker)

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
    photo: StockPhotoUi = StockPhotoUi(),
    stopped: List<StoppedStockRecord> = emptyList(),
    historyExpanded: Boolean = false,
    removing: StockRecord? = null,
    clearing: Boolean = false,
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
                photo = photo,
                stopped = stopped,
                historyExpanded = historyExpanded,
                removing = removing,
                clearing = clearing,
                actions = actions
            )
        }
    }
}

/** One stopped-item history entry, as the reader would have produced it. */
internal fun stoppedRecord(
    id: String,
    model: String,
    name: String,
    quantity: Double = 4.0,
    manual: Boolean = false,
    at: Long = 1_700_000_000_000L,
    group: String = "gateMotors"
): StoppedStockRecord = StoppedStockRecord(
    id = id,
    key = Keys.productKey(group, model),
    name = name,
    model = model,
    unit = "each",
    quantity = quantity,
    manual = manual,
    at = at,
    removedByUid = "uid_admin"
)

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
