package `in`.smartie.quotedesk.ui.quotations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
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
import `in`.smartie.quotedesk.data.model.QuotationRecord
import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.domain.QuotationHistory
import `in`.smartie.quotedesk.ui.components.EmptyState
import `in`.smartie.quotedesk.ui.components.InDevelopmentBanner
import `in`.smartie.quotedesk.ui.components.ListRow
import `in`.smartie.quotedesk.ui.components.Tag
import `in`.smartie.quotedesk.ui.components.TagTone
import `in`.smartie.quotedesk.ui.theme.LocalSmartieDimens
import `in`.smartie.quotedesk.ui.theme.SmartieColors

/**
 * Every quotation this person may look back at, newest first, and a way into
 * each one.
 *
 * Shared by the Quotations tab and by More → Quotation history. The tab adds
 * the N5 banner; nothing else differs, and **that is deliberate**. N4.4 found
 * the Purchase board and the Purchase history screen answering different
 * questions about who may see what, which showed a Staff account somebody
 * else's delivery. Two lists of the same documents must apply the same filter
 * or one of them is a leak.
 *
 * Who sees whose is `QuotationHistory`'s answer, and it is an **app-level**
 * filter; that file says why the rules do not enforce it and what would have
 * to be true first.
 */
@Composable
internal fun QuotationListScreen(
    records: List<QuotationRecord> = emptyList(),
    viewer: Member = Member(uid = ""),
    loading: Boolean = false,
    /** The Quotations tab shows it; More → Quotation history does not. */
    banner: Boolean = false
) {
    val dimens = LocalSmartieDimens.current
    var openId by rememberSaveable { mutableStateOf<String?>(null) }

    val visible = QuotationHistory.visibleTo(records, viewer)
    val open = openId?.let { id -> visible.firstOrNull { it.id == id } }

    if (open != null) {
        QuotationDetail(quotation = open, onBack = { openId = null })
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
        if (banner) {
            item(key = "banner") {
                InDevelopmentBanner(phase = "N5", detail = BANNER_DETAIL)
            }
        }

        when {
            loading && visible.isEmpty() -> item { EmptyState(LOADING_QUOTATIONS) }
            visible.isEmpty() -> item { EmptyState(NO_QUOTATIONS) }
        }

        items(visible, key = { it.id }) { quotation ->
            QuotationRow(quotation) { openId = quotation.id }
        }
    }
}

/** One quotation on the list: its number, who it was for, and what it came to. */
@Composable
private fun QuotationRow(quotation: QuotationRecord, onOpen: () -> Unit) {
    val number = quotation.number.ifBlank { UNNUMBERED }
    ListRow(
        // `ListRow` gives a clickable row the click action but no name for it.
        modifier = Modifier.semantics { contentDescription = openQuotationLabel(number) },
        title = number,
        secondary = quotation.party.name.ifBlank { PARTY_NOT_RECORDED },
        meta = quotation.at.takeIf { it > 0 }?.let(::formatDate),
        tags = {
            Tag(quotation.status.ifBlank { "Finalised" }, statusTone(quotation.status))
            if (quotation.legacyBetaShape) Tag(BETA_RECORD, TagTone.WARN)
        },
        trailing = {
            Text(
                Money.formatRupees(quotation.total, decimals = 0),
                style = MaterialTheme.typography.titleMedium,
                color = SmartieColors.Ink
            )
        },
        onClick = onOpen
    )
}

internal const val PARTY_NOT_RECORDED = "Party not recorded"
internal const val LOADING_QUOTATIONS = "Loading quotations…"
internal const val NO_QUOTATIONS = "No quotations have been issued from this database yet."
internal const val BANNER_DETAIL =
    "Creating a quotation, the professional PDF, WhatsApp and Print return with the " +
        "Quotation phase. Keep using the PWA to issue quotations."
