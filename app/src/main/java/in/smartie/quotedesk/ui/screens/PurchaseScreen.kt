package `in`.smartie.quotedesk.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.smartie.quotedesk.data.mapping.Money
import `in`.smartie.quotedesk.data.model.PurchaseRecord
import `in`.smartie.quotedesk.ui.components.EmptyState
import `in`.smartie.quotedesk.ui.components.ListRow
import `in`.smartie.quotedesk.ui.components.SectionHeader
import `in`.smartie.quotedesk.ui.components.SmartiePrimaryButton
import `in`.smartie.quotedesk.ui.components.Tag
import `in`.smartie.quotedesk.ui.components.TagTone
import `in`.smartie.quotedesk.ui.components.UrgencyTag
import `in`.smartie.quotedesk.ui.components.urgencyColour
import `in`.smartie.quotedesk.ui.purchase.ADD_REQUIREMENT
import `in`.smartie.quotedesk.ui.purchase.ADD_TITLE
import `in`.smartie.quotedesk.ui.purchase.AddRequirementPanel
import `in`.smartie.quotedesk.ui.purchase.EDIT_TITLE
import `in`.smartie.quotedesk.ui.purchase.EditRequirementPanel
import `in`.smartie.quotedesk.ui.purchase.MarkReceivedPanel
import `in`.smartie.quotedesk.ui.purchase.disabledLabel
import `in`.smartie.quotedesk.ui.purchase.PURCHASE_CONFIRM_PROPERTIES
import `in`.smartie.quotedesk.ui.purchase.PURCHASE_FORM_PROPERTIES
import `in`.smartie.quotedesk.ui.purchase.PurchaseActions
import `in`.smartie.quotedesk.ui.purchase.PurchaseCapabilities
import `in`.smartie.quotedesk.ui.purchase.PurchaseRowActions
import `in`.smartie.quotedesk.ui.purchase.PurchaseSheet
import `in`.smartie.quotedesk.ui.purchase.PurchaseSheetState
import `in`.smartie.quotedesk.ui.purchase.PurchaseViewModel
import `in`.smartie.quotedesk.ui.purchase.RECEIVE_TITLE
import `in`.smartie.quotedesk.ui.purchase.REMOVE_TITLE
import `in`.smartie.quotedesk.ui.purchase.REOPEN_TITLE
import `in`.smartie.quotedesk.ui.purchase.RemoveConfirmPanel
import `in`.smartie.quotedesk.ui.purchase.ReopenConfirmPanel
import `in`.smartie.quotedesk.ui.purchase.SetUrgencyPanel
import `in`.smartie.quotedesk.ui.purchase.URGENCY_TITLE
import `in`.smartie.quotedesk.ui.purchase.quantityLine
import `in`.smartie.quotedesk.ui.theme.LocalSmartieDimens
import `in`.smartie.quotedesk.ui.theme.SmartieColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Purchase requirements: what the shop floor still needs, and what has come.
 *
 * Everything a control does goes through [PurchaseViewModel] and the writer
 * behind it. The screen holds no state of its own — which panel is open lives
 * in the view model, so a rotation cannot lose it and a test can put the
 * board into any state without touching Firebase.
 */
@Composable
fun PurchaseScreen(viewModel: PurchaseViewModel) {
    val active by viewModel.active.collectAsStateWithLifecycle()
    val closed by viewModel.closed.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val online by viewModel.online.collectAsStateWithLifecycle()
    val sheet by viewModel.sheet.collectAsStateWithLifecycle()

    val actions = PurchaseActions(
        onAdd = viewModel::add,
        onEdit = viewModel::edit,
        onSetUrgency = viewModel::setUrgency,
        onReceive = viewModel::markReceived,
        onReopen = viewModel::reopen,
        onRemove = viewModel::remove,
        onOpen = viewModel::open,
        onDismiss = viewModel::dismiss
    )

    PurchaseBoardScreen(
        active = active,
        closed = closed,
        loading = loading,
        saving = saving,
        online = online,
        capabilities = viewModel.capabilities(),
        actions = actions
    )

    PurchaseSheets(sheet = sheet, online = online, saving = saving, actions = actions)
}

/**
 * The board itself, stateless over what it is given.
 *
 * **Two lists, and a removed requirement is in neither.** They come from
 * `PurchaseBoard`, which filters the second on `!deleted && isClosed` rather
 * than on `!isOpen`: `isOpen` is `!deleted && !isClosed`, so a removed
 * requirement that was never received is neither open nor closed, and
 * `filterNot { isOpen }` put it back on screen as history. That was this
 * screen's defect and it is gone with the `filterNot`.
 *
 * The sheets are hosted separately in [PurchaseSheets], so a test can drive
 * the board without a Compose `Dialog` in the composition.
 */
@Composable
internal fun PurchaseBoardScreen(
    active: List<PurchaseRecord> = emptyList(),
    closed: List<PurchaseRecord> = emptyList(),
    loading: Boolean = false,
    saving: Set<String> = emptySet(),
    online: Boolean = true,
    capabilities: PurchaseCapabilities = PurchaseCapabilities(),
    actions: PurchaseActions = PurchaseActions()
) {
    val dimens = LocalSmartieDimens.current

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = dimens.screenPadding,
            end = dimens.screenPadding,
            top = dimens.gapM,
            bottom = dimens.listBottomInset
        ),
        verticalArrangement = Arrangement.spacedBy(dimens.gapS)
    ) {
        // Everyone active may add one, Workers included — it is the one thing
        // a Worker may do here, so it goes first rather than under the list.
        if (capabilities.add) {
            item {
                SmartiePrimaryButton(
                    text = ADD_REQUIREMENT,
                    onClick = { actions.onOpen(PurchaseSheet.ADD, null) },
                    enabled = online,
                    modifier = Modifier
                        .semantics {
                            contentDescription =
                                if (online) ADD_REQUIREMENT else disabledLabel(ADD_REQUIREMENT)
                        }
                        .fillMaxWidth()
                )
            }
        }

        item { SectionHeader(OPEN_SECTION, trailing = active.size.toString()) }

        when {
            // An empty board and one that has not arrived are different
            // things, and saying "nothing is waiting" about a list nobody has
            // seen yet is a lie the shop floor would act on.
            loading && active.isEmpty() && closed.isEmpty() -> item { EmptyState(LOADING) }
            active.isEmpty() -> item { EmptyState(NOTHING_WAITING) }
        }

        items(active, key = { it.id }) { item ->
            PurchaseRow(
                item = item,
                capabilities = capabilities,
                online = online,
                saving = item.id in saving,
                actions = actions
            )
        }

        if (closed.isNotEmpty()) {
            item { SectionHeader(CLOSED_SECTION, trailing = closed.size.toString()) }
            items(closed, key = { it.id }) { item ->
                PurchaseRow(
                    item = item,
                    capabilities = capabilities,
                    online = online,
                    saving = item.id in saving,
                    actions = actions
                )
            }
        }
    }
}

/**
 * The six sheets, one at a time, from the view model's own state.
 *
 * **Back closes the sheet rather than leaving the tab**, on every one of
 * them: `PURCHASE_FORM_PROPERTIES` and `PURCHASE_CONFIRM_PROPERTIES` both set
 * `dismissOnBackPress`, and `onDismissRequest` goes to the same `onDismiss`
 * the Cancel button uses, so there is one way out and the view model always
 * knows the sheet has gone. A tap outside closes only a confirmation; the two
 * sheets holding typed values refuse it, because a miss should not throw away
 * what somebody typed.
 *
 * A requirement with a write in flight makes its own sheet busy, which is
 * what stops a second confirm reaching the repository.
 */
@Composable
internal fun PurchaseSheets(
    sheet: PurchaseSheetState = PurchaseSheetState(),
    online: Boolean = true,
    saving: Set<String> = emptySet(),
    actions: PurchaseActions = PurchaseActions()
) {
    val open = sheet.sheet ?: return
    val record = sheet.record
    val busy = when (open) {
        PurchaseSheet.ADD -> PurchaseViewModel.ADD_KEY in saving
        else -> record != null && record.id in saving
    }

    when (open) {
        PurchaseSheet.ADD -> PurchaseSheetDialog(ADD_TITLE, PURCHASE_FORM_PROPERTIES, actions) {
            AddRequirementPanel(online = online, saving = busy, actions = actions)
        }

        PurchaseSheet.EDIT -> record?.let {
            PurchaseSheetDialog(EDIT_TITLE, PURCHASE_FORM_PROPERTIES, actions) {
                EditRequirementPanel(record = it, online = online, saving = busy, actions = actions)
            }
        }

        PurchaseSheet.URGENCY -> record?.let {
            PurchaseSheetDialog(URGENCY_TITLE, PURCHASE_CONFIRM_PROPERTIES, actions) {
                SetUrgencyPanel(record = it, online = online, saving = busy, actions = actions)
            }
        }

        PurchaseSheet.RECEIVE -> record?.let {
            PurchaseSheetDialog(RECEIVE_TITLE, PURCHASE_FORM_PROPERTIES, actions) {
                MarkReceivedPanel(record = it, online = online, saving = busy, actions = actions)
            }
        }

        PurchaseSheet.REOPEN -> record?.let {
            PurchaseSheetDialog(REOPEN_TITLE, PURCHASE_CONFIRM_PROPERTIES, actions) {
                ReopenConfirmPanel(record = it, online = online, saving = busy, actions = actions)
            }
        }

        PurchaseSheet.REMOVE -> record?.let {
            PurchaseSheetDialog(REMOVE_TITLE, PURCHASE_CONFIRM_PROPERTIES, actions) {
                RemoveConfirmPanel(record = it, online = online, saving = busy, actions = actions)
            }
        }
    }
}

/** The wrapper, holding no logic: title, how it may be closed, and a panel. */
@Composable
private fun PurchaseSheetDialog(
    title: String,
    properties: DialogProperties,
    actions: PurchaseActions,
    body: @Composable () -> Unit
) {
    AlertDialog(
        onDismissRequest = actions.onDismiss,
        properties = properties,
        confirmButton = {},
        title = { Text(title) },
        text = { body() }
    )
}

/**
 * One requirement's card: what is needed, how urgently, why, and what this
 * person may do about it.
 *
 * **The note and the urgency are on the card, without opening Edit** — N3's
 * T-S25, which could not be run while nothing could create a requirement to
 * look at. The colour is filled rather than a 3dp accent bar, because the
 * first staging pass reported the bar as missing.
 *
 * Every parameter but the record is defaulted, so the rendering can be
 * asserted on its own.
 */
@Composable
internal fun PurchaseRow(
    item: PurchaseRecord,
    capabilities: PurchaseCapabilities = PurchaseCapabilities(),
    online: Boolean = true,
    saving: Boolean = false,
    actions: PurchaseActions = PurchaseActions()
) {
    ListRow(
        title = item.name,
        // Three figures once part of it has arrived — what was asked for,
        // what has come, and what is still to come. That a part delivery
        // leaves a requirement open is the whole point of it, and the card
        // has to say so where somebody is standing looking at the list.
        secondary = quantityLine(item),
        // A note is shown only when there is one: no empty placeholder.
        note = item.note.takeIf { it.isNotBlank() },
        meta = creatorLine(item),
        accent = urgencyColour(item.urgency),
        tags = {
            // Filled, not toned: the accent bar alone was invisible in use.
            UrgencyTag(item.urgency)
            Tag(item.status, if (item.isClosed) TagTone.NEUTRAL else TagTone.PURPLE)
        },
        trailing = {
            // Only on a closed card. On an open one the secondary line is
            // already carrying the received figure, and saying it twice in
            // two different wordings is how a card starts being misread.
            if (item.isClosed && item.receivedQuantity != null) {
                Text(
                    "${Money.formatQuantity(item.receivedTotal)} in",
                    style = MaterialTheme.typography.labelMedium,
                    color = SmartieColors.Steel
                )
            }
        },
        // Null, not an empty lambda: a Worker's card carries no control row
        // and must not carry its padding either.
        footer = if (capabilities.anyRowAction) {
            {
                PurchaseRowActions(
                    record = item,
                    capabilities = capabilities,
                    online = online,
                    saving = saving,
                    actions = actions
                )
            }
        } else {
            null
        }
    )
}

private fun creatorLine(item: PurchaseRecord): String? {
    val who = item.by.takeIf { it.isNotBlank() } ?: return null
    val created = item.createdAt.takeIf { it > 0 }?.let { formatDate(it) }
    return if (created != null) "Added by $who · $created" else "Added by $who"
}

private fun formatDate(millis: Long): String =
    SimpleDateFormat("d MMM yyyy", Locale.forLanguageTag("en-IN")).format(Date(millis))

// --- wording ---------------------------------------------------------------

internal const val OPEN_SECTION: String = "Open"
internal const val CLOSED_SECTION: String = "Received and closed"
internal const val NOTHING_WAITING: String = "Nothing is waiting to be bought."
internal const val LOADING: String = "Loading requirements…"
