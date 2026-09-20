package `in`.smartie.quotedesk.ui.stock

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.smartie.quotedesk.data.mapping.Money
import `in`.smartie.quotedesk.data.model.ProductRecord
import `in`.smartie.quotedesk.data.model.StockMove
import `in`.smartie.quotedesk.data.model.StockRecord
import `in`.smartie.quotedesk.data.model.StoppedStockRecord
import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.domain.Permissions
import `in`.smartie.quotedesk.domain.StockBoard
import `in`.smartie.quotedesk.domain.StockFilter
import `in`.smartie.quotedesk.domain.StockPhotoImage
import `in`.smartie.quotedesk.domain.StockRow
import `in`.smartie.quotedesk.domain.StockStatus
import `in`.smartie.quotedesk.domain.StockView
import `in`.smartie.quotedesk.ui.AppDataViewModel
import `in`.smartie.quotedesk.ui.components.CompactStepper
import `in`.smartie.quotedesk.ui.components.EmptyState
import `in`.smartie.quotedesk.ui.components.NOTE_TAG
import `in`.smartie.quotedesk.ui.components.SectionHeader
import `in`.smartie.quotedesk.ui.components.SmartieCard
import `in`.smartie.quotedesk.ui.components.sheetBodyHeight
import `in`.smartie.quotedesk.ui.components.SmartieField
import `in`.smartie.quotedesk.ui.components.SmartieGhostButton
import `in`.smartie.quotedesk.ui.components.SmartiePrimaryButton
import `in`.smartie.quotedesk.ui.components.SummaryTile
import `in`.smartie.quotedesk.ui.components.Tag
import `in`.smartie.quotedesk.ui.components.TagTone
import `in`.smartie.quotedesk.ui.components.clickableNoRipple
import `in`.smartie.quotedesk.util.StockImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val onAddProduct: (ProductRecord, Double, Double, String) -> Unit = { _, _, _, _ -> },
    val onAddManual: (String, String, String, Double, Double, String) -> Unit =
        { _, _, _, _, _, _ -> },
    /** Tapping a thumbnail, or the Photo button on a row without one. */
    val onOpenPhoto: (StockRecord) -> Unit = {},
    /**
     * The bytes to draw for a row.
     *
     * A suspending read rather than a value, because a photo is fetched
     * lazily as its card comes into view and answered from a cache far more
     * often than from Firestore. The default returns nothing, which is what
     * keeps this screen drivable in a test with no repository at all.
     */
    val loadPhoto: suspend (StockRecord) -> ByteArray? = { null },
    /** What the photo sheets can do. */
    val photo: StockPhotoActions = StockPhotoActions(),
    /** Opening the removal confirmation for a row. */
    val onAskRemove: (StockRecord) -> Unit = {},
    /** What the removal confirmation and the history section can do. */
    val removal: StockRemovalActions = StockRemovalActions()
)

/** What the screen may show this person, straight from [Permissions]. */
data class StockCapabilities(
    val adjust: Boolean = false,
    val exactQuantity: Boolean = false,
    val reorderLevel: Boolean = false,
    val pin: Boolean = false,
    val stopTracking: Boolean = false,
    /** Reading `/stockMoves`, which the rules refuse a Worker. */
    val history: Boolean = false,
    /** Taking, replacing and removing a photo: Owner, Administrator, Staff. */
    val photoManage: Boolean = false,
    /** Seeing one. Everybody who may read `/stock`, Workers included. */
    val photoView: Boolean = false,
    /**
     * Clearing stopped-item history. The same Owner-and-Administrator pair
     * that may remove an item in the first place — reading the history is
     * everybody's, emptying it is not.
     */
    val clearHistory: Boolean = false
) {
    /** A Worker gets no control at all — not even a disabled one. */
    val anyControl: Boolean get() = adjust || reorderLevel || pin || stopTracking

    /** Whether the card needs its controls row at all. */
    val anyRowAction: Boolean get() = anyControl || history || photoManage

    companion object {
        /**
         * What this person may do, in **one** place.
         *
         * The screen used to assemble this inline from six view-model calls,
         * which meant the role-to-capability mapping — the thing that decides
         * whether a control is rendered at all — had no test of its own. It
         * does now, per role, and the screen and the tests read the same
         * function rather than two copies of it.
         */
        fun forMember(member: Member): StockCapabilities = StockCapabilities(
            adjust = Permissions.canAdjustStock(member),
            exactQuantity = Permissions.canSetExactQuantity(member),
            reorderLevel = Permissions.canSetReorderLevel(member),
            pin = Permissions.canPinStock(member),
            stopTracking = Permissions.canStopTrackingStock(member),
            history = Permissions.canViewStockHistory(member),
            photoManage = Permissions.canManageStockPhoto(member),
            photoView = Permissions.canViewStockPhoto(member),
            clearHistory = Permissions.canStopTrackingStock(member)
        )
    }
}

@Composable
fun StockScreen(data: AppDataViewModel, viewModel: StockViewModel) {
    val stock by data.stock.collectAsStateWithLifecycle()
    val products by data.products.collectAsStateWithLifecycle()
    val movements by data.movements.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val online by viewModel.online.collectAsStateWithLifecycle()

    val photo by viewModel.photo.collectAsStateWithLifecycle()

    val stopped by data.stoppedStock.collectAsStateWithLifecycle()
    val legacyStopped by data.legacyStopped.collectAsStateWithLifecycle()
    val historyExpanded by viewModel.historyExpanded.collectAsStateWithLifecycle()
    val removing by viewModel.removing.collectAsStateWithLifecycle()
    val clearing by viewModel.clearing.collectAsStateWithLifecycle()

    // Rows the old "stop tracking" left hidden in `/stock`. Converting one is
    // idempotent and only an Owner or Administrator can, so this is safe to
    // run whenever the list changes; for everyone else it is always empty.
    LaunchedEffect(legacyStopped, online) {
        viewModel.convertLegacyStopped(legacyStopped)
    }

    val view = remember(stock, query, filter, pending) {
        StockBoard.build(stock, query, filter, pending)
    }
    val byKey = remember(stock) { stock.associateBy { it.key } }

    // --- the camera and the picker ----------------------------------------
    //
    // Neither needs a manifest permission: TakePicture hands off to whatever
    // camera app is installed, and PickVisualMedia is the system photo
    // picker. Declaring CAMERA would only create a runtime prompt to ask for.
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var captureTarget by remember { mutableStateOf<Uri?>(null) }

    // Compress off the main thread, then hand the bytes to the view model.
    // Nothing is written here; this only fills the preview.
    fun prepare(uri: Uri?) {
        if (uri == null) {
            viewModel.photoAbandoned()
            return
        }
        scope.launch {
            // Reading the picture is IO; decoding and compressing it is not,
            // but they happen in one pass, and IO is the dispatcher that may
            // block.
            val prepared = withContext(Dispatchers.IO) {
                StockImage.prepare(context.contentResolver, uri)
            }
            viewModel.photoPrepared(prepared)
            // The full-size capture has served its purpose.
            withContext(Dispatchers.IO) { StockImage.clearCaptures(context) }
        }
    }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        prepare(if (taken) captureTarget else null)
    }
    val gallery = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { picked -> prepare(picked) }

    StockBoardScreen(
        view = view,
        online = online,
        saving = saving,
        capabilities = viewModel.capabilities(),
        products = products,
        movements = movements,
        photo = photo,
        stopped = stopped,
        historyExpanded = historyExpanded,
        removing = removing,
        clearing = clearing,
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
            onAddProduct = { product, quantity, reorder, note ->
                viewModel.addFromProduct(product, quantity, reorder, note)
            },
            onAddManual = { model, name, unit, quantity, reorder, note ->
                viewModel.addManual(model, name, "", unit, quantity, reorder, note)
            },
            onOpenPhoto = viewModel::openPhoto,
            loadPhoto = viewModel::loadPhoto,
            onAskRemove = viewModel::askRemove,
            removal = StockRemovalActions(
                onRemove = viewModel::removeFromStock,
                onCancel = {
                    // One Cancel for both confirmations, and it writes nothing.
                    viewModel.cancelRemove()
                    viewModel.cancelClearHistory()
                },
                onToggleHistory = viewModel::toggleHistory,
                onClearHistory = viewModel::askClearHistory,
                onConfirmClear = { viewModel.clearHistory(stopped) }
            ),
            photo = StockPhotoActions(
                onTakePhoto = {
                    val target = StockImage.captureTarget(context)
                    captureTarget = target
                    viewModel.awaitingPhoto()
                    camera.launch(target)
                },
                onChooseFromGallery = {
                    viewModel.awaitingPhoto()
                    gallery.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onConfirm = viewModel::confirmPhoto,
                onRetake = viewModel::replacePhoto,
                onRemove = viewModel::removePhoto,
                onCancel = viewModel::closePhoto
            )
        )
    )
}

/**
 * Our Stock, stateless over [StockActions] so Robolectric can drive it
 * without Firebase.
 *
 * The number on a row is always the **stored** quantity and its tag is always
 * derived from that alone. A pending `+`/`−` count is shown beside it as its
 * own figure, clearly labelled, because until Done returns the stored number
 * is what the shelf and the other phones say.
 */
@Composable
fun StockBoardScreen(
    view: StockView,
    online: Boolean = true,
    saving: Set<String> = emptySet(),
    capabilities: StockCapabilities = StockCapabilities(),
    products: List<ProductRecord> = emptyList(),
    movements: List<StockMove> = emptyList(),
    photo: StockPhotoUi = StockPhotoUi(),
    stopped: List<StoppedStockRecord> = emptyList(),
    historyExpanded: Boolean = false,
    removing: StockRecord? = null,
    clearing: Boolean = false,
    actions: StockActions = StockActions()
) {
    val dimens = LocalSmartieDimens.current
    val focus = LocalFocusManager.current
    var editing by remember { mutableStateOf<StockRecord?>(null) }
    var showingHistory by remember { mutableStateOf<StockRecord?>(null) }
    var adding by remember { mutableStateOf(false) }
    val pendingCount = view.rows.count { it.hasPending }

    // Opening a sheet takes the focus off the search box first. Without this
    // the field behind the dialog keeps it, and the keyboard stays up or
    // comes straight back over the dialog's own fields.
    fun openSheet(open: () -> Unit) {
        focus.clearFocus(force = true)
        open()
    }

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
                        onClick = { openSheet { adding = true } },
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
                onEdit = { openSheet { editing = row.record } },
                onHistory = { openSheet { showingHistory = row.record } }
            )
        }

        // The very bottom of the board, after every row: a record of what is
        // gone, collapsed until somebody asks for it. Read-only for everyone.
        item(key = "stopped-history") {
            StoppedHistorySection(
                entries = stopped,
                expanded = historyExpanded,
                canClear = capabilities.clearHistory,
                online = online,
                actions = actions.removal
            )
        }
    }

    editing?.let { record ->
        // Neither back nor a tap outside closes it: both would throw away
        // what somebody has typed. Cancel and Save are the way out.
        EditStockDialog(
            record = record,
            capabilities = capabilities,
            online = online,
            onDismiss = { editing = null },
            onSave = { quantity, reorder, note ->
                actions.onEdit(record, quantity, reorder, note)
                editing = null
            },
            onRemove = {
                // The Edit sheet closes and the confirmation takes over:
                // removing is permanent, and it should not be one tap away
                // from a sheet somebody opened to change a number.
                editing = null
                actions.onAskRemove(record)
            }
        )
    }

    removing?.let { record ->
        StockRemoveDialog(
            record = record,
            online = online,
            saving = record.key in saving,
            actions = actions.removal
        )
    }

    if (clearing) {
        ClearHistoryDialog(count = stopped.size, actions = actions.removal)
    }

    if (adding) {
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

    showingHistory?.let { record ->
        // History holds nothing typed, so back closing it is a convenience
        // rather than a loss.
        BackHandler { showingHistory = null }
        StockHistoryDialog(
            record = record,
            movements = StockBoard.historyFor(movements, record.key),
            onDismiss = { showingHistory = null }
        )
    }

    if (photo.isOpen) {
        StockPhotoDialog(
            state = photo,
            capabilities = capabilities,
            online = online,
            actions = actions
        )
    }
}

/**
 * Whichever photo sheet is open.
 *
 * One dialog rather than three, because only one stage is ever current and
 * three would be three chances for two of them to be open at once. The
 * bodies are the internal panels the tests drive; this holds no logic beyond
 * choosing between them.
 *
 * Back and a tap outside close the looking stages. They do **not** close the
 * preview: that holds a picture somebody has taken and not yet saved, and
 * throwing it away by accident would mean walking back to the shelf.
 */
/**
 * Removing an item. Back and a tap outside close it, because nothing has been
 * typed and closing writes nothing.
 */
@Composable
private fun StockRemoveDialog(
    record: StockRecord,
    online: Boolean,
    saving: Boolean,
    actions: StockRemovalActions
) {
    AlertDialog(
        onDismissRequest = actions.onCancel,
        confirmButton = {},
        title = { Text(REMOVE_TITLE) },
        text = {
            StockRemoveConfirmPanel(online = online, saving = saving, actions = actions)
        }
    )
}

/** Clearing the history, with the number of entries in the question. */
@Composable
private fun ClearHistoryDialog(count: Int, actions: StockRemovalActions) {
    AlertDialog(
        onDismissRequest = actions.onCancel,
        confirmButton = {},
        title = { Text(CLEAR_HISTORY) },
        text = { ClearHistoryConfirmPanel(count = count, actions = actions) }
    )
}

@Composable
private fun StockPhotoDialog(
    state: StockPhotoUi,
    capabilities: StockCapabilities,
    online: Boolean,
    actions: StockActions
) {
    val record = state.record ?: return
    val name = StockBoard.displayName(record)
    val dismissible = state.stage != PhotoStage.PREVIEW && !state.saving

    AlertDialog(
        onDismissRequest = { if (dismissible) actions.photo.onCancel() },
        properties = DialogProperties(
            dismissOnBackPress = dismissible,
            dismissOnClickOutside = dismissible
        ),
        confirmButton = {},
        title = { Text(photoTitle(state.stage, name)) },
        text = {
            when (state.stage) {
                PhotoStage.VIEW -> StockPhotoViewPanel(
                    image = rememberStockPhoto(record, actions.loadPhoto),
                    name = name,
                    canManage = capabilities.photoManage,
                    online = online,
                    saving = state.saving,
                    actions = actions.photo
                )
                PhotoStage.SOURCE -> StockPhotoSourcePanel(
                    online = online,
                    canRemove = record.hasPhoto,
                    actions = actions.photo
                )
                PhotoStage.PREVIEW -> StockPhotoPreviewPanel(
                    prepared = state.prepared,
                    preview = rememberPrepared(state.prepared),
                    online = online,
                    saving = state.saving,
                    refusal = state.refusal,
                    actions = actions.photo
                )
                PhotoStage.CLOSED -> Unit
            }
        }
    )
}

/**
 * The bytes that will be written, decoded once so the person sees them.
 *
 * Takes a nullable and handles null itself rather than being called inside a
 * `?.let`, so the call site is unconditional: a composable that remembers
 * must be reached the same way on every pass.
 */
@Composable
private fun rememberPrepared(image: StockPhotoImage?): ImageBitmap? {
    var bitmap by remember(image) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(image) {
        bitmap = if (image == null) null else withContext(Dispatchers.Default) {
            StockImage.decodePreview(image.bytes)?.asImageBitmap()
        }
    }
    return bitmap
}

internal fun photoTitle(stage: PhotoStage, name: String): String = when (stage) {
    PhotoStage.VIEW -> name
    PhotoStage.SOURCE -> "Photo of $name"
    PhotoStage.PREVIEW -> "Save this photo?"
    PhotoStage.CLOSED -> ""
}

/**
 * One stock item, as **one card**.
 *
 * The information and the controls used to be two detached cards, which read
 * as two unrelated things. The stored quantity is the authoritative number and
 * is styled as such; the stepper's middle figure is captioned "Pending change"
 * so it can never be mistaken for it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StockRowCard(
    row: StockRow,
    online: Boolean,
    saving: Boolean,
    capabilities: StockCapabilities,
    actions: StockActions,
    onEdit: () -> Unit,
    onHistory: () -> Unit
) {
    val dimens = LocalSmartieDimens.current
    SmartieCard(
        // Names the whole card, so a test can prove the controls are inside
        // it rather than in a second card of their own.
        modifier = Modifier.semantics { contentDescription = stockItemLabel(row.name) },
        background = if (row.pinned) SmartieColors.PurpleTint else SmartieColors.Panel,
        accent = when (row.status) {
            StockStatus.OUT -> SmartieColors.Danger
            StockStatus.LOW -> SmartieColors.Warn
            StockStatus.IN -> null
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(dimens.gapXs)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(dimens.gapM)
            ) {
                // The picture slot. Only a row that says it has a photo asks
                // for one — a row with `hasPhoto: false` spends no read
                // discovering that, which is the whole point of the flag
                // sitting on the stock document.
                //
                // A row without one shows the way to add it **here**, in the
                // picture-shaped hole, rather than as another button below:
                // that is where somebody looks for a picture, and it costs
                // the card no height on a board built to be scanned.
                if (capabilities.photoView && row.record.hasPhoto) {
                    StockPhotoThumbnail(
                        image = rememberStockPhoto(row.record, actions.loadPhoto),
                        name = row.name,
                        onOpen = { actions.onOpenPhoto(row.record) }
                    )
                } else if (capabilities.photoManage) {
                    StockAddPhotoTile(
                        name = row.name,
                        onOpen = { actions.onOpenPhoto(row.record) }
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        row.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = SmartieColors.Ink
                    )
                    row.model.takeIf { it.isNotBlank() && it != row.name }?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = SmartieColors.Steel
                        )
                    }
                    if (row.record.reorderLevel > 0.0) {
                        Text(
                            "Reorder at ${Money.formatQuantity(row.record.reorderLevel)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = SmartieColors.Steel2
                        )
                    }
                    // Only when there is one: never an empty placeholder.
                    if (row.record.note.isNotBlank()) {
                        Text(
                            row.record.note,
                            style = MaterialTheme.typography.bodySmall,
                            color = SmartieColors.Ink2,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag(NOTE_TAG).padding(top = dimens.gapXs)
                        )
                    }
                    Row(
                        Modifier.padding(top = dimens.gapXs),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // From the stored quantity alone — a pending −5 must
                        // never make a row read Out of stock before anything
                        // has been taken.
                        when (row.status) {
                            StockStatus.OUT -> Tag("Out of stock", TagTone.DANGER)
                            StockStatus.LOW -> Tag("Low", TagTone.WARN)
                            StockStatus.IN -> Tag("In stock", TagTone.GREEN)
                        }
                        if (row.record.manual) Tag("Manual", TagTone.NEUTRAL)
                        if (row.pinned) Tag("Tracked", TagTone.PURPLE)
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "${Money.formatQuantity(row.quantity)} ${row.record.unit}",
                        style = MaterialTheme.typography.titleLarge,
                        color = SmartieColors.Ink
                    )
                    Text(
                        "in stock",
                        style = MaterialTheme.typography.labelMedium,
                        color = SmartieColors.Steel2
                    )
                }
            }

            if (row.hasPending) {
                Text(
                    pendingLine(row),
                    style = MaterialTheme.typography.labelMedium,
                    color = SmartieColors.Purple
                )
            }

            if (!capabilities.anyRowAction) return@Column

            // The controls belong to this card, below its own rule, rather
            // than in a second card that read as an unrelated thing.
            HorizontalDivider(color = SmartieColors.Rule)

            // **A flow, not a row.** A `Row` does not wrap: once its children
            // exceed the card's width the remainder are measured at zero and
            // clipped, and they stay in the semantics tree while being
            // invisible on the device — which is exactly how the Photo button
            // shipped in run #73 without anyone's test noticing. The stepper
            // alone is 130–134dp and each button 58–83dp, so on a 360dp phone
            // three controls already overflow. Anything added here must wrap
            // rather than disappear.
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(dimens.gapS),
                verticalArrangement = Arrangement.spacedBy(dimens.gapS)
            ) {
                if (capabilities.adjust) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CompactStepper(
                            value = Money.formatDelta(row.pending),
                            onDecrement = { actions.onDecrement(row.key) },
                            onIncrement = { actions.onIncrement(row.key) },
                            highlighted = row.hasPending,
                            modifier = Modifier.semantics {
                                contentDescription = "Change ${row.name} by one"
                            }
                        )
                        Text(
                            PENDING_CAPTION,
                            style = MaterialTheme.typography.labelMedium,
                            color = SmartieColors.Steel2
                        )
                    }
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
                if (capabilities.history) {
                    SmartieGhostButton(
                        text = "History",
                        onClick = onHistory,
                        modifier = Modifier.semantics {
                            contentDescription = "History for ${row.name}"
                        }
                    )
                }
            }

            if (row.hasPending && capabilities.adjust) {
                Row(
                    Modifier.fillMaxWidth(),
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

/**
 * The decoded photo for a row, fetched as the card comes into view.
 *
 * Keyed on the row's **identity and revision together**, so a replacement
 * starts a new load and a scroll past an unchanged row starts none. The
 * decode is on a worker thread because a WebP decode on the main thread is a
 * dropped frame per card.
 *
 * Call this only for a row that has a photo. A row with `hasPhoto: false`
 * must not reach here — not because it would be wrong, but because it would
 * be a load nobody asked for.
 */
@Composable
private fun rememberStockPhoto(
    record: StockRecord,
    load: suspend (StockRecord) -> ByteArray?
): ImageBitmap? {
    var image by remember(record.documentId, record.photoRev) {
        mutableStateOf<ImageBitmap?>(null)
    }
    LaunchedEffect(record.documentId, record.photoRev) {
        val bytes = load(record) ?: return@LaunchedEffect
        image = withContext(Dispatchers.Default) {
            StockImage.decodePreview(bytes)?.asImageBitmap()
        }
    }
    return image
}

/** One card holds one item: its details, its pending line and its controls. */
internal fun stockItemLabel(name: String): String = "Stock item $name"

/** "Pending change +5 · 14 each after Done". Never the stored quantity. */
internal fun pendingLine(row: StockRow): String =
    "$PENDING_CAPTION ${Money.formatDelta(row.pending)} · " +
        "${Money.formatQuantity(row.projected)} ${row.record.unit} after Done"

/** What happened to one item, newest first. A plain panel, not a dialog. */
@Composable
internal fun StockHistoryPanel(
    name: String,
    movements: List<StockMove>,
    onClose: () -> Unit
) {
    val dimens = LocalSmartieDimens.current
    Column(
        Modifier.heightIn(max = sheetBodyHeight()),
        verticalArrangement = Arrangement.spacedBy(dimens.gapS)
    ) {
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(dimens.gapS)
        ) {
            if (movements.isEmpty()) {
                Text(
                    "Nothing has moved yet.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = SmartieColors.Steel
                )
            }
            movements.forEach { move ->
                Column {
                    Text(
                        "${move.action.uppercase()} ${Money.formatDelta(move.delta)} · " +
                            "${Money.formatQuantity(move.previous)} → " +
                            Money.formatQuantity(move.next),
                        style = MaterialTheme.typography.titleSmall,
                        color = SmartieColors.Ink
                    )
                    val line = listOfNotNull(
                        move.by.takeIf { it.isNotBlank() },
                        move.note.takeIf { it.isNotBlank() }
                    ).joinToString(" · ")
                    if (line.isNotBlank()) {
                        Text(
                            line,
                            style = MaterialTheme.typography.labelMedium,
                            color = SmartieColors.Steel
                        )
                    }
                }
            }
        }
        SmartieGhostButton(
            text = "Close",
            onClick = onClose,
            modifier = Modifier.semantics { contentDescription = "Close history for $name" }
        )
    }
}

@Composable
private fun StockHistoryDialog(
    record: StockRecord,
    movements: List<StockMove>,
    onDismiss: () -> Unit
) {
    val name = StockBoard.displayName(record)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("History · $name") },
        text = { StockHistoryPanel(name = name, movements = movements, onClose = onDismiss) },
        confirmButton = {}
    )
}

@Composable
private fun EditStockDialog(
    record: StockRecord,
    capabilities: StockCapabilities,
    online: Boolean,
    onDismiss: () -> Unit,
    onSave: (Double, Double, String) -> Unit,
    onRemove: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = KEEP_WHAT_IS_TYPED,
        title = { Text("Edit ${StockBoard.displayName(record)}") },
        text = {
            EditStockPanel(
                record = record,
                capabilities = capabilities,
                online = online,
                onSave = onSave,
                onRemove = onRemove,
                onCancel = onDismiss
            )
        },
        // The panel carries its own actions, so the dialog holds none: that
        // keeps the whole body ordinary composable content.
        confirmButton = {}
    )
}

/**
 * The Edit body, deliberately **not** wrapped in a dialog.
 *
 * A Compose `Dialog` opens its own window with its own recomposer, which the
 * Robolectric test clock does not drive — `waitForIdle` then spins until
 * Espresso gives up, whatever the content is. Keeping the body a plain
 * composable is what makes it testable at all.
 */
@Composable
internal fun EditStockPanel(
    record: StockRecord,
    capabilities: StockCapabilities,
    online: Boolean,
    onSave: (Double, Double, String) -> Unit,
    onRemove: () -> Unit,
    onCancel: () -> Unit
) {
    var quantity by rememberSaveable(record.key) {
        mutableStateOf(Money.formatQuantity(record.quantity))
    }
    var reorder by rememberSaveable(record.key) {
        mutableStateOf(Money.formatQuantity(record.reorderLevel))
    }
    // Empty, never prefilled with the stored note: this field is also the
    // reason a movement carries, and the last note typed is not the reason
    // for the next change.
    var note by rememberSaveable(record.key) { mutableStateOf("") }
    val parsedQuantity = quantity.trim().toDoubleOrNull()
    val parsedReorder = reorder.trim().toDoubleOrNull()
    val valid = (parsedQuantity ?: record.quantity) >= 0.0 && (parsedReorder ?: 0.0) >= 0.0

    PanelWithActions(
        fields = {
            if (capabilities.exactQuantity) {
                SmartieField(
                    label = "Exact quantity",
                    value = quantity,
                    onValueChange = { quantity = it },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.semantics { contentDescription = "Exact quantity" }
                )
            } else {
                // Staff reach this for the reorder level and the note; the
                // rules reserve an exact quantity for an Administrator, so the
                // field is not offered at all rather than offered and refused.
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
        },
        header = {
            // Small, and out of the way of Save — removing an item is
            // permanent and should not sit under the thumb that was reaching
            // for the primary action. It is **visually** compact only: the
            // 48dp minimum touch target below is what a finger gets.
            if (capabilities.stopTracking) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    // The description and the touch target go on the **box**,
                    // outermost. A semantics node reports the bounds at its
                    // own position in the chain, and a `clickable` below a
                    // size only takes pointers over what is below it — so
                    // hanging either off the Text would give a 48dp target
                    // that is really the width of the words.
                    Box(
                        Modifier
                            .semantics {
                                contentDescription =
                                    if (online) REMOVE_FROM_STOCK else OFFLINE_LABEL
                            }
                            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                            .clickable(enabled = online, onClick = onRemove),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            REMOVE_FROM_STOCK,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (online) SmartieColors.Danger else SmartieColors.Steel2,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                }
            }
        },
        online = online,
        actions = {
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
            SmartieGhostButton(text = "Cancel", onClick = onCancel)
        }
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
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = KEEP_WHAT_IS_TYPED,
        title = { Text("Add stock") },
        text = {
            AddStockPanel(
                products = products,
                online = online,
                onAddProduct = onAddProduct,
                onAddManual = onAddManual,
                onCancel = onDismiss
            )
        },
        confirmButton = {}
    )
}

/** Which of the two things somebody is adding. */
internal enum class AddStockMode { CHOOSE, CATALOGUE, MANUAL }

/**
 * The Add body, in **two modes**.
 *
 * It used to show the catalogue search, its results and the manual-item
 * fields all at once, so it was never clear which half was being filled in
 * and the buttons sat below a list. Now the choice comes first and only the
 * fields belonging to it are shown.
 */
@Composable
internal fun AddStockPanel(
    products: List<ProductRecord>,
    online: Boolean,
    onAddProduct: (ProductRecord, Double, Double, String) -> Unit,
    onAddManual: (String, String, String, Double, Double, String) -> Unit,
    onCancel: () -> Unit
) {
    val focus = LocalFocusManager.current
    var mode by rememberSaveable { mutableStateOf(AddStockMode.CHOOSE) }
    var search by rememberSaveable { mutableStateOf("") }
    var chosenKey by rememberSaveable { mutableStateOf("") }
    var model by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var unit by rememberSaveable { mutableStateOf("each") }
    var quantity by rememberSaveable { mutableStateOf("0") }
    var reorder by rememberSaveable { mutableStateOf("0") }
    var note by rememberSaveable { mutableStateOf("") }

    val startingQuantity = quantity.trim().toDoubleOrNull() ?: 0.0
    val startingReorder = reorder.trim().toDoubleOrNull() ?: 0.0
    val chosen = remember(products, chosenKey) {
        products.firstOrNull { it.stockKey == chosenKey }
    }
    val matches = remember(products, search) {
        val needle = search.trim().lowercase()
        // Show the top of the catalogue straight away rather than an empty
        // box: most additions are the product somebody is already looking at.
        if (needle.isEmpty()) products.take(CATALOGUE_RESULTS)
        else products.asSequence()
            .filter {
                it.model.lowercase().contains(needle) || it.name.lowercase().contains(needle)
            }
            .take(CATALOGUE_RESULTS)
            .toList()
    }

    fun back() {
        focus.clearFocus(force = true)
        mode = AddStockMode.CHOOSE
        chosenKey = ""
        search = ""
    }

    when (mode) {
        AddStockMode.CHOOSE -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "What are you adding?",
                style = MaterialTheme.typography.titleSmall,
                color = SmartieColors.Ink
            )
            SmartiePrimaryButton(
                text = FROM_PRODUCTS,
                onClick = { mode = AddStockMode.CATALOGUE },
                modifier = Modifier.fillMaxWidth().semantics {
                    contentDescription = "Add stock from the product catalogue"
                }
            )
            SmartieGhostButton(
                text = MANUAL_ITEM,
                onClick = { mode = AddStockMode.MANUAL },
                modifier = Modifier.fillMaxWidth().semantics {
                    contentDescription = "Add a manual stock item"
                }
            )
            Text(
                "A catalogue product keeps its model and name; a manual item " +
                    "is anything not in the catalogue.",
                style = MaterialTheme.typography.labelMedium,
                color = SmartieColors.Steel
            )
            if (!online) OfflineNote()
            SmartieGhostButton(text = "Cancel", onClick = onCancel)
        }

        AddStockMode.CATALOGUE -> PanelWithActions(
            fields = {
                SectionHeader(FROM_PRODUCTS)
                if (chosen == null) {
                    SmartieField(
                        label = "Find a product",
                        value = search,
                        onValueChange = { search = it },
                        placeholder = "Model or name",
                        modifier = Modifier.semantics {
                            contentDescription = "Find a catalogue product"
                        }
                    )
                    if (products.isEmpty()) {
                        Text(
                            "The catalogue has not loaded yet.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = SmartieColors.Steel
                        )
                    } else if (matches.isEmpty()) {
                        Text(
                            "No product matches that.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = SmartieColors.Steel
                        )
                    } else {
                        // Bounded and scrollable, so however long the list
                        // is the buttons below stay where they are.
                        Column(
                            Modifier
                                .heightIn(max = sheetBodyHeight() * 0.44f)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            matches.forEach { product ->
                                CatalogueResultRow(
                                    product = product,
                                    onSelect = {
                                        // Taking the focus off the search box
                                        // puts the keyboard away, so the
                                        // fields below are not covered.
                                        focus.clearFocus(force = true)
                                        chosenKey = product.stockKey
                                    }
                                )
                            }
                        }
                        Text(
                            "Choose a product to continue.",
                            style = MaterialTheme.typography.labelMedium,
                            color = SmartieColors.Steel
                        )
                    }
                } else {
                    ChosenProductCard(product = chosen, onChange = { chosenKey = "" })
                    QuantityFields(
                        quantity = quantity,
                        onQuantity = { quantity = it },
                        reorder = reorder,
                        onReorder = { reorder = it },
                        note = note,
                        onNote = { note = it }
                    )
                }
            },
            online = online,
            actions = {
                if (chosen != null) {
                    SmartiePrimaryButton(
                        text = ADD_ITEM,
                        enabled = online,
                        onClick = {
                            onAddProduct(chosen, startingQuantity, startingReorder, note)
                        }
                    )
                }
                SmartieGhostButton(text = "Back", onClick = { back() })
                SmartieGhostButton(text = "Cancel", onClick = onCancel)
            }
        )

        AddStockMode.MANUAL -> PanelWithActions(
            fields = {
                SectionHeader(MANUAL_ITEM)
                SmartieField(
                    label = "Model or code",
                    value = model,
                    onValueChange = { model = it },
                    modifier = Modifier.semantics {
                        contentDescription = "Manual model or code"
                    }
                )
                SmartieField(
                    label = "Item name",
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.semantics { contentDescription = "Manual item name" }
                )
                SmartieField(
                    label = "Unit",
                    value = unit,
                    onValueChange = { unit = it },
                    modifier = Modifier.semantics { contentDescription = "Unit" }
                )
                QuantityFields(
                    quantity = quantity,
                    onQuantity = { quantity = it },
                    reorder = reorder,
                    onReorder = { reorder = it },
                    note = note,
                    onNote = { note = it }
                )
            },
            online = online,
            actions = {
                SmartiePrimaryButton(
                    text = ADD_ITEM,
                    enabled = online && (model.isNotBlank() || name.isNotBlank()),
                    onClick = {
                        onAddManual(model, name, unit, startingQuantity, startingReorder, note)
                    }
                )
                SmartieGhostButton(text = "Back", onClick = { back() })
                SmartieGhostButton(text = "Cancel", onClick = onCancel)
            }
        )
    }
}

/**
 * Fields that scroll above actions that do not.
 *
 * The actions are measured first and the fields get what is left, so however
 * much is above them Add item, Back and Cancel stay on screen.
 */
@Composable
private fun PanelWithActions(
    fields: @Composable () -> Unit,
    online: Boolean,
    actions: @Composable () -> Unit,
    /** Sits above the scrolling fields and stays put, for a small action. */
    header: @Composable () -> Unit = {}
) {
    Column(
        Modifier.heightIn(max = sheetBodyHeight()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        header()
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            fields()
        }
        if (!online) OfflineNote()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
    }
}

@Composable
private fun OfflineNote() {
    Text(
        OFFLINE_LABEL,
        style = MaterialTheme.typography.labelMedium,
        color = SmartieColors.Warn
    )
}

@Composable
private fun QuantityFields(
    quantity: String,
    onQuantity: (String) -> Unit,
    reorder: String,
    onReorder: (String) -> Unit,
    note: String,
    onNote: (String) -> Unit
) {
    SmartieField(
        label = "Starting quantity",
        value = quantity,
        onValueChange = onQuantity,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.semantics { contentDescription = "Starting quantity" }
    )
    SmartieField(
        label = "Reorder level",
        value = reorder,
        onValueChange = onReorder,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.semantics { contentDescription = "Reorder level" }
    )
    SmartieField(
        label = "Note",
        value = note,
        onValueChange = onNote,
        singleLine = false,
        placeholder = "Where it is kept, or why",
        modifier = Modifier.semantics { contentDescription = "Note" }
    )
}

/** One catalogue match: the model or code first, then the descriptive name. */
@Composable
private fun CatalogueResultRow(product: ProductRecord, onSelect: () -> Unit) {
    val dimens = LocalSmartieDimens.current
    val code = product.model.ifBlank { product.seedModel }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(dimens.radiusSmall))
            .background(SmartieColors.Panel2)
            .clickableNoRipple(onSelect)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .semantics { contentDescription = "Choose $code" }
    ) {
        Text(code, style = MaterialTheme.typography.titleSmall, color = SmartieColors.Ink)
        if (product.name.isNotBlank()) {
            Text(
                product.name,
                style = MaterialTheme.typography.bodySmall,
                color = SmartieColors.Steel,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** The product that has been picked, so there is no doubt which one it is. */
@Composable
private fun ChosenProductCard(product: ProductRecord, onChange: () -> Unit) {
    val code = product.model.ifBlank { product.seedModel }
    SmartieCard(background = SmartieColors.PurpleTint, accent = SmartieColors.Purple) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Tag(SELECTED_TAG, TagTone.PURPLE)
            Text(code, style = MaterialTheme.typography.titleSmall, color = SmartieColors.Ink)
            if (product.name.isNotBlank()) {
                Text(
                    product.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = SmartieColors.Steel
                )
            }
            SmartieGhostButton(
                text = "Change product",
                onClick = onChange,
                modifier = Modifier.semantics { contentDescription = "Change the chosen product" }
            )
        }
    }
}

/**
 * Neither back nor a tap outside dismisses a sheet holding typed values.
 *
 * The first staging pass lost a half-filled Add stock form to both. Cancel,
 * or a save that succeeds, is the only way out. While the keyboard is up
 * Android gives back to the keyboard first, so the first press still only
 * puts the keyboard away.
 */
internal val KEEP_WHAT_IS_TYPED = DialogProperties(
    dismissOnBackPress = false,
    dismissOnClickOutside = false
)

/** One wording, on every disabled control and in every refusal. */
const val OFFLINE_LABEL: String = StockViewModel.OFFLINE

/** Captions and labels the tests and the screen share. */
internal const val PENDING_CAPTION: String = "Pending change"
internal const val FROM_PRODUCTS: String = "From Products"
internal const val MANUAL_ITEM: String = "Manual Item"
internal const val ADD_ITEM: String = "Add item"
internal const val SELECTED_TAG: String = "Selected"
private const val CATALOGUE_RESULTS: Int = 20
