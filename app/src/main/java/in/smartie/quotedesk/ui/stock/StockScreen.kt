package `in`.smartie.quotedesk.ui.stock

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.smartie.quotedesk.data.mapping.Money
import `in`.smartie.quotedesk.data.model.ProductRecord
import `in`.smartie.quotedesk.data.model.StockRecord
import `in`.smartie.quotedesk.domain.Permissions
import `in`.smartie.quotedesk.domain.StockBoard
import `in`.smartie.quotedesk.domain.StockFilter
import `in`.smartie.quotedesk.domain.StockRow
import `in`.smartie.quotedesk.domain.StockStatus
import `in`.smartie.quotedesk.domain.StockView
import `in`.smartie.quotedesk.ui.AppDataViewModel
import `in`.smartie.quotedesk.ui.components.CompactStepper
import `in`.smartie.quotedesk.ui.components.EmptyState
import `in`.smartie.quotedesk.ui.components.ListRow
import `in`.smartie.quotedesk.ui.components.SectionHeader
import `in`.smartie.quotedesk.ui.components.SmartieCard
import `in`.smartie.quotedesk.ui.components.SmartieField
import `in`.smartie.quotedesk.ui.components.SmartieGhostButton
import `in`.smartie.quotedesk.ui.components.SmartiePrimaryButton
import `in`.smartie.quotedesk.ui.components.SummaryTile
import `in`.smartie.quotedesk.ui.components.Tag
import `in`.smartie.quotedesk.ui.components.TagTone
import `in`.smartie.quotedesk.ui.theme.LocalSmartieDimens
import `in`.smartie.quotedesk.ui.theme.SmartieColors

/** Everything Our Stock can do, so the screen itself stays stateless. */
data class StockActions(
    val onQueryChange: (String) -> Unit = {},
    val onFilter: (StockFilter) -> Unit = {},
    val onIncrement: (String) -> Unit = {},
    val onDecrement: (String) -> Unit = {},
    val onDone: (StockRecord) -> Unit = {},
    val onClearPending: (String) -> Unit = {},
    val onSaveAll: () -> Unit = {},
    val onEdit: (StockRecord, Double, Double, String) -> Unit = { _, _, _, _ -> },
    val onTogglePin: (StockRecord) -> Unit = {},
    val onStopTracking: (StockRecord) -> Unit = {},
    val onAddProduct: (ProductRecord, Double, Double, String) -> Unit = { _, _, _, _ -> },
    val onAddManual: (String, String, String, Double, Double, String) -> Unit =
        { _, _, _, _, _, _ -> }
)

/** What the screen may show this person, straight from [Permissions]. */
data class StockCapabilities(
    val adjust: Boolean = false,
    val exactQuantity: Boolean = false,
    val reorderLevel: Boolean = false,
    val pin: Boolean = false,
    val stopTracking: Boolean = false
) {
    /** A Worker gets no control at all — not even a disabled one. */
    val anyControl: Boolean get() = adjust || reorderLevel || pin || stopTracking
}

@Composable
fun StockScreen(data: AppDataViewModel, viewModel: StockViewModel) {
    val stock by data.stock.collectAsStateWithLifecycle()
    val products by data.products.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val online by viewModel.online.collectAsStateWithLifecycle()

    val view = remember(stock, query, filter, pending) {
        StockBoard.build(stock, query, filter, pending)
    }
    val byKey = remember(stock) { stock.associateBy { it.key } }

    StockBoardScreen(
        view = view,
        online = online,
        saving = saving,
        capabilities = StockCapabilities(
            adjust = viewModel.canAdjust(),
            exactQuantity = viewModel.canSetExactQuantity(),
            reorderLevel = viewModel.canSetReorderLevel(),
            pin = viewModel.canPin(),
            stopTracking = viewModel.canStopTracking()
        ),
        products = products,
        actions = StockActions(
            onQueryChange = viewModel::setQuery,
            onFilter = viewModel::toggleFilter,
            onIncrement = { viewModel.changePending(it, 1.0) },
            onDecrement = { viewModel.changePending(it, -1.0) },
            onDone = { viewModel.save(it) },
            onClearPending = viewModel::clearPending,
            onSaveAll = { viewModel.saveAll(byKey.values.toList()) },
            onEdit = { record, quantity, reorder, note ->
                viewModel.edit(record, quantity, reorder, note)
            },
            onTogglePin = viewModel::togglePin,
            onStopTracking = { viewModel.stopTracking(it) },
            onAddProduct = { product, quantity, reorder, note ->
                viewModel.addFromProduct(product, quantity, reorder, note)
            },
            onAddManual = { model, name, unit, quantity, reorder, note ->
                viewModel.addManual(model, name, "", unit, quantity, reorder, note)
            }
        )
    )
}

/**
 * Our Stock, stateless over [StockActions] so Robolectric can drive it
 * without Firebase.
 *
 * The number on a row is always the **stored** quantity and its tag is always
 * derived from that alone. A pending `+`/`−` count is shown beside it as its
 * own figure, because until Done returns, the stored number is what the shelf
 * and the other phones say.
 */
@Composable
fun StockBoardScreen(
    view: StockView,
    online: Boolean = true,
    saving: Set<String> = emptySet(),
    capabilities: StockCapabilities = StockCapabilities(),
    products: List<ProductRecord> = emptyList(),
    actions: StockActions = StockActions()
) {
    val dimens = LocalSmartieDimens.current
    var editing by remember { mutableStateOf<StockRecord?>(null) }
    var adding by remember { mutableStateOf(false) }
    val pendingCount = view.rows.count { it.hasPending }

    LazyColumn(
        modifier = Modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(
            start = dimens.screenPadding,
            end = dimens.screenPadding,
            top = dimens.gapM,
            // Clears the bottom navigation on a small phone.
            bottom = dimens.listBottomInset
        ),
        verticalArrangement = Arrangement.spacedBy(dimens.gapS)
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(dimens.gapS)
            ) {
                SummaryTile(
                    caption = "Tracked",
                    value = view.tracked.toString(),
                    tone = TagTone.PURPLE,
                    selected = view.filter == StockFilter.ALL,
                    onClick = { actions.onFilter(StockFilter.ALL) },
                    modifier = Modifier.weight(1f).semantics {
                        contentDescription = "Show all ${view.tracked} tracked items"
                    }
                )
                SummaryTile(
                    caption = "Low",
                    value = view.low.toString(),
                    tone = TagTone.WARN,
                    selected = view.filter == StockFilter.LOW,
                    onClick = { actions.onFilter(StockFilter.LOW) },
                    modifier = Modifier.weight(1f).semantics {
                        contentDescription = "Show ${view.low} low stock items"
                    }
                )
                SummaryTile(
                    caption = "Out",
                    value = view.out.toString(),
                    tone = TagTone.DANGER,
                    selected = view.filter == StockFilter.OUT,
                    onClick = { actions.onFilter(StockFilter.OUT) },
                    modifier = Modifier.weight(1f).semantics {
                        contentDescription = "Show ${view.out} out of stock items"
                    }
                )
            }
        }

        item {
            SmartieField(
                label = "Search",
                value = view.query,
                onValueChange = actions.onQueryChange,
                placeholder = "Name, model or note",
                modifier = Modifier.semantics { contentDescription = "Search stock" }
            )
        }

        if (capabilities.adjust) {
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(dimens.gapS)
                ) {
                    SmartiePrimaryButton(
                        text = "Add stock",
                        onClick = { adding = true },
                        enabled = online,
                        modifier = Modifier.weight(1f).semantics {
                            contentDescription = if (online) "Add stock" else OFFLINE_LABEL
                        }
                    )
                    if (pendingCount > 1) {
                        SmartieGhostButton(
                            text = "Save $pendingCount",
                            onClick = actions.onSaveAll,
                            enabled = online && saving.isEmpty(),
                            modifier = Modifier.weight(1f).semantics {
                                contentDescription =
                                    if (online) "Save $pendingCount pending changes" else OFFLINE_LABEL
                            }
                        )
                    }
                }
            }
            if (!online) {
                item {
                    Text(
                        OFFLINE_LABEL,
                        style = MaterialTheme.typography.labelMedium,
                        color = SmartieColors.Warn
                    )
                }
            }
        }

        if (view.filter != StockFilter.ALL) {
            item {
                SectionHeader(
                    text = when (view.filter) {
                        StockFilter.LOW -> "Low stock"
                        StockFilter.OUT -> "Out of stock"
                        StockFilter.PINNED -> "Frequently tracked"
                        StockFilter.ALL -> "All stock"
                    },
                    trailing = "${view.rows.size}"
                )
            }
        }

        if (view.isEmpty) {
            item {
                EmptyState(
                    if (view.searching) "Nothing matches that search."
                    else "Nothing is being tracked yet."
                )
            }
        }

        items(view.rows, key = { it.key }) { row ->
            StockRowCard(
                row = row,
                online = online,
                saving = row.key in saving,
                capabilities = capabilities,
                actions = actions,
                onEdit = { editing = row.record }
            )
        }
    }

    editing?.let { record ->
        // Android back closes the sheet before it leaves the screen.
        BackHandler { editing = null }
        EditStockDialog(
            record = record,
            capabilities = capabilities,
            online = online,
            onDismiss = { editing = null },
            onSave = { quantity, reorder, note ->
                actions.onEdit(record, quantity, reorder, note)
                editing = null
            },
            onStopTracking = {
                actions.onStopTracking(record)
                editing = null
            }
        )
    }

    if (adding) {
        BackHandler { adding = false }
        AddStockDialog(
            products = products,
            online = online,
            onDismiss = { adding = false },
            onAddProduct = { product, quantity, reorder, note ->
                actions.onAddProduct(product, quantity, reorder, note)
                adding = false
            },
            onAddManual = { model, name, unit, quantity, reorder, note ->
                actions.onAddManual(model, name, unit, quantity, reorder, note)
                adding = false
            }
        )
    }
}

@Composable
private fun StockRowCard(
    row: StockRow,
    online: Boolean,
    saving: Boolean,
    capabilities: StockCapabilities,
    actions: StockActions,
    onEdit: () -> Unit
) {
    val dimens = LocalSmartieDimens.current
    Column(verticalArrangement = Arrangement.spacedBy(dimens.gapXs)) {
    ListRow(
        title = row.name,
        secondary = row.model.takeIf { it.isNotBlank() && it != row.name },
        meta = "Reorder at ${Money.formatQuantity(row.record.reorderLevel)}"
            .takeIf { row.record.reorderLevel > 0.0 },
        background = if (row.pinned) SmartieColors.PurpleTint else SmartieColors.Panel,
        accent = when (row.status) {
            StockStatus.OUT -> SmartieColors.Danger
            StockStatus.LOW -> SmartieColors.Warn
            StockStatus.IN -> null
        },
        tags = {
            // Derived from the stored quantity alone — a pending −5 must never
            // make a row read Out of stock before anything has been taken.
            when (row.status) {
                StockStatus.OUT -> Tag("Out of stock", TagTone.DANGER)
                StockStatus.LOW -> Tag("Low", TagTone.WARN)
                StockStatus.IN -> Tag("In stock", TagTone.GREEN)
            }
            if (row.record.manual) Tag("Manual", TagTone.NEUTRAL)
            if (row.pinned) Tag("Tracked", TagTone.PURPLE)
        },
        trailing = {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${Money.formatQuantity(row.quantity)} ${row.record.unit}",
                    style = MaterialTheme.typography.titleMedium,
                    color = SmartieColors.Ink
                )
                if (row.hasPending) {
                    Text(
                        "${Money.formatDelta(row.pending)} pending",
                        style = MaterialTheme.typography.labelMedium,
                        color = SmartieColors.Purple
                    )
                }
            }
        }
    )

    if (!capabilities.anyControl) return@Column

    SmartieCard(background = SmartieColors.Panel2) {
        Column {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.gapS)
        ) {
            if (capabilities.adjust) {
                CompactStepper(
                    value = Money.formatDelta(row.pending),
                    onDecrement = { actions.onDecrement(row.key) },
                    onIncrement = { actions.onIncrement(row.key) },
                    highlighted = row.hasPending,
                    modifier = Modifier.semantics {
                        contentDescription = "Change ${row.name} by one"
                    }
                )
            }
            if (capabilities.pin) {
                SmartieGhostButton(
                    text = if (row.pinned) "Unpin" else "Pin",
                    onClick = { actions.onTogglePin(row.record) },
                    enabled = online && !saving,
                    modifier = Modifier.semantics {
                        contentDescription = when {
                            !online -> OFFLINE_LABEL
                            row.pinned -> "Unpin ${row.name}"
                            else -> "Pin ${row.name}"
                        }
                    }
                )
            }
            if (capabilities.reorderLevel) {
                SmartieGhostButton(
                    text = "Edit",
                    onClick = onEdit,
                    modifier = Modifier.semantics { contentDescription = "Edit ${row.name}" }
                )
            }
        }
        if (row.hasPending && capabilities.adjust) {
            Row(
                Modifier.fillMaxWidth().padding(top = dimens.gapS),
                horizontalArrangement = Arrangement.spacedBy(dimens.gapS)
            ) {
                SmartiePrimaryButton(
                    text = if (saving) "Saving…" else "Done",
                    onClick = { actions.onDone(row.record) },
                    enabled = online && !saving,
                    modifier = Modifier.weight(1f).semantics {
                        contentDescription = when {
                            !online -> OFFLINE_LABEL
                            saving -> "Saving ${row.name}"
                            else -> "Done, save ${row.name}"
                        }
                    }
                )
                SmartieGhostButton(
                    text = "Clear",
                    onClick = { actions.onClearPending(row.key) },
                    enabled = !saving,
                    modifier = Modifier.semantics {
                        contentDescription = "Clear pending change for ${row.name}"
                    }
                )
            }
        }
        }
    }
    }
}

@Composable
private fun EditStockDialog(
    record: StockRecord,
    capabilities: StockCapabilities,
    online: Boolean,
    onDismiss: () -> Unit,
    onSave: (Double, Double, String) -> Unit,
    onStopTracking: () -> Unit
) {
    var quantity by remember(record) { mutableStateOf(Money.formatQuantity(record.quantity)) }
    var reorder by remember(record) { mutableStateOf(Money.formatQuantity(record.reorderLevel)) }
    var note by remember(record) { mutableStateOf("") }
    val parsedQuantity = quantity.trim().toDoubleOrNull()
    val parsedReorder = reorder.trim().toDoubleOrNull()
    val valid = (parsedQuantity ?: record.quantity) >= 0.0 && (parsedReorder ?: 0.0) >= 0.0

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit ${StockBoard.displayName(record)}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (capabilities.exactQuantity) {
                    SmartieField(
                        label = "Exact quantity",
                        value = quantity,
                        onValueChange = { quantity = it },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.semantics { contentDescription = "Exact quantity" }
                    )
                } else {
                    // Staff reach this dialog for the reorder level and the
                    // note; the rules reserve an exact quantity for an
                    // Administrator, so the field is not offered at all.
                    Text(
                        "Only an Owner or Administrator can set an exact quantity.",
                        style = MaterialTheme.typography.labelMedium,
                        color = SmartieColors.Steel
                    )
                }
                SmartieField(
                    label = "Reorder level",
                    value = reorder,
                    onValueChange = { reorder = it },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.semantics { contentDescription = "Reorder level" }
                )
                SmartieField(
                    label = "Reason or shared note",
                    value = note,
                    onValueChange = { note = it },
                    singleLine = false,
                    placeholder = "Why, or where it is kept",
                    modifier = Modifier.semantics { contentDescription = "Reason or shared note" }
                )
                if (!online) {
                    Text(
                        OFFLINE_LABEL,
                        style = MaterialTheme.typography.labelMedium,
                        color = SmartieColors.Warn
                    )
                }
                if (capabilities.stopTracking) {
                    SmartieGhostButton(
                        text = "Stop tracking",
                        onClick = onStopTracking,
                        enabled = online,
                        modifier = Modifier.semantics {
                            contentDescription = "Stop tracking this item"
                        }
                    )
                }
            }
        },
        confirmButton = {
            SmartiePrimaryButton(
                text = "Save",
                enabled = valid && online,
                onClick = {
                    onSave(
                        parsedQuantity ?: record.quantity,
                        parsedReorder ?: record.reorderLevel,
                        note
                    )
                }
            )
        },
        dismissButton = { SmartieGhostButton(text = "Cancel", onClick = onDismiss) }
    )
}

@Composable
private fun AddStockDialog(
    products: List<ProductRecord>,
    online: Boolean,
    onDismiss: () -> Unit,
    onAddProduct: (ProductRecord, Double, Double, String) -> Unit,
    onAddManual: (String, String, String, Double, Double, String) -> Unit
) {
    var search by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("each") }
    var quantity by remember { mutableStateOf("0") }
    var reorder by remember { mutableStateOf("0") }
    var note by remember { mutableStateOf("") }

    val matches = remember(products, search) {
        val needle = search.trim().lowercase()
        // Show the top of the catalogue straight away rather than an empty
        // box: most additions are the product somebody is already looking at.
        if (needle.isEmpty()) products.take(8)
        else products.filter {
            it.model.lowercase().contains(needle) || it.name.lowercase().contains(needle)
        }.take(8)
    }
    val startingQuantity = quantity.trim().toDoubleOrNull() ?: 0.0
    val startingReorder = reorder.trim().toDoubleOrNull() ?: 0.0

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add stock") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    SmartieField(
                        label = "Starting quantity",
                        value = quantity,
                        onValueChange = { quantity = it },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.semantics { contentDescription = "Starting quantity" }
                    )
                }
                item {
                    SmartieField(
                        label = "Reorder level",
                        value = reorder,
                        onValueChange = { reorder = it },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                }
                item {
                    SmartieField(
                        label = "Note",
                        value = note,
                        onValueChange = { note = it },
                        singleLine = false
                    )
                }
                if (products.isNotEmpty()) {
                    item { SectionHeader("From the catalogue") }
                    item {
                        SmartieField(
                            label = "Find a product",
                            value = search,
                            onValueChange = { search = it },
                            placeholder = "Model or name",
                            modifier = Modifier.semantics {
                                contentDescription = "Find a catalogue product"
                            }
                        )
                    }
                    items(matches, key = { it.documentId }) { product ->
                        ListRow(
                            title = product.name.ifBlank { product.model },
                            secondary = product.model,
                            onClick = {
                                onAddProduct(product, startingQuantity, startingReorder, note)
                            }
                        )
                    }
                }
                item { SectionHeader("Or a manual item") }
                item {
                    SmartieField(
                        label = "Model or code",
                        value = model,
                        onValueChange = { model = it },
                        modifier = Modifier.semantics { contentDescription = "Manual model or code" }
                    )
                }
                item {
                    SmartieField(
                        label = "Item name",
                        value = name,
                        onValueChange = { name = it },
                        modifier = Modifier.semantics { contentDescription = "Manual item name" }
                    )
                }
                item {
                    SmartieField(label = "Unit", value = unit, onValueChange = { unit = it })
                }
                if (!online) {
                    item {
                        Text(
                            OFFLINE_LABEL,
                            style = MaterialTheme.typography.labelMedium,
                            color = SmartieColors.Warn
                        )
                    }
                }
            }
        },
        confirmButton = {
            SmartiePrimaryButton(
                text = "Add item",
                enabled = online && (model.isNotBlank() || name.isNotBlank()),
                onClick = {
                    onAddManual(model, name, unit, startingQuantity, startingReorder, note)
                }
            )
        },
        dismissButton = { SmartieGhostButton(text = "Cancel", onClick = onDismiss) }
    )
}

/** One wording, on every disabled control and in every refusal. */
const val OFFLINE_LABEL: String = StockViewModel.OFFLINE
