package `in`.smartie.quotedesk.ui.products

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.smartie.quotedesk.data.mapping.Money
import `in`.smartie.quotedesk.data.model.ProductRecord
import `in`.smartie.quotedesk.data.model.RateTierV2
import `in`.smartie.quotedesk.data.model.StockRecord
import `in`.smartie.quotedesk.domain.Catalogue
import `in`.smartie.quotedesk.domain.CatalogueEntry
import `in`.smartie.quotedesk.domain.DraftLine
import `in`.smartie.quotedesk.domain.Permissions
import `in`.smartie.quotedesk.domain.ProductPins
import `in`.smartie.quotedesk.domain.QuoteDraft
import `in`.smartie.quotedesk.ui.AppDataViewModel
import `in`.smartie.quotedesk.ui.components.CompactStepper
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
 * Phase N2. Editing a product, managing categories and resolving price reviews
 * are N6's; turning the draft this screen fills into a numbered quotation is
 * N5's.
 */
@Composable
fun ProductsScreen(
    data: AppDataViewModel,
    viewModel: ProductsViewModel,
) {
    val member = data.member
    if (!Permissions.canViewProducts(member)) {
        // The tab is already hidden from a Worker and the rules refuse the
        // read, so this is the third lock rather than the only one (T-P3).
        EmptyState("Products and prices are not part of a Worker account.")
        return
    }

    val dimens = LocalSmartieDimens.current
    val products by data.products.collectAsStateWithLifecycle()
    val categories by data.categories.collectAsStateWithLifecycle()
    val pinnedKeys by data.pinnedKeys.collectAsStateWithLifecycle()
    val stock by data.stock.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val minimumKg by viewModel.minimumKg.collectAsStateWithLifecycle()
    val openShelves by viewModel.openShelves.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()

    val view = remember(products, categories, pinnedKeys, query, minimumKg) {
        Catalogue.build(products, categories, pinnedKeys, query, minimumKg)
    }
    val stockByKey = remember(stock) { stock.associateBy { it.key } }
    val canPin = viewModel.canManagePins()
    var showDraft by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = dimens.screenPadding,
                end = dimens.screenPadding,
                top = dimens.gapM,
                bottom = dimens.listBottomInset,
            ),
            verticalArrangement = Arrangement.spacedBy(dimens.gapS),
        ) {
            item(key = "search") {
                SmartieField(
                    label = "Search",
                    value = query,
                    onValueChange = viewModel::setQuery,
                    placeholder = "Search all products",
                )
            }

            item(key = "filters") {
                Column(verticalArrangement = Arrangement.spacedBy(dimens.gapS)) {
                    SegmentedChoice(
                        options = RateTierV2.entries.toList(),
                        selected = draft.tier,
                        label = { it.label },
                        onSelect = { viewModel.setTier(it, products) },
                    )
                    LoadFilter(minimumKg = minimumKg, onSelect = viewModel::setMinimumKg)
                }
            }

            if (view.searching) {
                if (view.results.isEmpty()) {
                    item(key = "no-results") {
                        EmptyState("No products found. Nothing matches “$query”.")
                    }
                } else {
                    item(key = "result-head") {
                        SectionHeader(
                            text = "${view.matchCount} product${if (view.matchCount == 1) "" else "s"} " +
                                "matching “$query”",
                            trailing = if (view.matchCount > view.results.size) {
                                "showing ${view.results.size}"
                            } else {
                                null
                            },
                        )
                    }
                    items(view.results, key = { it.product.documentId }) { entry ->
                        ProductCard(
                            entry = entry,
                            stock = stockByKey[entry.product.key],
                            tier = draft.tier,
                            quantity = draft.quantityOf(entry.product.key),
                            pinned = ProductPins.isPinned(pinnedKeys, entry.product.key),
                            canPin = canPin,
                            showCategory = true,
                            onAdd = { viewModel.add(entry.product) },
                            onChangeQuantity = { viewModel.changeQuantity(entry.product.key, it) },
                            onTogglePin = { viewModel.togglePin(pinnedKeys, entry.product.key) },
                            onMovePin = null,
                        )
                    }
                }
                return@LazyColumn
            }

            if (view.pinned.isNotEmpty()) {
                item(key = "pinned-head") {
                    SectionHeader("Pinned Products", trailing = "${view.pinned.size} / ${Catalogue.MAX_PINS}")
                }
                items(view.pinned, key = { "pin-" + it.product.documentId }) { entry ->
                    ProductCard(
                        entry = entry,
                        stock = stockByKey[entry.product.key],
                        tier = draft.tier,
                        quantity = draft.quantityOf(entry.product.key),
                        pinned = true,
                        canPin = canPin,
                        onAdd = { viewModel.add(entry.product) },
                        onChangeQuantity = { viewModel.changeQuantity(entry.product.key, it) },
                        onTogglePin = { viewModel.togglePin(pinnedKeys, entry.product.key) },
                        onMovePin = { delta -> viewModel.movePin(pinnedKeys, entry.product.key, delta) },
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
                        onClick = { viewModel.toggleShelf(shelf.id, !expanded) },
                    )
                }
                if (!expanded) return@forEach
                if (shelf.entries.isEmpty()) {
                    item(key = "shelf-empty-" + shelf.id) {
                        EmptyState("No products in this category yet.")
                    }
                } else {
                    items(shelf.entries, key = { it.product.documentId }) { entry ->
                        ProductCard(
                            entry = entry,
                            stock = stockByKey[entry.product.key],
                            tier = draft.tier,
                            quantity = draft.quantityOf(entry.product.key),
                            pinned = false,
                            canPin = canPin,
                            onAdd = { viewModel.add(entry.product) },
                            onChangeQuantity = { viewModel.changeQuantity(entry.product.key, it) },
                            onTogglePin = { viewModel.togglePin(pinnedKeys, entry.product.key) },
                            onMovePin = null,
                        )
                    }
                }
            }
        }

        QuoteBar(
            lineCount = draft.lineCount,
            total = Money.formatRupees(draft.total, decimals = 0),
            needsRate = draft.needsRateCount,
            onOpen = { showDraft = true },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = dimens.screenPadding, vertical = dimens.gapS),
        )
    }

    if (showDraft) {
        DraftSheet(
            draft = draft,
            onDismiss = { showDraft = false },
            onChangeQuantity = viewModel::changeQuantity,
            onClear = {
                viewModel.clearDraft()
                showDraft = false
            },
        )
    }
}

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
    onAdd: () -> Unit,
    onChangeQuantity: (Double) -> Unit,
    onTogglePin: () -> Unit,
    onMovePin: ((Int) -> Unit)?,
    showCategory: Boolean = false,
) {
    val dimens = LocalSmartieDimens.current
    val product = entry.product
    val inQuote = quantity > 0.0
    SmartieCard(
        background = if (pinned) SmartieColors.PurpleTint else SmartieColors.Panel,
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
                    Text(
                        product.unit,
                        style = MaterialTheme.typography.bodySmall,
                        color = SmartieColors.Steel,
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
                SmartiePrimaryButton(
                    text = if (inQuote) "In quote" else "Add",
                    onClick = onAdd,
                )
            }

            if (canPin) {
                Row(horizontalArrangement = Arrangement.spacedBy(dimens.gapXs)) {
                    SmartieGhostButton(
                        text = if (pinned) "★ Pinned" else "☆ Pin",
                        onClick = onTogglePin,
                    )
                    if (onMovePin != null) {
                        SmartieGhostButton(text = "↑", onClick = { onMovePin(-1) })
                        SmartieGhostButton(text = "↓", onClick = { onMovePin(1) })
                    }
                }
            }
        }
    }
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
