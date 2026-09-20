package `in`.smartie.quotedesk.ui.purchase

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import `in`.smartie.quotedesk.data.mapping.Money
import `in`.smartie.quotedesk.data.model.PurchaseRecord
import `in`.smartie.quotedesk.data.model.UrgencyV2
import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.domain.Permissions
import `in`.smartie.quotedesk.domain.PurchaseDraft
import `in`.smartie.quotedesk.domain.PurchaseWrite
import `in`.smartie.quotedesk.ui.components.SmartieField
import `in`.smartie.quotedesk.ui.components.SmartieGhostButton
import `in`.smartie.quotedesk.ui.components.SmartiePrimaryButton
import `in`.smartie.quotedesk.ui.components.sheetBodyHeight
import `in`.smartie.quotedesk.ui.components.clickableNoRipple
import `in`.smartie.quotedesk.ui.components.urgencyColour
import `in`.smartie.quotedesk.ui.theme.LocalSmartieDimens
import `in`.smartie.quotedesk.ui.theme.SmartieColors

/**
 * Everything the Purchase tab puts in front of somebody, minus the tab
 * itself, which is the next batch.
 *
 * Panels rather than dialog bodies, for the reason recorded in
 * `docs/PROJECT-STATUS.md`: a Compose `Dialog` opens its own window with its
 * own recomposer, which the Robolectric test clock does not drive, so a test
 * that opens one spins until Espresso times out. Each wrapper holds no logic
 * and every panel here is driven directly by its test.
 *
 * **Six operations and no more.** There is no Cancel control and no way to
 * set a status directly: a status is moved only by the operation that owns
 * it. [PurchaseActions.onDismiss] closes a sheet — it is the sheet's own
 * Cancel button and writes nothing, which is not the same thing at all.
 */

/**
 * What a purchase surface can ask for, as one record of defaulted lambdas.
 *
 * Defaulted so a test can pass only the one it is asserting, and so adding a
 * seventh surface later cannot silently break a call site that does not care.
 */
data class PurchaseActions(
    val onAdd: (PurchaseDraft) -> Unit = {},
    val onEdit: (PurchaseRecord, String, Double, UrgencyV2, String) -> Unit =
        { _, _, _, _, _ -> },
    val onSetUrgency: (PurchaseRecord, UrgencyV2) -> Unit = { _, _ -> },
    val onReceive: (PurchaseRecord, Double) -> Unit = { _, _ -> },
    val onReopen: (PurchaseRecord) -> Unit = {},
    val onRemove: (PurchaseRecord) -> Unit = {},
    /** Open the panel for one of the above. The screen decides how. */
    val onOpen: (PurchaseSheet, PurchaseRecord?) -> Unit = { _, _ -> },
    /** Close whatever is open. Writes nothing; it is a Cancel button. */
    val onDismiss: () -> Unit = {}
)

/** Which panel a surface is asking to open. */
enum class PurchaseSheet { ADD, EDIT, URGENCY, RECEIVE, REOPEN, REMOVE }

/**
 * What this person may do to a requirement, in **one** place.
 *
 * The same shape as `StockCapabilities`, and for the same reason: the mapping
 * from a role to a set of controls is the thing that decides whether a
 * control is rendered at all, so it gets a test of its own per role rather
 * than being reassembled inline by each surface.
 */
data class PurchaseCapabilities(
    /** Everybody active, Workers included. */
    val add: Boolean = false,
    val edit: Boolean = false,
    val receive: Boolean = false,
    /** **Owner and Administrator only**, and the app is the only enforcement. */
    val reopen: Boolean = false,
    val remove: Boolean = false
) {
    /** Whether a card needs a control row at all. A Worker's does not. */
    val anyRowAction: Boolean get() = edit || receive || reopen || remove

    companion object {
        fun forMember(member: Member): PurchaseCapabilities = PurchaseCapabilities(
            add = Permissions.canAddPurchase(member),
            edit = Permissions.canEditPurchase(member),
            receive = Permissions.canSetPurchaseStatus(member),
            reopen = Permissions.canReopenPurchase(member),
            remove = Permissions.canDeletePurchase(member)
        )
    }
}

// --- adding ---------------------------------------------------------------

/**
 * A new requirement: what is needed, how many, how urgently, and why.
 *
 * Validation is [PurchaseDraft.refusal], the same function the planner uses,
 * so the sentence somebody reads here cannot drift from the one that would
 * come back from a write.
 */
@Composable
internal fun AddRequirementPanel(
    online: Boolean = true,
    saving: Boolean = false,
    actions: PurchaseActions = PurchaseActions()
) {
    var name by rememberSaveable { mutableStateOf("") }
    var quantity by rememberSaveable { mutableStateOf("1") }
    var note by rememberSaveable { mutableStateOf("") }
    var urgency by rememberSaveable { mutableStateOf(UrgencyV2.NORMAL) }

    val draft = PurchaseDraft(
        name = name,
        quantity = quantity.trim().toDoubleOrNull() ?: 0.0,
        urgency = urgency,
        note = note
    )
    val refusal = draft.refusal()

    PurchaseFormPanel(
        online = online,
        fields = {
            SmartieField(
                label = NAME_LABEL,
                value = name,
                onValueChange = { name = it },
                placeholder = NAME_PLACEHOLDER,
                enabled = !saving,
                modifier = Modifier.semantics { contentDescription = NAME_LABEL }
            )
            SmartieField(
                label = QUANTITY_LABEL,
                value = quantity,
                onValueChange = { quantity = it },
                enabled = !saving,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.semantics { contentDescription = QUANTITY_LABEL }
            )
            UrgencyChoice(
                selected = urgency,
                enabled = !saving,
                onSelect = { urgency = it }
            )
            SmartieField(
                label = NOTE_LABEL,
                value = note,
                onValueChange = { note = it },
                placeholder = NOTE_PLACEHOLDER,
                enabled = !saving,
                singleLine = false,
                modifier = Modifier.semantics { contentDescription = NOTE_LABEL }
            )
            // Only once something has been typed: an empty form that is
            // already complaining reads as broken rather than as guidance.
            if (refusal != null && name.isNotBlank()) {
                Text(
                    refusal,
                    style = MaterialTheme.typography.labelMedium,
                    color = SmartieColors.Warn
                )
            }
        },
        actions = {
            SmartieGhostButton(text = CANCEL, onClick = actions.onDismiss, enabled = !saving)
            SmartiePrimaryButton(
                text = if (saving) ADDING else ADD_REQUIREMENT,
                onClick = { if (refusal == null) actions.onAdd(draft) },
                enabled = online && !saving && refusal == null,
                modifier = Modifier.semantics { contentDescription = CONFIRM_ADD }
            )
        }
    )
}

// --- changing one ---------------------------------------------------------

/**
 * The Edit sheet, and **the way out of a broken stored quantity**.
 *
 * A V8C4 row holding `"qty": "10"` as a string cannot be updated at all until
 * a write rewrites it as a number, and every other operation refuses such a
 * row by name. This one does not, which is why it is the rescue — so the
 * quantity field is pre-filled with something usable rather than with the
 * unusable stored value.
 */
@Composable
internal fun EditRequirementPanel(
    record: PurchaseRecord,
    online: Boolean = true,
    saving: Boolean = false,
    actions: PurchaseActions = PurchaseActions()
) {
    var name by rememberSaveable(record.id) { mutableStateOf(record.name) }
    var quantity by rememberSaveable(record.id) {
        mutableStateOf(
            if (record.quantity > 0.0) Money.formatQuantity(record.quantity) else "1"
        )
    }
    var note by rememberSaveable(record.id) { mutableStateOf(record.note) }
    var urgency by rememberSaveable(record.id) { mutableStateOf(record.urgency) }

    val parsed = quantity.trim().toDoubleOrNull() ?: 0.0
    val refusal = PurchaseDraft(name = name, quantity = parsed, urgency = urgency, note = note)
        .refusal()

    PurchaseFormPanel(
        online = online,
        fields = {
            SmartieField(
                label = NAME_LABEL,
                value = name,
                onValueChange = { name = it },
                enabled = !saving,
                modifier = Modifier.semantics { contentDescription = NAME_LABEL }
            )
            SmartieField(
                label = QUANTITY_LABEL,
                value = quantity,
                onValueChange = { quantity = it },
                enabled = !saving,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.semantics { contentDescription = QUANTITY_LABEL }
            )
            UrgencyChoice(
                selected = urgency,
                enabled = !saving,
                onSelect = { urgency = it }
            )
            SmartieField(
                label = NOTE_LABEL,
                value = note,
                onValueChange = { note = it },
                enabled = !saving,
                singleLine = false,
                modifier = Modifier.semantics { contentDescription = NOTE_LABEL }
            )
            if (refusal != null) {
                Text(
                    refusal,
                    style = MaterialTheme.typography.labelMedium,
                    color = SmartieColors.Warn
                )
            }
        },
        actions = {
            SmartieGhostButton(text = CANCEL, onClick = actions.onDismiss, enabled = !saving)
            SmartiePrimaryButton(
                text = if (saving) SAVING else SAVE,
                onClick = {
                    if (refusal == null) actions.onEdit(record, name, parsed, urgency, note)
                },
                enabled = online && !saving && refusal == null,
                modifier = Modifier.semantics { contentDescription = CONFIRM_EDIT }
            )
        }
    )
}

/** Just the urgency, without opening the whole Edit sheet. */
@Composable
internal fun SetUrgencyPanel(
    record: PurchaseRecord,
    online: Boolean = true,
    saving: Boolean = false,
    actions: PurchaseActions = PurchaseActions()
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        UrgencyChoice(
            selected = record.urgency,
            enabled = online && !saving,
            onSelect = { actions.onSetUrgency(record, it) }
        )
        if (!online) OfflineNote()
        SmartieGhostButton(text = CANCEL, onClick = actions.onDismiss, enabled = !saving)
    }
}

// --- closing and reopening ------------------------------------------------

/**
 * A delivery arrived, and it is very often **not** the whole order.
 *
 * The field asks for the quantity that came **in this delivery**, so all
 * three figures are put in front of the person first: what was asked for,
 * what has already arrived, and what is still to come. It defaults to the
 * outstanding quantity — the common case is the rest of the order arriving —
 * and anything smaller and positive is accepted.
 *
 * Both refusals are the planner's own sentences rather than a second wording
 * of the same rules, so what is read here cannot drift from what a write
 * would come back with. The write still decides against the **stored**
 * document; this is only what stops somebody typing a figure that was never
 * going to be accepted.
 */
@Composable
internal fun MarkReceivedPanel(
    record: PurchaseRecord,
    online: Boolean = true,
    saving: Boolean = false,
    actions: PurchaseActions = PurchaseActions()
) {
    val outstanding = record.remaining
    var received by rememberSaveable(record.id) {
        mutableStateOf(if (outstanding > 0.0) Money.formatQuantity(outstanding) else "")
    }
    val parsed = received.trim().toDoubleOrNull() ?: 0.0
    val refusal = when {
        received.isBlank() -> null
        parsed <= 0.0 -> PurchaseWrite.NOT_POSITIVE
        parsed > outstanding + PurchaseRecord.QUANTITY_TOLERANCE ->
            PurchaseWrite.moreThanRemaining(outstanding)
        else -> null
    }
    val usable = parsed > 0.0 && refusal == null

    PurchaseFormPanel(
        online = online,
        fields = {
            ReceiptSummary(record)
            SmartieField(
                label = RECEIVED_LABEL,
                value = received,
                onValueChange = { received = it },
                enabled = !saving,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.semantics { contentDescription = RECEIVED_LABEL }
            )
            if (refusal != null) {
                Text(
                    refusal,
                    style = MaterialTheme.typography.labelMedium,
                    color = SmartieColors.Warn
                )
            }
        },
        actions = {
            SmartieGhostButton(text = CANCEL, onClick = actions.onDismiss, enabled = !saving)
            SmartiePrimaryButton(
                text = if (saving) RECEIVING else MARK_RECEIVED,
                onClick = { if (usable) actions.onReceive(record, parsed) },
                enabled = online && !saving && usable,
                modifier = Modifier.semantics { contentDescription = CONFIRM_RECEIVE }
            )
        }
    )
}

/**
 * The three figures a part delivery turns on, each on its own line.
 *
 * Merged into one semantics node per line, so a test reads "Already received
 * 5" as one sentence rather than hunting two adjacent nodes — and so a screen
 * reader says the label with its figure instead of a bare number.
 */
@Composable
private fun ReceiptSummary(record: PurchaseRecord) {
    val dimens = LocalSmartieDimens.current
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(dimens.gapXs)
    ) {
        SummaryLine(TOTAL_REQUIRED, record.quantity)
        SummaryLine(ALREADY_RECEIVED, record.receivedTotal)
        SummaryLine(REMAINING, record.remaining)
    }
}

@Composable
private fun SummaryLine(label: String, quantity: Double) {
    Row(
        modifier = Modifier
            .semantics(mergeDescendants = true) {
                contentDescription = summaryLine(label, quantity)
            }
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = SmartieColors.Steel)
        Text(
            Money.formatQuantity(quantity),
            style = MaterialTheme.typography.bodyLarge,
            color = SmartieColors.Ink
        )
    }
}

/**
 * Back to the active list. **Owner and Administrator only**, and the panel
 * says what it will throw away, because the received quantity does not come
 * back.
 */
@Composable
internal fun ReopenConfirmPanel(
    record: PurchaseRecord,
    online: Boolean = true,
    saving: Boolean = false,
    actions: PurchaseActions = PurchaseActions()
) {
    ConfirmPanel(
        question = REOPEN_WARNING,
        detail = record.receivedBy.takeIf { it.isNotBlank() }?.let { "Received by $it" },
        confirmText = if (saving) REOPENING else REOPEN,
        confirmDescription = CONFIRM_REOPEN,
        online = online,
        saving = saving,
        onConfirm = { actions.onReopen(record) },
        onDismiss = actions.onDismiss
    )
}

// --- removing -------------------------------------------------------------

/**
 * A soft delete, and never a hard one: the document survives so a PWA device
 * cannot bring the row back, and nobody sees it again. The wording says
 * "remove" rather than "delete" for exactly that reason.
 */
@Composable
internal fun RemoveConfirmPanel(
    record: PurchaseRecord,
    online: Boolean = true,
    saving: Boolean = false,
    actions: PurchaseActions = PurchaseActions()
) {
    ConfirmPanel(
        question = REMOVE_WARNING,
        detail = record.name.takeIf { it.isNotBlank() },
        confirmText = if (saving) REMOVING else REMOVE,
        confirmDescription = CONFIRM_REMOVE,
        online = online,
        saving = saving,
        onConfirm = { actions.onRemove(record) },
        onDismiss = actions.onDismiss
    )
}

// --- the controls on a card -----------------------------------------------

/**
 * The actions offered for one requirement, gated by role.
 *
 * A control this person may not use is **absent**, not disabled: a Worker
 * gets no row action at all, and a Manager is never shown Reopen or Remove.
 * Reopen is the one restriction the v9 rules cannot express — a reopen is an
 * ordinary update, which any non-Worker may perform — so this gate and
 * `Permissions.canReopenPurchase` behind it are the whole of the enforcement.
 *
 * A [FlowRow] rather than a `Row`, deliberately: a Compose `Row` measures
 * children past the available width at zero and clips them while leaving
 * them in the semantics tree, which is how a control once shipped invisible
 * with every test green.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PurchaseRowActions(
    record: PurchaseRecord,
    capabilities: PurchaseCapabilities = PurchaseCapabilities(),
    online: Boolean = true,
    saving: Boolean = false,
    actions: PurchaseActions = PurchaseActions()
) {
    if (!capabilities.anyRowAction) return
    val dimens = LocalSmartieDimens.current
    val open = record.isOpen

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(dimens.gapS),
        verticalArrangement = Arrangement.spacedBy(dimens.gapXs)
    ) {
        if (open && capabilities.edit) {
            RowAction(EDIT, PurchaseSheet.EDIT, record, online, saving, actions)
            RowAction(URGENCY, PurchaseSheet.URGENCY, record, online, saving, actions)
        }
        if (open && capabilities.receive) {
            RowAction(MARK_RECEIVED, PurchaseSheet.RECEIVE, record, online, saving, actions)
        }
        // Only a closed requirement can come back, and only for the two roles
        // the rules cannot be made to check.
        if (!open && capabilities.reopen) {
            RowAction(REOPEN, PurchaseSheet.REOPEN, record, online, saving, actions)
        }
        if (capabilities.remove) {
            RowAction(REMOVE, PurchaseSheet.REMOVE, record, online, saving, actions, danger = true)
        }
    }
}

/** One card control: 48dp of something to hit, and 48dp is what it measures. */
@Composable
private fun RowAction(
    text: String,
    sheet: PurchaseSheet,
    record: PurchaseRecord,
    online: Boolean,
    saving: Boolean,
    actions: PurchaseActions,
    danger: Boolean = false
) {
    val description = rowActionLabel(sheet, record.name)
    SmartieGhostButton(
        text = text,
        onClick = { actions.onOpen(sheet, record) },
        enabled = online && !saving,
        danger = danger,
        // Semantics **outermost**: a semantics node reports the bounds at its
        // own position in the chain, so one placed under the sizing would
        // describe the words rather than the target they sit in.
        modifier = Modifier
            .semantics {
                contentDescription =
                    if (online) description else disabledLabel(description)
            }
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
    )
}

// --- the urgency picker ---------------------------------------------------

/**
 * The three urgencies, **as three rows rather than three segments**.
 *
 * A segmented control divides 360dp by three and clips what will not fit at
 * `maxLines = 1`; "Needed, but not now" and "Can wait 1-2 days" both lose
 * their ends there, and a half-read label on the control that decides how
 * badly something is needed is not acceptable. Each row is its own 48dp
 * target carrying the full wording and the colour it will show on the card.
 */
@Composable
internal fun UrgencyChoice(
    selected: UrgencyV2,
    enabled: Boolean = true,
    onSelect: (UrgencyV2) -> Unit = {}
) {
    val dimens = LocalSmartieDimens.current
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(dimens.gapXs)
    ) {
        Text(
            URGENCY_LABEL,
            style = MaterialTheme.typography.labelMedium,
            color = SmartieColors.Steel
        )
        UrgencyV2.entries.forEach { option ->
            val chosen = option == selected
            val tint = urgencyColour(option)
            Box(
                Modifier
                    .semantics { contentDescription = urgencyOptionLabel(option) }
                    .fillMaxWidth()
                    .sizeIn(minHeight = 48.dp)
                    .clip(RoundedCornerShape(dimens.radiusSmall))
                    .background(if (chosen) tint.copy(alpha = 0.12f) else SmartieColors.Panel)
                    .border(
                        dimens.hairline,
                        if (chosen) tint else SmartieColors.Rule,
                        RoundedCornerShape(dimens.radiusSmall)
                    )
                    .then(if (enabled) Modifier.clickableNoRipple { onSelect(option) } else Modifier)
                    .padding(horizontal = dimens.gapM),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    // The Owner's exact wording, straight off the enum, so the
                    // picker and the card can never disagree about a label.
                    option.label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (chosen) tint else SmartieColors.Ink
                )
            }
        }
    }
}

// --- shared shapes --------------------------------------------------------

@Composable
private fun PurchaseFormPanel(
    online: Boolean,
    fields: @Composable () -> Unit,
    actions: @Composable () -> Unit
) {
    Column(
        // A share of the window, never a fixed figure: a Material dialog clips
        // what does not fit rather than scrolling it, and with the keyboard up
        // every phone is a short one. The fields scroll; the actions do not.
        Modifier.heightIn(max = sheetBodyHeight()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
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
private fun ConfirmPanel(
    question: String,
    detail: String?,
    confirmText: String,
    confirmDescription: String,
    online: Boolean,
    saving: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(question, style = MaterialTheme.typography.bodyLarge, color = SmartieColors.Ink)
        if (detail != null) {
            Text(detail, style = MaterialTheme.typography.labelMedium, color = SmartieColors.Steel)
        }
        if (!online) OfflineNote()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmartieGhostButton(text = CANCEL, onClick = onDismiss, enabled = !saving)
            SmartiePrimaryButton(
                text = confirmText,
                onClick = onConfirm,
                enabled = online && !saving,
                modifier = Modifier.semantics { contentDescription = confirmDescription }
            )
        }
    }
}

@Composable
private fun OfflineNote() {
    Text(OFFLINE, style = MaterialTheme.typography.labelMedium, color = SmartieColors.Warn)
}

// --- wording --------------------------------------------------------------

internal const val OFFLINE: String = "Internet required to change a requirement"

// --- how a purchase sheet may be closed ----------------------------------

/**
 * **Back closes a purchase sheet**, and an accidental tap outside does not.
 *
 * Both halves are deliberate and they answer different risks. Back is a
 * person saying "not this" and must close the panel rather than leave the
 * tab, so it is allowed on every sheet here — the stock sheets refuse it,
 * and this is the one place the two differ. A tap outside is usually a miss,
 * and on the two sheets that hold typed values a miss would throw the typing
 * away, so those are not dismissible that way.
 *
 * With the keyboard up the system takes the first Back to close the keyboard,
 * exactly as N3's T-S21 recorded for stock, so nothing typed is lost to a
 * single press.
 */
internal val PURCHASE_FORM_PROPERTIES: DialogProperties = DialogProperties(
    dismissOnBackPress = true,
    dismissOnClickOutside = false
)

/** A confirmation holds nothing typed, so a tap outside may close it too. */
internal val PURCHASE_CONFIRM_PROPERTIES: DialogProperties = DialogProperties(
    dismissOnBackPress = true,
    dismissOnClickOutside = true
)

// --- sheet titles ---------------------------------------------------------

internal const val ADD_TITLE: String = "Add a requirement"
internal const val EDIT_TITLE: String = "Edit requirement"
internal const val URGENCY_TITLE: String = "How urgently is it needed?"
internal const val RECEIVE_TITLE: String = "Mark as received"
internal const val REOPEN_TITLE: String = "Reopen this requirement?"
internal const val REMOVE_TITLE: String = "Remove this requirement?"

internal const val CANCEL: String = "Cancel"

internal const val NAME_LABEL: String = "What is needed"
internal const val NAME_PLACEHOLDER: String = "Sliding gate rack 1 m"
internal const val QUANTITY_LABEL: String = "How many"
internal const val NOTE_LABEL: String = "Note"
internal const val NOTE_PLACEHOLDER: String = "Where it is for, or anything useful"
internal const val URGENCY_LABEL: String = "How urgently"

/** "…now", because the field means this delivery and not the running total. */
internal const val RECEIVED_LABEL: String = "How many arrived now"

internal const val TOTAL_REQUIRED: String = "Total required"
internal const val ALREADY_RECEIVED: String = "Already received"
internal const val REMAINING: String = "Remaining"

internal const val ADD_REQUIREMENT: String = "Add requirement"
internal const val ADDING: String = "Adding…"
internal const val SAVE: String = "Save"
internal const val SAVING: String = "Saving…"
internal const val EDIT: String = "Edit"
internal const val URGENCY: String = "Urgency"
internal const val MARK_RECEIVED: String = "Received"
internal const val RECEIVING: String = "Receiving…"
internal const val REOPEN: String = "Reopen"
internal const val REOPENING: String = "Reopening…"
internal const val REMOVE: String = "Remove"
internal const val REMOVING: String = "Removing…"

internal const val REOPEN_WARNING: String =
    "Put this back on the active list? The received quantity and who received " +
        "it will be cleared."

internal const val REMOVE_WARNING: String =
    "Remove this requirement? It leaves the active list and the history, and " +
        "there is no way to bring it back."

/** Named so a test finds the confirm rather than the control that opened it. */
internal const val CONFIRM_ADD: String = "Confirm adding this requirement"
internal const val CONFIRM_EDIT: String = "Confirm the changes to this requirement"
internal const val CONFIRM_RECEIVE: String = "Confirm this requirement has arrived"
internal const val CONFIRM_REOPEN: String = "Confirm reopening this requirement"
internal const val CONFIRM_REMOVE: String = "Confirm removing this requirement"

/**
 * One figure from the receive panel's summary, label and all.
 *
 * Shared with the tests, so the wording is asserted where it is written
 * rather than copied into an assertion that can drift away from it.
 */
internal fun summaryLine(label: String, quantity: Double): String =
    "$label ${Money.formatQuantity(quantity)}"

/**
 * What a card says about quantities.
 *
 * Three figures **only once part of it has arrived**. A requirement nobody
 * has delivered against reads "10 needed", because "10 required · 0 received
 * · 10 remaining" is three ways of saying one thing, on every card, for the
 * ordinary case. A part delivery is the case worth the width.
 */
internal fun quantityLine(record: PurchaseRecord): String =
    if (record.isOpen && record.receivedTotal > 0.0) {
        "${Money.formatQuantity(record.quantity)} required · " +
            "${Money.formatQuantity(record.receivedTotal)} received · " +
            "${Money.formatQuantity(record.remaining)} remaining"
    } else {
        "${Money.formatQuantity(record.quantity)} needed"
    }

/** So a test can click an urgency without depending on where its words wrap. */
internal fun urgencyOptionLabel(urgency: UrgencyV2): String = "Urgency ${urgency.label}"

/**
 * A control's own description, **and then** why it cannot be used.
 *
 * Not the reason on its own: offline, every control on every card would
 * announce the same sentence, and somebody working by ear could not tell
 * Edit from Remove — or one requirement's Remove from another's. The name
 * comes first because that is what identifies the control; the reason
 * follows because that is what has changed.
 */
internal fun disabledLabel(description: String): String = "$description — $OFFLINE"

/**
 * What a screen reader says about a card control.
 *
 * A sentence naming the requirement, because "Remove" on its own, read out
 * halfway down a list of them, says nothing about which one.
 */
internal fun rowActionLabel(sheet: PurchaseSheet, name: String): String = when (sheet) {
    PurchaseSheet.ADD -> ADD_REQUIREMENT
    PurchaseSheet.EDIT -> "Edit $name"
    PurchaseSheet.URGENCY -> "Change how urgently $name is needed"
    PurchaseSheet.RECEIVE -> "Mark $name as received"
    PurchaseSheet.REOPEN -> "Reopen $name"
    PurchaseSheet.REMOVE -> "Remove $name"
}
