package `in`.smartie.quotedesk.ui.purchase

import androidx.compose.foundation.layout.Arrangement
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
import `in`.smartie.quotedesk.data.mapping.Money
import `in`.smartie.quotedesk.data.model.PurchaseRecord
import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.domain.PurchaseHistory
import `in`.smartie.quotedesk.domain.PurchasePeople
import `in`.smartie.quotedesk.ui.components.EmptyState
import `in`.smartie.quotedesk.ui.components.ListRow
import `in`.smartie.quotedesk.ui.components.SectionHeader
import `in`.smartie.quotedesk.ui.components.SmartieGhostButton
import `in`.smartie.quotedesk.ui.components.Tag
import `in`.smartie.quotedesk.ui.components.TagTone
import `in`.smartie.quotedesk.ui.components.urgencyColour
import `in`.smartie.quotedesk.ui.theme.LocalSmartieDimens
import `in`.smartie.quotedesk.ui.theme.SmartieColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What has already happened to the requirements: what arrived, and what
 * somebody took off the list.
 *
 * **Read-only, for everybody.** There is no control on any row here — no
 * edit, no reopen, and deliberately no "clear history": `allow delete: if
 * false` means a requirement's document outlives every screen that shows it,
 * and a button implying otherwise would be a lie.
 *
 * Removed requirements are behind a collapsed heading at the bottom. They are
 * a different kind of fact from a delivery — usually a mistake being tidied
 * away — and mixing them into the deliveries makes the deliveries harder to
 * read.
 *
 * Who sees whose is `PurchaseHistory`'s answer, and it is an **app-level**
 * filter; see that file for why the rules do not enforce it and what would
 * have to be true before they could.
 */
@Composable
internal fun PurchaseHistoryScreen(
    records: List<PurchaseRecord> = emptyList(),
    viewer: Member = Member(uid = ""),
    loading: Boolean = false,
    /** The team by uid, or empty where this account may not read it. */
    members: Map<String, Member> = emptyMap()
) {
    val dimens = LocalSmartieDimens.current
    val received = PurchaseHistory.received(records, viewer)
    val removed = PurchaseHistory.removed(records, viewer)
    var removedOpen by rememberSaveable { mutableStateOf(false) }

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
        item { SectionHeader(RECEIVED_SECTION, trailing = received.size.toString()) }

        when {
            // An empty history and one that has not arrived are different
            // things, and only one of them is worth acting on.
            loading && received.isEmpty() && removed.isEmpty() -> item { EmptyState(LOADING_HISTORY) }
            received.isEmpty() -> item { EmptyState(NOTHING_RECEIVED) }
        }

        items(received, key = { it.id }) { item -> HistoryRow(item, members) }

        if (removed.isNotEmpty()) {
            item {
                SmartieGhostButton(
                    text = removedHeading(removed.size, removedOpen),
                    onClick = { removedOpen = !removedOpen },
                    modifier = Modifier
                        .semantics { contentDescription = removedHeading(removed.size, removedOpen) }
                        .fillMaxWidth()
                )
            }
            if (removedOpen) {
                items(removed, key = { it.id }) { item -> HistoryRow(item, members) }
            }
        }
    }
}

/**
 * One line of history: what it was, how much of it came, and who closed it.
 *
 * No control, and no accent colour — urgency mattered while somebody was
 * waiting for it, and nobody is waiting for anything here.
 */
@Composable
private fun HistoryRow(item: PurchaseRecord, members: Map<String, Member> = emptyMap()) {
    ListRow(
        title = item.name,
        secondary = quantityLine(item),
        note = item.note.takeIf { it.isNotBlank() },
        meta = closingLine(item, members),
        accent = urgencyColour(item.urgency),
        // Neutral throughout: nothing here is waiting for anybody, so a
        // coloured tag would be shouting about a decision already taken.
        tags = { Tag(if (item.deleted) REMOVED_TAG else item.status, TagTone.NEUTRAL) },
        trailing = {
            // C7: a removed requirement that had received something still
            // says so. Hiding it was treating "removed" as though it undid
            // the delivery, and it does not — the goods arrived.
            if (item.receivedQuantity != null) {
                Text(
                    "${Money.formatQuantity(item.receivedTotal)} in",
                    style = MaterialTheme.typography.labelMedium,
                    color = SmartieColors.Steel
                )
            }
        }
    )
}

/**
 * Who closed this requirement, and when.
 *
 * A removed row records both in `delBy` and `delAt` — but only if this app
 * removed it. A PWA removal wrote neither, so the name falls back to nothing
 * and the line reads simply "Removed"; a row is never dropped for want of a
 * stamp, and the screen never invents one.
 */
internal fun closingLine(
    item: PurchaseRecord,
    members: Map<String, Member> = emptyMap()
): String? {
    val remover = PurchasePeople.describe(item.removedBy, item.removedByUid, members)
    val receiver = PurchasePeople.describe(item.receivedBy, item.receivedByUid, members)
    return when {
        item.deleted -> listOfNotNull(
            REMOVED_TAG,
            remover.takeIf { it.isNotBlank() }?.let { "by $it" },
            item.removedAt.takeIf { it > 0L }?.let { "on ${formatDay(it)}" }
        ).joinToString(" ")

        receiver.isNotBlank() -> listOfNotNull(
            "Received by $receiver",
            item.receivedAt.takeIf { it > 0L }?.let { formatDay(it) }
        ).joinToString(" · ")

        else -> item.receivedAt.takeIf { it > 0L }?.let { "Received ${formatDay(it)}" }
    }
}

private fun formatDay(millis: Long): String =
    SimpleDateFormat("d MMM yyyy", Locale.forLanguageTag("en-IN")).format(Date(millis))

// --- wording ---------------------------------------------------------------

internal const val RECEIVED_SECTION: String = "Received"
internal const val REMOVED_TAG: String = "Removed"
internal const val NOTHING_RECEIVED: String = "Nothing has been received yet."
internal const val LOADING_HISTORY: String = "Loading history…"

/** Says how many are hidden, so tapping it is a decision rather than a guess. */
internal fun removedHeading(count: Int, open: Boolean): String =
    if (open) "Hide removed ($count)" else "Removed ($count)"
