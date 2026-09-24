package `in`.smartie.quotedesk.ui.products

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.smartie.quotedesk.data.mapping.Money
import `in`.smartie.quotedesk.data.model.ProductRecord
import `in`.smartie.quotedesk.data.model.RateTierV2
import `in`.smartie.quotedesk.data.model.StockRecord
import `in`.smartie.quotedesk.domain.Catalogue
import `in`.smartie.quotedesk.domain.CatalogueEntry
import `in`.smartie.quotedesk.domain.CatalogueView
import `in`.smartie.quotedesk.domain.DraftLine
import `in`.smartie.quotedesk.domain.Permissions
import `in`.smartie.quotedesk.domain.ProductDraft
import `in`.smartie.quotedesk.domain.ProductUnit
import `in`.smartie.quotedesk.domain.RoleTitles

import `in`.smartie.quotedesk.domain.PinDrag
import `in`.smartie.quotedesk.domain.ProductPins
import `in`.smartie.quotedesk.domain.QuoteDraft
import `in`.smartie.quotedesk.domain.ScrollToTop
import `in`.smartie.quotedesk.ui.AppDataViewModel
import `in`.smartie.quotedesk.ui.components.CompactStepper
import `in`.smartie.quotedesk.ui.components.DragHandle
import `in`.smartie.quotedesk.ui.components.EmptyState
import `in`.smartie.quotedesk.ui.components.QuoteBar
import `in`.smartie.quotedesk.ui.components.SectionHeader
import `in`.smartie.quotedesk.ui.components.SegmentedChoice
import `in`.smartie.quotedesk.ui.components.ShelfHeader
import `in`.smartie.quotedesk.ui.components.SmartieCard
import `in`.smartie.quotedesk.ui.components.SmartieField
import `in`.smartie.quotedesk.ui.components.SmartieGhostButton
import `in`.smartie.quotedesk.ui.components.SmartiePrimaryButton
import `in`.smartie.quotedesk.ui.components.Tag
import `in`.smartie.quotedesk.ui.components.TagTone
import `in`.smartie.quotedesk.ui.theme.LocalSmartieDimens
import `in`.smartie.quotedesk.ui.theme.SmartieColors

/**
 * The Products tab: the whole catalogue on its shelves, as the PWA's
 * `V.catalogue` (`index.html:7615-7721`) lays it out.
 *
 * Phase N2, with the minimal product edit added in N5.7: the rate, the unit,
 * the minimum chargeable area and GST, for an Owner or an Administrator.
 * Managing categories and resolving price reviews are still N6's; turning the
 * draft this screen fills into a numbered quotation is N5.8's.
 */
/** Everything the catalogue can do, so the screen itself stays stateless. */
data class ProductsActions(
    val onQueryChange: (String) -> Unit = {},
    val onMinimumKgChange: (Double?) -> Unit = {},
    val onTierChange: (RateTierV2) -> Unit = {},
    val onToggleShelf: (String, Boolean) -> Unit = { _, _ -> },
    val onAdd: (ProductRecord) -> Unit = {},
    val onChangeQuantity: (String, Double) -> Unit = { _, _ -> },
    val onTogglePin: (String) -> Unit = {},
    /** One place up (-1) or down (+1), from the drag handle's named actions. */
    val onMovePin: (String, Int) -> Unit = { _, _ -> },
    /** A drop: put the first key where the second one currently sits. */
    val onReorderPin: (String, String) -> Unit = { _, _ -> },
    val onClearDraft: () -> Unit = {},
    /** Save one product's corrections. The draft is what the sheet showed. */
    val onSaveProduct: (ProductRecord, ProductDraft) -> Unit = { _, _ -> },
)

@Composable
fun ProductsScreen(
    data: AppDataViewModel,
    viewModel: ProductsViewModel,
) {
    val products by data.products.collectAsStateWithLifecycle()
    val categories by data.categories.collectAsStateWithLifecycle()
    val pinnedKeys by data.pinnedKeys.collectAsStateWithLifecycle()
    val stock by data.stock.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val minimumKg by viewModel.minimumKg.collectAsStateWithLifecycle()
    val openShelves by viewModel.openShelves.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val savingProduct by viewModel.savingProduct.collectAsStateWithLifecycle()
    val productFailure by viewModel.productFailure.collectAsStateWithLifecycle()

    val view = remember(products, categories, pinnedKeys, query, minimumKg) {
        Catalogue.build(products, categories, pinnedKeys, query, minimumKg)
    }
    // Joined on the product's immutable key, never its display model: a
    // renamed product keeps the stock row and the history it already had.
    val stockByKey = remember(stock) { stock.associateBy { it.key } }

    // A line tapped in before the stored draft answered was priced at the tier
    // the screen was showing, not the tier the draft turned out to carry.
    // Keyed on the mismatch itself rather than on the draft, so it fires
    // whichever of the two arrives second and stops as soon as it is fixed.
    LaunchedEffect(products.isNotEmpty(), draft.hasLinesOutOfStep) {
        if (products.isNotEmpty() && draft.hasLinesOutOfStep) {
            viewModel.alignDraftToTier(products)
        }
    }

    ProductsCatalogue(
        canViewProducts = Permissions.canViewProducts(data.member),
        view = view,
        draft = draft,
        query = query,
        minimumKg = minimumKg,
        openShelves = openShelves,
        pinnedKeys = pinnedKeys,
        canPin = viewModel.canManagePins(),
        canEditProducts = Permissions.canEditProducts(data.member),
        savingProduct = savingProduct,
        productFailure = productFailure,
        stockByKey = stockByKey,
        actions = ProductsActions(
            onQueryChange = viewModel::setQuery,
            onMinimumKgChange = viewModel::setMinimumKg,
            onTierChange = { viewModel.setTier(it, products) },
            onToggleShelf = viewModel::toggleShelf,
            onAdd = viewModel::add,
            onChangeQuantity = viewModel::changeQuantity,
            onTogglePin = { key -> viewModel.togglePin(pinnedKeys, key) },
            onMovePin = { key, delta -> viewModel.movePin(pinnedKeys, key, delta) },
            onReorderPin = { moved, target -> viewModel.reorderPin(pinnedKeys, moved, target) },
            onClearDraft = viewModel::clearDraft,
            onSaveProduct = viewModel::saveProduct,
        ),
    )
}

/**
 * The catalogue itself, taking only what it draws. Stateless, so the whole page
 * can be rendered in a test without Firebase behind it.
 */
@Composable
fun ProductsCatalogue(
    canViewProducts: Boolean,
    view: CatalogueView,
    draft: QuoteDraft,
    query: String = "",
    minimumKg: Double? = null,
    openShelves: Set<String> = emptySet(),
    pinnedKeys: List<String> = emptyList(),
    canPin: Boolean = false,
    canEditProducts: Boolean = false,
    savingProduct: Boolean = false,
    productFailure: String? = null,
    stockByKey: Map<String, StockRecord> = emptyMap(),
    actions: ProductsActions = ProductsActions(),
) {
    if (!canViewProducts) {
        // The tab is already hidden from a Worker and the rules refuse the
        // read, so this is the third lock rather than the only one (T-P3).
        EmptyState("Products and prices are not part of a ${RoleTitles.STAFF} account.")
        return
    }

    val dimens = LocalSmartieDimens.current
    var showDraft by remember { mutableStateOf(false) }

    // The product being corrected, if any. The editor replaces the catalogue
    // rather than floating over it: it is a form with seven fields, and on a
    // phone there is nothing useful to see behind it.
    var editing by remember { mutableStateOf<ProductRecord?>(null) }

    // **The sheet does not close on the tap.** A refusal — a stored price that
    // cannot be read, a model that can only be guessed at — names something
    // the person has to act on, and closing the sheet would take it away
    // before they read it. So the tap starts the save and the sheet stays
    // until the save has actually finished with nothing to say.
    var awaitingSave by remember { mutableStateOf(false) }
    LaunchedEffect(savingProduct, productFailure) {
        if (awaitingSave && !savingProduct) {
            awaitingSave = false
            if (productFailure == null) editing = null
        }
    }

    val correcting = editing
    if (correcting != null) {
        ProductEditorSheet(
            record = correcting,
            canEdit = canEditProducts,
            saving = savingProduct,
            failure = productFailure,
            actions = ProductEditorActions(
                onSave = { edited, draft ->
                    awaitingSave = true
                    actions.onSaveProduct(edited, draft)
                },
                onCancel = { editing = null },
            ),
        )
        BackHandler { editing = null }
        return
    }

    // The catalogue is long — every shelf, every card. `derivedStateOf` so a
    // scroll recomposes the one control rather than the whole page.
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val backToTop by remember {
        derivedStateOf {
            ScrollToTop.visible(
                firstVisibleItemIndex = listState.firstVisibleItemIndex,
                firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
            )
        }
    }

    // Which pinned card is being dragged, how far it has travelled, and the
    // heights it has to travel over. The heights are a plain map: nothing
    // reads them until the finger lifts, so they must not cause recomposition.
    var draggingKey by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }
    val pinnedHeights = remember { mutableMapOf<String, Int>() }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = dimens.screenPadding,
                end = dimens.screenPadding,
                top = dimens.gapM,
                // Room for the quote bar **and** the back-to-top control above
                // it, reserved whether or not the control is showing: a
                // padding that changed as it appeared would shift the last
                // card under the thumb that was reaching for it.
                bottom = dimens.listBottomInset + dimens.backToTopSize + dimens.gapS,
            ),
            verticalArrangement = Arrangement.spacedBy(dimens.gapS),
        ) {
            item(key = "search") {
                SmartieField(
                    label = "Search",
                    value = query,
                    onValueChange = actions.onQueryChange,
                    placeholder = "Search all products",
                )
            }

            item(key = "filters") {
                Column(verticalArrangement = Arrangement.spacedBy(dimens.gapS)) {
                    SegmentedChoice(
                        options = RateTierV2.entries.toList(),
                        selected = draft.tier,
                        label = { it.label },
                        onSelect = actions.onTierChange,
                    )
                    LoadFilter(minimumKg = minimumKg, onSelect = actions.onMinimumKgChange)
                }
            }

            if (view.searching) {
                if (view.results.isEmpty()) {
                    item(key = "no-results") {
                        EmptyState("No products found. Nothing matches \u201c$query\u201d.")
                    }
                } else {
                    item(key = "result-head") {
                        SectionHeader(
                            text = "${view.matchCount} product${if (view.matchCount == 1) "" else "s"} " +
                                "matching \u201c$query\u201d",
                            trailing = if (view.matchCount > view.results.size) {
                                "showing ${view.results.size}"
                            } else {
                                null
                            },
                        )
                    }
                    items(view.results, key = { it.product.documentId }) { entry ->
                        CatalogueCard(
                            canEdit = canEditProducts,
                            onEdit = { editing = entry.product },
                            entry = entry,
                            stock = stockByKey[entry.product.stockKey],
                            draft = draft,
                            pinned = ProductPins.isPinned(pinnedKeys, entry.product.key),
                            canPin = canPin,
                            showCategory = true,
                            actions = actions,
                        )
                    }
                }
                return@LazyColumn
            }

            if (view.pinned.isNotEmpty()) {
                item(key = "pinned-head") {
                    SectionHeader(
                        "Pinned Products",
                        trailing = "${view.pinned.size} / ${Catalogue.MAX_PINS}",
                    )
                }
                itemsIndexed(
                    view.pinned,
                    key = { _, entry -> "pin-" + entry.product.documentId },
                ) { index, entry ->
                    val key = entry.product.key
                    val dragging = draggingKey == key
                    CatalogueCard(
                        canEdit = canEditProducts,
                        onEdit = { editing = entry.product },
                        entry = entry,
                        stock = stockByKey[entry.product.stockKey],
                        draft = draft,
                        pinned = true,
                        canPin = canPin,
                        showCategory = false,
                        actions = actions,
                        modifier = Modifier
                            .onSizeChanged { pinnedHeights[key] = it.height }
                            // Lifted above its neighbours while it travels.
                            .zIndex(if (dragging) 1f else 0f)
                            .graphicsLayer { translationY = if (dragging) dragOffset else 0f },
                        dragging = dragging,
                        drag = if (canPin && view.pinned.size > 1) {
                            PinDragHandlers(
                                label = "Reorder " +
                                    entry.product.model.ifBlank { entry.product.seedModel },
                                onStart = {
                                    draggingKey = key
                                    dragOffset = 0f
                                },
                                onDelta = { dragOffset += it },
                                onDrop = {
                                    val heights = view.pinned.map { pinned ->
                                        pinnedHeights[pinned.product.key]
                                            ?: pinnedHeights[key]
                                            ?: 1
                                    }
                                    val to = PinDrag.targetIndex(heights, index, dragOffset)
                                    draggingKey = null
                                    dragOffset = 0f
                                    if (to != index) {
                                        actions.onReorderPin(key, view.pinned[to].product.key)
                                    }
                                },
                                onCancel = {
                                    draggingKey = null
                                    dragOffset = 0f
                                },
                            )
                        } else {
                            null
                        },
                    )
                }
            }

            item(key = "shelves-head") { SectionHeader("Product Categories") }

            if (view.isEmpty) {
                item(key = "no-products") {
                    EmptyState(
                        "No products yet. They arrive with the catalogue import; " +
                            "until then this shelf list is empty."
                    )
                }
            }

            view.shelves.forEach { shelf ->
                val expanded = shelf.id in openShelves
                item(key = "shelf-" + shelf.id) {
                    ShelfHeader(
                        name = shelf.name,
                        count = shelf.count,
                        expanded = expanded,
                        onClick = { actions.onToggleShelf(shelf.id, !expanded) },
                    )
                }
                if (!expanded) return@forEach
                if (shelf.entries.isEmpty()) {
                    item(key = "shelf-empty-" + shelf.id) {
                        EmptyState("No products in this category yet.")
                    }
                } else {
                    items(shelf.entries, key = { it.product.documentId }) { entry ->
                        CatalogueCard(
                            canEdit = canEditProducts,
                            onEdit = { editing = entry.product },
                            entry = entry,
                            stock = stockByKey[entry.product.stockKey],
                            draft = draft,
                            pinned = false,
                            canPin = canPin,
                            showCategory = false,
                            actions = actions,
                        )
                    }
                }
            }
        }

        // One stack at the bottom, so the control can never land on the quote
        // bar: it sits above it, and both sit inside the content area the
        // scaffold has already kept clear of the bottom navigation and the
        // system navigation below that.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = dimens.screenPadding, vertical = dimens.gapS),
            verticalArrangement = Arrangement.spacedBy(dimens.gapS),
            horizontalAlignment = Alignment.End,
        ) {
            BackToTopButton(
                visible = backToTop,
                onClick = { scope.launch { listState.animateScrollToItem(0) } },
            )
            QuoteBar(
                lineCount = draft.lineCount,
                total = Money.formatRupees(draft.total, decimals = 0),
                needsRate = draft.needsRateCount,
                onOpen = { showDraft = true },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (showDraft) {
        DraftSheet(
            draft = draft,
            onDismiss = { showDraft = false },
            onChangeQuantity = actions.onChangeQuantity,
            onClear = {
                actions.onClearDraft()
                showDraft = false
            },
        )
    }
}

/**
 * Back to the top of the catalogue.
 *
 * Small, and only there once there is somewhere to go back from — see
 * [ScrollToTop]. It **scrolls and nothing else**: the search text, the
 * Dealer/Contractor/Client tier, the load filter, the pinned order and the
 * draft quotation are all held elsewhere and are untouched by a tap.
 *
 * Its size is its touch target, so there is no invisible ring around it that
 * swallows a tap meant for a card.
 *
 * Products only. The other tabs' lists are short enough to thumb back up,
 * and a floating control on each of them would be five things to miss.
 */
@Composable
internal fun BackToTopButton(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    val dimens = LocalSmartieDimens.current
    Box(
        modifier
            // Outermost, so the bounds a test reads are the control's own and
            // not whatever is left inside the decoration.
            .semantics { contentDescription = ScrollToTop.LABEL }
            .size(dimens.backToTopSize)
            .shadow(3.dp, CircleShape)
            .clip(CircleShape)
            .background(SmartieColors.Panel)
            .border(dimens.hairline, SmartieColors.PurpleLine, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.KeyboardArrowUp,
            contentDescription = null,
            tint = SmartieColors.Purple,
        )
    }
}

/** One catalogue row, wherever it appears: pinned, on a shelf or in results. */
@Composable
private fun CatalogueCard(
    entry: CatalogueEntry,
    stock: StockRecord?,
    draft: QuoteDraft,
    pinned: Boolean,
    canPin: Boolean,
    canEdit: Boolean,
    showCategory: Boolean,
    actions: ProductsActions,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
    dragging: Boolean = false,
    drag: PinDragHandlers? = null,
) {
    ProductCard(
        entry = entry,
        stock = stock,
        tier = draft.tier,
        quantity = draft.quantityOf(entry.product.key),
        pinned = pinned,
        canPin = canPin,
        canEdit = canEdit,
        showCategory = showCategory,
        modifier = modifier,
        dragging = dragging,
        drag = drag,
        onAdd = { actions.onAdd(entry.product) },
        onChangeQuantity = { actions.onChangeQuantity(entry.product.key, it) },
        onTogglePin = { actions.onTogglePin(entry.product.key) },
        onMovePin = { delta -> actions.onMovePin(entry.product.key, delta) },
        onEdit = onEdit,
    )
}

/**
 * What a pinned card's drag handle does.
 *
 * Only pinned cards get one, and only when there are at least two of them:
 * a shelf of one has nothing to reorder.
 */
private data class PinDragHandlers(
    val label: String,
    val onStart: () -> Unit,
    val onDelta: (Float) -> Unit,
    val onDrop: () -> Unit,
    val onCancel: () -> Unit,
)

/** The PWA's minimum-load filter (`match` 3088), as a row of choices. */
@Composable
private fun LoadFilter(minimumKg: Double?, onSelect: (Double?) -> Unit) {
    val options = listOf(null, 600.0, 1000.0, 1500.0, 2000.0)
    SegmentedChoice(
        options = options,
        selected = options.firstOrNull { it == minimumKg },
        label = { if (it == null) "Any load" else "≥${it.toInt()} kg" },
        onSelect = onSelect,
    )
}

/**
 * One product, laid out as `productCard` (3042-3061) is on a phone: the name
 * block and its tags, then the price and the controls beneath.
 */
@Composable
private fun ProductCard(
    entry: CatalogueEntry,
    stock: StockRecord?,
    tier: RateTierV2,
    quantity: Double,
    pinned: Boolean,
    canPin: Boolean,
    canEdit: Boolean,
    onAdd: () -> Unit,
    onChangeQuantity: (Double) -> Unit,
    onTogglePin: () -> Unit,
    onMovePin: (Int) -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
    dragging: Boolean = false,
    drag: PinDragHandlers? = null,
    showCategory: Boolean = false,
) {
    val dimens = LocalSmartieDimens.current
    val product = entry.product
    val inQuote = quantity > 0.0
    SmartieCard(
        // Lifted while it is being dragged, so it is obvious which card the
        // finger has hold of.
        modifier = if (dragging) {
            modifier.shadow(8.dp, RoundedCornerShape(dimens.radius))
        } else {
            modifier
        },
        background = if (dragging || pinned) SmartieColors.PurpleTint else SmartieColors.Panel,
        borderColor = if (dragging) SmartieColors.PurpleLine else SmartieColors.Rule,
        accent = if (inQuote) SmartieColors.Purple else null,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(dimens.gapXs)) {
            Text(
                product.model.ifBlank { product.seedModel },
                style = MaterialTheme.typography.titleSmall,
                color = SmartieColors.Ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(dimens.gapXs)) {
                if (showCategory) Tag(entry.categoryName, TagTone.NEUTRAL)
                stockTag(stock)?.let { (text, tone) -> Tag(text, tone) }
                product.kg?.let { Tag("${it.toInt()} kg", TagTone.NEUTRAL) }
                if (!product.seeded) Tag("added", TagTone.GREEN)
            }

            val description = shortDescription(product.name)
            if (description.isNotBlank()) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = SmartieColors.Steel,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Row(
                Modifier.fillMaxWidth().padding(top = dimens.gapXs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(dimens.gapS),
            ) {
                Column(Modifier.weight(1f)) {
                    val price = product.priceFor(tier)
                    Text(
                        price?.let { Money.formatRupees(it, decimals = 0) } ?: "Price not set",
                        style = MaterialTheme.typography.titleSmall,
                        color = if (price == null) SmartieColors.Warn else SmartieColors.Ink,
                    )
                    // `each` on hundreds of rows is noise; a unit that is
                    // anything else is the thing somebody scanning the list
                    // is looking for, so it gets the emphasis the price has.
                    // That is what makes a wrong unit findable on a phone —
                    // and what makes a product still reading `sqft` rather
                    // than `per sq ft` a visible refusal instead of a silent
                    // mispricing.
                    Text(
                        product.unit,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (product.unit.trim().equals(ProductUnit.EACH, ignoreCase = true)) {
                            SmartieColors.Steel
                        } else {
                            SmartieColors.Ink
                        },
                    )
                }
                if (inQuote) {
                    CompactStepper(
                        value = Money.formatQuantity(quantity),
                        onDecrement = { onChangeQuantity(-1.0) },
                        onIncrement = { onChangeQuantity(1.0) },
                        highlighted = true,
                    )
                }
                if (canEdit) {
                    SmartieGhostButton(
                        text = EDIT_PRODUCT,
                        onClick = onEdit,
                        compact = true,
                        modifier = Modifier.semantics {
                            contentDescription = editLabel(product.model)
                        },
                    )
                }
                SmartiePrimaryButton(
                    text = if (inQuote) "In quote" else "Add",
                    onClick = onAdd,
                )
            }

            if (canPin) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(dimens.gapXs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SmartieGhostButton(
                        text = if (pinned) "★ Pinned" else "☆ Pin",
                        onClick = onTogglePin,
                    )
                    // The arrows this replaced were two more taps on a card
                    // that already carries three, and they only ever moved
                    // one place at a time.
                    if (drag != null) PinDragHandle(drag = drag, onMovePin = onMovePin)
                }
            }
        }
    }
}

/**
 * The grip on a pinned card: long-press and drag to reorder.
 *
 * The handlers are read through [rememberUpdatedState] because the gesture
 * detector outlives the recompositions that renumber the shelf — keying the
 * `pointerInput` on the order instead would cancel a drag in progress every
 * time the list moved under it.
 */
@Composable
private fun PinDragHandle(drag: PinDragHandlers, onMovePin: (Int) -> Unit) {
    val latest = rememberUpdatedState(drag)
    DragHandle(
        label = drag.label,
        // Reachable without a drag, and without putting the arrows back.
        accessibilityActions = listOf(
            CustomAccessibilityAction("Move up") {
                onMovePin(-1)
                true
            },
            CustomAccessibilityAction("Move down") {
                onMovePin(1)
                true
            },
        ),
        modifier = Modifier.pointerInput(Unit) {
            detectDragGesturesAfterLongPress(
                onDragStart = { latest.value.onStart() },
                onDrag = { _, delta -> latest.value.onDelta(delta.y) },
                onDragEnd = { latest.value.onDrop() },
                onDragCancel = { latest.value.onCancel() },
            )
        },
    )
}

/** What the quotation holds so far. Building it into one is N5's work. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DraftSheet(
    draft: QuoteDraft,
    onDismiss: () -> Unit,
    onChangeQuantity: (String, Double) -> Unit,
    onClear: () -> Unit,
) {
    val dimens = LocalSmartieDimens.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = dimens.screenPadding, vertical = dimens.gapS),
            verticalArrangement = Arrangement.spacedBy(dimens.gapS),
        ) {
            SectionHeader("Quotation draft", trailing = Money.formatRupees(draft.total, decimals = 0))
            draft.lines.forEach { line -> DraftRow(line, onChangeQuantity) }
            if (draft.needsRateCount > 0) {
                Text(
                    "${draft.needsRateCount} line${if (draft.needsRateCount == 1) "" else "s"} " +
                        "still need a rate before this can be finalised.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SmartieColors.Warn,
                )
            }
            Text(
                "Turning this into a numbered quotation, with a party, GST and a PDF, " +
                    "arrives with the Quotation phase.",
                style = MaterialTheme.typography.bodySmall,
                color = SmartieColors.Steel,
            )
            SmartieGhostButton(text = "Clear", onClick = onClear, danger = true)
            Spacer(Modifier.height(dimens.gapL))
        }
    }
}

@Composable
private fun DraftRow(line: DraftLine, onChangeQuantity: (String, Double) -> Unit) {
    val dimens = LocalSmartieDimens.current
    SmartieCard {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.gapS),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    line.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = SmartieColors.Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    line.amount?.let { Money.formatRupees(it, decimals = 0) } ?: "Rate needed",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (line.needsRate) SmartieColors.Warn else SmartieColors.Steel,
                )
            }
            CompactStepper(
                value = Money.formatQuantity(line.quantity),
                onDecrement = { onChangeQuantity(line.key, -1.0) },
                onIncrement = { onChangeQuantity(line.key, 1.0) },
            )
        }
    }
}

/** `stockChip` (3508-3514): no chip at all for an untracked product. */
private fun stockTag(stock: StockRecord?): Pair<String, TagTone>? {
    if (stock == null) return null
    val quantity = stock.quantity
    val reorder = stock.reorderLevel
    return when {
        quantity <= 0.0 -> "Out of stock" to TagTone.DANGER
        reorder > 0.0 && quantity <= reorder -> "Low · ${Money.formatQuantity(quantity)} left" to TagTone.WARN
        else -> "${Money.formatQuantity(quantity)} in stock" to TagTone.GREEN
    }
}

/** `shortDesc` (3037-3040): 88 characters, then an ellipsis. */
private fun shortDescription(value: String, limit: Int = 88): String {
    val trimmed = value.trim()
    if (trimmed.length <= limit) return trimmed
    return trimmed.take(limit - 1).trimEnd { it.isWhitespace() || it == ',' || it == ';' || it == '-' } + "…"
}
