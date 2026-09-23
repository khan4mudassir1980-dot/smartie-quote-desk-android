package `in`.smartie.quotedesk.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import `in`.smartie.quotedesk.data.model.PartyRecord
import `in`.smartie.quotedesk.domain.PartyDraft
import `in`.smartie.quotedesk.domain.PartyMatch
import `in`.smartie.quotedesk.domain.PartyWrite
import `in`.smartie.quotedesk.domain.Parties
import `in`.smartie.quotedesk.ui.components.SegmentedChoice
import `in`.smartie.quotedesk.ui.components.SmartieCard
import `in`.smartie.quotedesk.ui.components.SmartieField
import `in`.smartie.quotedesk.ui.components.SmartieGhostButton
import `in`.smartie.quotedesk.ui.components.SmartiePrimaryButton
import `in`.smartie.quotedesk.ui.theme.LocalSmartieDimens
import `in`.smartie.quotedesk.ui.theme.SmartieColors

/**
 * Adding a party, and correcting one.
 *
 * **What is on screen is what is stored.** Emptying a box and saving takes
 * the value off the customer — which is how a GSTIN entered against the wrong
 * firm comes off it. The quotation side's "Save this customer", arriving in
 * N5.8, does the opposite and never blanks anything; `PartyWrite` holds both
 * and says which is which.
 *
 * **The form offers only what this person may actually save.** A Manager may
 * correct every detail but may neither rename nor archive, so the name is
 * shown as a fact rather than a field and no archive control is drawn. That
 * is not politeness: the rules refuse both, and a control that produces a
 * permission error is worse than no control.
 */
@Composable
internal fun PartyEditPanel(
    initial: PartyDraft,
    onSave: (PartyDraft) -> Unit,
    onCancel: () -> Unit,
    capabilities: PartyCapabilities,
    /** Null when creating. */
    editing: PartyRecord? = null,
    duplicate: PartyMatch? = null,
    onOpenDuplicate: (PartyRecord) -> Unit = {},
    saving: Boolean = false,
    error: String? = null
) {
    val dimens = LocalSmartieDimens.current
    val creating = editing == null

    // The fields are the panel's own, and saveable: a half-typed party must
    // survive a rotation. Keyed on the party being edited so opening a
    // different one does not inherit the last one's boxes.
    val key = editing?.id ?: "new"
    var name by rememberSaveable(key) { mutableStateOf(initial.name) }
    var type by rememberSaveable(key) { mutableStateOf(initial.type) }
    var contact by rememberSaveable(key) { mutableStateOf(initial.contact) }
    var phone by rememberSaveable(key) { mutableStateOf(initial.phone) }
    var email by rememberSaveable(key) { mutableStateOf(initial.email) }
    var gstin by rememberSaveable(key) { mutableStateOf(initial.gstin) }
    var city by rememberSaveable(key) { mutableStateOf(initial.city) }
    var address by rememberSaveable(key) { mutableStateOf(initial.address) }
    var notes by rememberSaveable(key) { mutableStateOf(initial.notes) }

    val draft = PartyDraft(
        name = name, type = type, city = city, gstin = gstin, contact = contact,
        phone = phone, email = email, address = address, notes = notes
    )
    // A Manager sees the name it already has, and cannot change it.
    val nameIsEditable = creating || capabilities.canRename

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
        item(key = "heading") {
            Text(
                if (creating) ADD_HEADING else EDIT_HEADING,
                style = MaterialTheme.typography.titleMedium,
                color = SmartieColors.Ink
            )
        }

        if (duplicate != null) {
            item(key = "duplicate") { DuplicateWarning(duplicate, onOpenDuplicate) }
        }

        if (error != null) {
            item(key = "error") {
                Text(error, style = MaterialTheme.typography.bodyMedium, color = SmartieColors.Danger)
            }
        }

        item(key = "name") {
            if (nameIsEditable) {
                Field(NAME_LABEL, name, saving) { name = it }
            } else {
                SmartieCard {
                    Column {
                        Text(
                            NAME_LABEL,
                            style = MaterialTheme.typography.labelSmall,
                            color = SmartieColors.Steel
                        )
                        Text(
                            name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = SmartieColors.Ink
                        )
                        Text(
                            NAME_IS_LOCKED,
                            style = MaterialTheme.typography.bodySmall,
                            color = SmartieColors.Steel
                        )
                    }
                }
            }
        }

        item(key = "type") {
            Column(verticalArrangement = Arrangement.spacedBy(dimens.gapXs)) {
                Text(
                    TYPE_LABEL,
                    style = MaterialTheme.typography.labelMedium,
                    color = SmartieColors.Steel
                )
                SegmentedChoice(
                    options = PartyWrite.TYPES,
                    selected = PartyWrite.normaliseType(type),
                    label = { it.replaceFirstChar(Char::titlecase) },
                    onSelect = { type = it }
                )
            }
        }

        item(key = "contact") {
            Field(CONTACT_LABEL, contact, saving) { contact = it }
        }
        item(key = "phone") {
            Field(PHONE_LABEL, phone, saving) { phone = it }
        }
        item(key = "email") {
            Field(EMAIL_LABEL, email, saving) { email = it }
        }
        item(key = "gstin") {
            Field(GSTIN_LABEL, gstin, saving) { gstin = it }
        }
        item(key = "city") {
            Field(CITY_LABEL, city, saving) { city = it }
        }
        item(key = "address") {
            Field(ADDRESS_LABEL, address, saving) { address = it }
        }
        item(key = "notes") {
            Field(NOTES_LABEL, notes, saving) { notes = it }
        }

        item(key = "save") {
            SmartiePrimaryButton(
                text = if (creating) SAVE_NEW else SAVE_CHANGES,
                onClick = { onSave(draft) },
                enabled = !saving,
                modifier = Modifier
                    .semantics { contentDescription = if (creating) SAVE_NEW else SAVE_CHANGES }
                    .fillMaxWidth()
            )
        }
        item(key = "cancel") {
            SmartieGhostButton(
                text = CANCEL,
                onClick = onCancel,
                modifier = Modifier
                    .semantics { contentDescription = CANCEL }
                    .fillMaxWidth()
            )
        }

        item(key = "clearing") {
            Text(
                CLEARING_NOTE,
                style = MaterialTheme.typography.bodySmall,
                color = SmartieColors.Steel
            )
        }
    }
}

/**
 * The party this one would duplicate, named, with a way to open it instead.
 *
 * It says **why** it thinks so — the same GSTIN, the same phone number, the
 * same name — because a warning that cannot justify itself gets dismissed.
 */
@Composable
private fun DuplicateWarning(match: PartyMatch, onOpen: (PartyRecord) -> Unit) {
    val dimens = LocalSmartieDimens.current
    SmartieCard(background = SmartieColors.WarnSoft, borderColor = SmartieColors.WarnLine) {
        Column(verticalArrangement = Arrangement.spacedBy(dimens.gapXs)) {
            Text(
                duplicateHeadline(match),
                style = MaterialTheme.typography.titleSmall,
                color = SmartieColors.Ink
            )
            Text(
                DUPLICATE_ADVICE,
                style = MaterialTheme.typography.bodySmall,
                color = SmartieColors.Steel
            )
            SmartieGhostButton(
                text = openLabel(Parties.displayName(match.party)),
                onClick = { onOpen(match.party) },
                modifier = Modifier
                    .semantics { contentDescription = openLabel(Parties.displayName(match.party)) }
                    .fillMaxWidth()
            )
        }
    }
}

@Composable
private fun Field(label: String, value: String, saving: Boolean, onChange: (String) -> Unit) {
    SmartieField(
        label = label,
        value = value,
        onValueChange = onChange,
        enabled = !saving,
        modifier = Modifier.semantics { contentDescription = label }
    )
}

internal fun duplicateHeadline(match: PartyMatch): String =
    "${Parties.displayName(match.party)} already has ${match.on.label}"

internal const val ADD_HEADING = "New party"
internal const val EDIT_HEADING = "Edit party"
internal const val NAME_LABEL = "Party name"
internal const val TYPE_LABEL = "Type"
internal const val CONTACT_LABEL = "Contact person"
internal const val PHONE_LABEL = "Phone"
internal const val EMAIL_LABEL = "Email"
internal const val GSTIN_LABEL = "GSTIN"
internal const val CITY_LABEL = "City"
internal const val ADDRESS_LABEL = "Address"
internal const val NOTES_LABEL = "Notes"
internal const val SAVE_NEW = "Save party"
internal const val SAVE_CHANGES = "Save changes"
internal const val CANCEL = "Cancel"
internal const val ADD_PARTY = "Add party"
internal const val EDIT_PARTY = "Edit"
internal const val ARCHIVE_PARTY = "Archive"
internal const val UNARCHIVE_PARTY = "Bring back"
internal const val NAME_IS_LOCKED =
    "Only an Owner or Administrator can rename a party."
internal const val DUPLICATE_ADVICE =
    "Open it instead of entering a second one — a quotation points at the party it was " +
        "issued to, so two records for one customer split their history."
internal const val CLEARING_NOTE =
    "Saving stores exactly what is above. Emptying a box takes that detail off the party."
