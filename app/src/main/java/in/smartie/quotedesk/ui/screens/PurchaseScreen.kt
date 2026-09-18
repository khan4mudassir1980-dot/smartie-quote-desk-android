package `in`.smartie.quotedesk.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.smartie.quotedesk.data.mapping.Money
import `in`.smartie.quotedesk.data.model.PurchaseRecord
import `in`.smartie.quotedesk.ui.AppDataViewModel
import `in`.smartie.quotedesk.ui.components.EmptyState
import `in`.smartie.quotedesk.ui.components.InDevelopmentBanner
import `in`.smartie.quotedesk.ui.components.ListRow
import `in`.smartie.quotedesk.ui.components.SectionHeader
import `in`.smartie.quotedesk.ui.components.Tag
import `in`.smartie.quotedesk.ui.components.TagTone
import `in`.smartie.quotedesk.ui.components.UrgencyTag
import `in`.smartie.quotedesk.ui.components.urgencyColour
import `in`.smartie.quotedesk.ui.theme.LocalSmartieDimens
import `in`.smartie.quotedesk.ui.theme.SmartieColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PurchaseScreen(data: AppDataViewModel) {
    val requirements by data.requirements.collectAsStateWithLifecycle()
    val dimens = LocalSmartieDimens.current
    val open = remember(requirements) { requirements.filter { it.isOpen } }
    val closed = remember(requirements) { requirements.filterNot { it.isOpen } }

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
        item {
            InDevelopmentBanner(
                phase = "N4",
                detail = "Adding a requirement, statuses, received quantity and history return " +
                    "with the Purchase phase."
            )
        }
        item { SectionHeader("Open", trailing = open.size.toString()) }
        if (open.isEmpty()) item { EmptyState("Nothing is waiting to be bought.") }
        items(open, key = { it.id }) { item -> PurchaseRow(item) }

        if (closed.isNotEmpty()) {
            item { SectionHeader("Received and closed", trailing = closed.size.toString()) }
            items(closed, key = { it.id }) { item -> PurchaseRow(item) }
        }
    }
}

/** Internal so the note and the urgency colour can be asserted directly. */
@Composable
internal fun PurchaseRow(item: PurchaseRecord) {
    ListRow(
        title = item.name,
        secondary = "${Money.formatQuantity(item.quantity)} needed",
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
            if (item.receivedQuantity != null) {
                Text(
                    "${Money.formatQuantity(item.receivedQuantity)} in",
                    style = MaterialTheme.typography.labelMedium,
                    color = SmartieColors.Steel
                )
            }
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
