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
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.smartie.quotedesk.data.mapping.Money
import `in`.smartie.quotedesk.data.model.QuotationRecord
import `in`.smartie.quotedesk.ui.AppDataViewModel
import `in`.smartie.quotedesk.ui.components.EmptyState
import `in`.smartie.quotedesk.ui.components.InDevelopmentBanner
import `in`.smartie.quotedesk.ui.components.ListRow
import `in`.smartie.quotedesk.ui.components.Tag
import `in`.smartie.quotedesk.ui.components.TagTone
import `in`.smartie.quotedesk.ui.theme.LocalSmartieDimens
import `in`.smartie.quotedesk.ui.theme.SmartieColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun QuotationsScreen(data: AppDataViewModel) {
    val quotations by data.quotations.collectAsStateWithLifecycle()
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
        item {
            InDevelopmentBanner(
                phase = "N5",
                detail = "Creating a quotation, the professional PDF, WhatsApp, Print and Parties " +
                    "return with the Quotation phase. Keep using the PWA to issue quotations."
            )
        }
        if (quotations.isEmpty()) {
            item { EmptyState("No quotations have been issued from this database yet.") }
        }
        items(quotations, key = { it.id }) { quotation -> QuotationRow(quotation) }
    }
}

@Composable
private fun QuotationRow(quotation: QuotationRecord) {
    ListRow(
        title = quotation.number.ifBlank { "Quotation" },
        secondary = quotation.party.name.ifBlank { "Party not recorded" },
        meta = quotation.at.takeIf { it > 0 }?.let { formatDate(it) },
        tags = {
            Tag(
                quotation.status,
                if (quotation.status.equals("Cancelled", ignoreCase = true)) TagTone.DANGER
                else TagTone.GREEN
            )
            if (quotation.legacyBetaShape) Tag("Beta record", TagTone.WARN)
        },
        trailing = {
            Text(
                Money.formatRupees(quotation.total),
                style = MaterialTheme.typography.titleMedium,
                color = SmartieColors.Ink
            )
        }
    )
}

private fun formatDate(millis: Long): String =
    SimpleDateFormat("d MMM yyyy", Locale.forLanguageTag("en-IN")).format(Date(millis))
