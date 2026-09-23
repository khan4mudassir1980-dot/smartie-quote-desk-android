package `in`.smartie.quotedesk.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.style.TextAlign
import `in`.smartie.quotedesk.data.model.PartyRecord
import `in`.smartie.quotedesk.domain.Parties
import `in`.smartie.quotedesk.ui.components.EmptyState
import `in`.smartie.quotedesk.ui.components.ListRow
import `in`.smartie.quotedesk.ui.components.SectionHeader
import `in`.smartie.quotedesk.ui.components.SmartieCard
import `in`.smartie.quotedesk.ui.components.SmartieField
import `in`.smartie.quotedesk.ui.components.SmartieGhostButton
import `in`.smartie.quotedesk.ui.components.Tag
import `in`.smartie.quotedesk.ui.components.TagTone
import `in`.smartie.quotedesk.ui.theme.LocalSmartieDimens
import `in`.smartie.quotedesk.ui.theme.SmartieColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The saved customers, **read-only**.
 *
 * N5.3 replaces the placeholder with the list and a detail view and stops
 * there: creating or correcting a party is N5.5, and a screen that offered an
 * edit it could not perform would be worse than one that does not offer it.
 *
 * Every role that may quote sees every party — they are shared, deliberately,
 * so a Manager can quote against a customer somebody else entered. A Staff
 * account never reaches this screen: the More entry is gated on
 * `Permissions.canUseParties`, and the rules refuse the read as well.
 */
@Composable
internal fun PartiesScreen(
    parties: List<PartyRecord> = emptyList(),
    loading: Boolean = false
) {
    val dimens = LocalSmartieDimens.current
    var query by rememberSaveable { mutableStateOf("") }
    var archivedOpen by rememberSaveable { mutableStateOf(false) }
    var openPartyId by rememberSaveable { mutableStateOf<String?>(null) }

    val book = Parties.build(parties, query)
    val open = openPartyId?.let { id -> parties.firstOrNull { it.id == id } }

    if (open != null) {
        PartyDetail(party = open, onBack = { openPartyId = null })
        return
    }

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
        item(key = "search") {
            SmartieField(
                label = SEARCH_LABEL,
                value = query,
                onValueChange = { query = it },
                placeholder = SEARCH_PLACEHOLDER,
                // `SmartieField` puts the caller's modifier on the column
                // holding the label and the input, so this is what names the
                // field for a test — and for a screen reader.
                modifier = Modifier.semantics { contentDescription = SEARCH_LABEL }
            )
        }

        item(key = "heading") {
            SectionHeader(ACTIVE_SECTION, trailing = book.active.size.toString())
        }

        when {
            // Nothing yet and nothing at all are different things, and only
            // one of them is worth acting on.
            loading && book.isEmpty -> item { EmptyState(LOADING_PARTIES) }
            book.searching && book.matchCount == 0 -> item { EmptyState(NO_MATCHES) }
            book.active.isEmpty() && !book.searching -> item { EmptyState(NO_PARTIES) }
            book.active.isEmpty() -> item { EmptyState(NO_ACTIVE_MATCHES) }
        }

        items(book.active, key = { it.id }) { party ->
            PartyRow(party) { openPartyId = party.id }
        }

        if (book.archived.isNotEmpty()) {
            item(key = "archived-heading") {
                SmartieGhostButton(
                    text = archivedHeading(book.archived.size, archivedOpen),
                    onClick = { archivedOpen = !archivedOpen },
                    modifier = Modifier
                        .semantics {
                            contentDescription = archivedHeading(book.archived.size, archivedOpen)
                        }
                        .fillMaxWidth()
                )
            }
            if (archivedOpen) {
                items(book.archived, key = { "archived-${it.id}" }) { party ->
                    PartyRow(party) { openPartyId = party.id }
                }
            }
        }
    }
}

/** One party on the list: who they are, where, and how to reach them. */
@Composable
private fun PartyRow(party: PartyRecord, onOpen: () -> Unit) {
    val name = Parties.displayName(party)
    ListRow(
        // `ListRow` gives a clickable row the click action but no name for it,
        // so the row says what opening it would do.
        modifier = Modifier.semantics { contentDescription = openLabel(name) },
        title = name,
        secondary = party.city.ifBlank { null },
        meta = listOfNotNull(
            party.contact.takeIf { it.isNotBlank() && it != name },
            party.phone.takeIf { it.isNotBlank() }
        ).joinToString(" · ").ifBlank { null },
        tags = {
            if (party.archived) Tag("Archived", TagTone.WARN)
            if (party.type.isNotBlank()) {
                Tag(party.type.replaceFirstChar(Char::titlecase), TagTone.NEUTRAL)
            }
            // Said out loud rather than papered over: this row has no firm
            // name at all, so the name above it is a person's.
            if (Parties.missingFirmName(party)) Tag(CONTACT_AS_NAME, TagTone.DANGER)
        },
        onClick = onOpen
    )
}

/**
 * Everything stored about one party, and **nothing editable**.
 *
 * Every field is shown, including the ones that are empty, because "no GSTIN
 * recorded" is an answer somebody came here for and a missing line is not.
 */
@Composable
private fun PartyDetail(party: PartyRecord, onBack: () -> Unit) {
    val dimens = LocalSmartieDimens.current
    val name = Parties.displayName(party)

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
        item(key = "back") {
            SmartieGhostButton(
                text = BACK_TO_PARTIES,
                onClick = onBack,
                modifier = Modifier
                    .semantics { contentDescription = BACK_TO_PARTIES }
                    .fillMaxWidth()
            )
        }

        item(key = "identity") {
            SmartieCard {
                Column(verticalArrangement = Arrangement.spacedBy(dimens.gapS)) {
                    Text(
                        name,
                        style = MaterialTheme.typography.titleMedium,
                        color = SmartieColors.Ink
                    )
                    if (Parties.missingFirmName(party)) {
                        Text(
                            CONTACT_AS_NAME_DETAIL,
                            style = MaterialTheme.typography.bodySmall,
                            color = SmartieColors.Danger
                        )
                    }
                    Field("Type", party.type)
                    Field("Contact person", party.contact)
                    Field("Phone", party.phone)
                    Field("Email", party.email)
                    Field("GSTIN", party.gstin)
                    Field("City", party.city)
                    Field("Address", party.address)
                    Field("Notes", party.notes)
                }
            }
        }

        item(key = "provenance") {
            SmartieCard {
                Column(verticalArrangement = Arrangement.spacedBy(dimens.gapS)) {
                    Text(
                        RECORD_SECTION,
                        style = MaterialTheme.typography.labelLarge,
                        color = SmartieColors.Steel
                    )
                    Field("Added by", party.by)
                    Field("Added", party.createdAt.takeIf { it > 0 }?.let(::formatDate).orEmpty())
                    Field("Last changed by", party.updatedByName)
                    Field("Last changed", party.updatedAt.takeIf { it > 0 }?.let(::formatDate).orEmpty())
                    Field("Archived", if (party.archived) "Yes" else "No")
                }
            }
        }

        item(key = "read-only") {
            Text(
                READ_ONLY_NOTE,
                style = MaterialTheme.typography.bodySmall,
                color = SmartieColors.Steel,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** A labelled fact, which says so when it was never recorded. */
@Composable
private fun Field(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = SmartieColors.Steel)
        Text(
            value.ifBlank { NOT_RECORDED },
            style = MaterialTheme.typography.bodyMedium,
            color = if (value.isBlank()) SmartieColors.Steel else SmartieColors.Ink
        )
    }
}

internal fun archivedHeading(count: Int, open: Boolean): String =
    if (open) "Hide archived ($count)" else "Archived ($count)"

internal fun openLabel(name: String): String = "Open $name"

private fun formatDate(millis: Long): String =
    SimpleDateFormat("d MMM yyyy", Locale.forLanguageTag("en-IN")).format(Date(millis))

internal const val SEARCH_LABEL = "Search"
internal const val SEARCH_PLACEHOLDER = "Name, contact, phone or GSTIN"
internal const val ACTIVE_SECTION = "Parties"
internal const val RECORD_SECTION = "Record"
internal const val BACK_TO_PARTIES = "Back to parties"
internal const val NOT_RECORDED = "Not recorded"
internal const val LOADING_PARTIES = "Loading parties…"
internal const val NO_PARTIES = "No parties have been saved in this database yet."
internal const val NO_MATCHES = "No party matches that."
internal const val NO_ACTIVE_MATCHES = "No party in use matches that — check Archived below."
internal const val CONTACT_AS_NAME = "No firm name"
internal const val CONTACT_AS_NAME_DETAIL =
    "No firm name is recorded on this party, so the contact person's name is shown above. " +
        "Some of these came from the native beta, which kept the firm and the contact in " +
        "different fields. Correcting it needs the party editor, which arrives in N5.5."
internal const val READ_ONLY_NOTE =
    "Parties are read-only for now. Adding and correcting one arrives with the rest of " +
        "the Quotation phase."
