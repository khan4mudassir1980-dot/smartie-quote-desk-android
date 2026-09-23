package `in`.smartie.quotedesk.ui.quotations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import `in`.smartie.quotedesk.data.mapping.Money
import `in`.smartie.quotedesk.data.model.QuotationLineRecord
import `in`.smartie.quotedesk.data.model.QuotationRecord
import `in`.smartie.quotedesk.ui.components.ListRow
import `in`.smartie.quotedesk.ui.components.SmartieCard
import `in`.smartie.quotedesk.ui.components.SmartieGhostButton
import `in`.smartie.quotedesk.ui.components.Tag
import `in`.smartie.quotedesk.ui.components.TagTone
import `in`.smartie.quotedesk.ui.theme.LocalSmartieDimens
import `in`.smartie.quotedesk.ui.theme.SmartieColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One quotation exactly as it was issued, **read-only**.
 *
 * Shared by the Quotations tab and by More → Quotation history, because they
 * are two ways into the same document and a quotation that read differently
 * depending on which list you came from would be worse than useless.
 *
 * **Everything here is the stored document, not a recalculation.** The totals
 * are what was sent to the customer; re-deriving them from the lines would
 * quietly disagree with the paper in somebody's hand the moment a rate
 * changed, or the moment a PWA row turned out to carry a figure this app would
 * have computed differently. The one exception is a per-line amount that was
 * never stored, where `qty × rate` is the only answer available.
 *
 * **Transport is a line, not a field.** V8C4 pushes it into `lines[]` as an
 * ordinary manual entry — "Transportation", no product key — and counts it in
 * the subtotal, so that is where it appears here, tagged as typed by hand.
 * The separate `install`, `disc` and `discBase` fields N5.2 defined are not
 * shown, because nothing writes them yet: no document in either project
 * carries one, and a row that always read zero would be noise.
 *
 * **GST is shown as `total − subtotal`**, both stored figures, rather than
 * recomputed from the percentage. That is what keeps a beta record honest:
 * `q_beta_issued` has no document-level `gst` at all, only a per-line rate and
 * a `gstTotal`, and the subtraction lands on exactly the 3,132 it stored.
 */
@Composable
internal fun QuotationDetail(quotation: QuotationRecord, onBack: () -> Unit) {
    val dimens = LocalSmartieDimens.current

    LazyColumn(
        // Tagged so a test can scroll to a card below the fold. A LazyColumn
        // never composes what is off screen, so on a tall quotation the
        // totals are not merely invisible — they are absent from the
        // semantics tree entirely, and no amount of looking finds them.
        modifier = Modifier.fillMaxSize().testTag(DETAIL_LIST_TAG),
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
                text = BACK_TO_QUOTATIONS,
                onClick = onBack,
                modifier = Modifier
                    .semantics { contentDescription = BACK_TO_QUOTATIONS }
                    .fillMaxWidth()
            )
        }

        item(key = "head") {
            SmartieCard {
                Column(verticalArrangement = Arrangement.spacedBy(dimens.gapXs)) {
                    Text(
                        quotation.number.ifBlank { UNNUMBERED },
                        style = MaterialTheme.typography.titleMedium,
                        color = SmartieColors.Ink
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(dimens.gapXs)) {
                        Tag(quotation.status.ifBlank { "Finalised" }, statusTone(quotation.status))
                        if (quotation.tierName.isNotBlank()) {
                            Tag(quotation.tierName, TagTone.NEUTRAL)
                        }
                        if (quotation.legacyBetaShape) Tag(BETA_RECORD, TagTone.WARN)
                    }
                    Fact("Issued", quotation.at.takeIf { it > 0 }?.let(::formatDate).orEmpty())
                    Fact("Issued by", quotation.by)
                    if (quotation.status.equals("Cancelled", ignoreCase = true)) {
                        Fact("Cancelled by", quotation.cancelledBy)
                        Fact(
                            "Cancelled",
                            quotation.cancelledAt.takeIf { it > 0 }?.let(::formatDate).orEmpty()
                        )
                    }
                }
            }
        }

        item(key = "party") {
            SmartieCard {
                Column(verticalArrangement = Arrangement.spacedBy(dimens.gapXs)) {
                    Text(
                        PARTY_SECTION,
                        style = MaterialTheme.typography.labelLarge,
                        color = SmartieColors.Steel
                    )
                    // The snapshot, not today's party record. A customer who
                    // has since moved offices did not move on this quotation.
                    Fact("Name", quotation.party.name)
                    Fact("Site", quotation.party.site)
                    Fact("Contact", quotation.party.contact)
                    Fact("Phone", quotation.party.phone)
                    Fact("Email", quotation.party.email)
                    Fact("GSTIN", quotation.party.gstin)
                    Fact("City", quotation.party.city)
                    Fact("Address", quotation.party.address)
                }
            }
        }

        item(key = "lines-heading") {
            Text(
                LINES_SECTION,
                style = MaterialTheme.typography.labelLarge,
                color = SmartieColors.Steel
            )
        }

        if (quotation.lines.isEmpty()) {
            item(key = "no-lines") {
                Text(
                    NO_LINES,
                    style = MaterialTheme.typography.bodyMedium,
                    color = SmartieColors.Steel
                )
            }
        }

        itemsIndexed(quotation.lines, key = { index, _ -> "line-$index" }) { _, line ->
            LineRow(line)
        }

        item(key = TOTALS_KEY) {
            SmartieCard {
                Column(verticalArrangement = Arrangement.spacedBy(dimens.gapXs)) {
                    MoneyLine("Subtotal", quotation.subtotal)
                    if (quotation.gstEnabled) {
                        MoneyLine(gstLabel(quotation.gstPercent), quotation.total - quotation.subtotal)
                    }
                    MoneyLine(GRAND_TOTAL, quotation.total, emphasis = true)
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

/**
 * One line: what it was, how much, at what rate, and what it came to.
 *
 * A manual line — transport, a site visit, anything typed by hand — carries no
 * product key, and is labelled so a reader can tell it from a catalogue item.
 */
@Composable
private fun LineRow(line: QuotationLineRecord) {
    ListRow(
        title = line.title.ifBlank { UNTITLED_LINE },
        secondary = line.spec.ifBlank { null },
        meta = "${Money.formatQuantity(line.quantity)} ${line.unit} × " +
            Money.formatRupees(line.rate, decimals = 0),
        tags = { if (line.manual) Tag(MANUAL_LINE, TagTone.NEUTRAL) },
        trailing = {
            Text(
                // The stored amount, falling back to qty x rate only where
                // the document never carried one.
                Money.formatRupees(line.amount, decimals = 0),
                style = MaterialTheme.typography.titleSmall,
                color = SmartieColors.Ink
            )
        }
    )
}

/** A labelled fact, which says so when it was never recorded. */
@Composable
private fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = SmartieColors.Steel)
        Text(
            value.ifBlank { NOT_RECORDED },
            style = MaterialTheme.typography.bodyMedium,
            color = if (value.isBlank()) SmartieColors.Steel else SmartieColors.Ink
        )
    }
}

/** A labelled rupee figure. */
@Composable
private fun MoneyLine(label: String, amount: Double, emphasis: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            label,
            style = if (emphasis) MaterialTheme.typography.titleSmall
            else MaterialTheme.typography.bodyMedium,
            color = if (emphasis) SmartieColors.Ink else SmartieColors.Steel
        )
        Text(
            Money.formatRupees(amount, decimals = 0),
            style = if (emphasis) MaterialTheme.typography.titleSmall
            else MaterialTheme.typography.bodyMedium,
            color = SmartieColors.Ink,
            fontWeight = if (emphasis) FontWeight.Bold else null
        )
    }
}

internal fun statusTone(status: String): TagTone = when {
    status.equals("Cancelled", ignoreCase = true) -> TagTone.DANGER
    else -> TagTone.GREEN
}

internal fun gstLabel(percent: Double): String =
    "GST ${Money.formatQuantity(percent)}%"

internal fun openQuotationLabel(number: String): String = "Open $number"

internal fun formatDate(millis: Long): String =
    SimpleDateFormat("d MMM yyyy", Locale.forLanguageTag("en-IN")).format(Date(millis))

internal const val DETAIL_LIST_TAG = "quotation-detail"
internal const val TOTALS_KEY = "totals"
internal const val PARTY_SECTION = "Party, as it was issued"
internal const val LINES_SECTION = "Items"
internal const val GRAND_TOTAL = "Grand total"
internal const val BACK_TO_QUOTATIONS = "Back to quotations"
internal const val UNNUMBERED = "Quotation"
internal const val UNTITLED_LINE = "Item"
internal const val MANUAL_LINE = "Typed by hand"
internal const val BETA_RECORD = "Beta record"
internal const val NO_LINES = "This quotation stored no item lines."
internal const val NOT_RECORDED = "Not recorded"
internal const val READ_ONLY_NOTE =
    "Quotations are read-only in the app. Creating, editing and sharing one arrives with " +
        "the rest of the Quotation phase."
