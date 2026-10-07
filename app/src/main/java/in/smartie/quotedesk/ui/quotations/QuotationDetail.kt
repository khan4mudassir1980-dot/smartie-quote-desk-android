package `in`.smartie.quotedesk.ui.quotations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import `in`.smartie.quotedesk.data.mapping.Money
import `in`.smartie.quotedesk.data.model.QuotationLineRecord
import `in`.smartie.quotedesk.data.model.QuotationRecord
import `in`.smartie.quotedesk.domain.CopyStart
import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.domain.Permissions
import `in`.smartie.quotedesk.domain.QuotationCancel
import `in`.smartie.quotedesk.domain.QuotationCopy
import `in`.smartie.quotedesk.domain.QuotationEdit
import `in`.smartie.quotedesk.ui.components.ListRow
import `in`.smartie.quotedesk.ui.components.SmartieCard
import `in`.smartie.quotedesk.ui.components.SmartieGhostButton
import `in`.smartie.quotedesk.ui.components.SmartiePrimaryButton
import `in`.smartie.quotedesk.ui.components.Tag
import `in`.smartie.quotedesk.ui.components.TagTone
import `in`.smartie.quotedesk.ui.theme.LocalSmartieDimens
import `in`.smartie.quotedesk.ui.theme.SmartieColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One quotation exactly as it was issued — or last edited — and, since
 * N5.10, the two things that may be done to it: **Edit** and **Cancel**.
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
 * ordinary line — "Transportation", `k: null`, `manual: false` — and counts it
 * in the subtotal, so that is where it appears here. A line is tagged by its
 * stored `manual` flag alone, so a V8C4 transport line shows untagged. (This
 * said "manual entry … tagged as typed by hand" until N5.9a commit 3b; the
 * fixture's invented `manual: true` was replaced by V8C4's own shape in N5.12.)
 * **Installation and discount are rows of their own when a quotation carries
 * them** — the `install` and `disc` N5.9 writes and V8C4 never does. Until
 * N5.10 commit 1 this said they were not shown "because nothing writes them
 * yet", which stopped being true the day 9b issued its first quotation: a
 * discounted quotation's totals then did not visibly add up. They show what
 * was stored, like every other figure here. The full printed order is N5.11's.
 *
 * **"Last edited" sits directly after the issue date** — the place the printed
 * order gives it (`docs/N5-plan.md`) — and only on a quotation somebody has
 * edited. N5.11 prints it in the same place.
 *
 * **GST is shown as `total − subtotal`**, both stored figures, rather than
 * recomputed from the percentage. That is what keeps a beta record honest:
 * `q_beta_issued` has no document-level `gst` at all, only a per-line rate and
 * a `gstTotal`, and the subtraction lands on exactly the 2,160 it stored.
 */
@Composable
internal fun QuotationDetail(
    quotation: QuotationRecord,
    onBack: () -> Unit,
    /** Who is looking: what is offered is theirs to do (`QuotationEdit.refusalToOpen`, `QuotationCancel.offered`). */
    viewer: Member = Member(uid = ""),
    actions: QuotationActions = QuotationActions(),
    /** True while this quotation's cancel is out. */
    cancelling: Boolean = false,
    /** Why this quotation's last cancel did not happen. */
    cancelFailure: String? = null,
    /** What Duplicate would come to on this device now (`QuotationCopy.start`). */
    copyStart: CopyStart = CopyStart.Go,
    /** True while this quotation's PDF is being made (N5.11). */
    preparing: Boolean = false,
    /** Why this quotation's last PDF was not made. */
    pdfFailure: String? = null
) {
    val dimens = LocalSmartieDimens.current
    // N5.11: anyone who may quote, on any issued quotation — a cancelled one
    // included, which prints marked CANCELLED — but not the native beta's
    // records, whose shape the PDF does not know.
    val offersPdf = Permissions.canQuote(viewer) && !quotation.legacyBetaShape
    val offersEdit = QuotationEdit.refusalToOpen(viewer, quotation) == null
    val offersCancel = QuotationCancel.offered(viewer, quotation)
    val offersCopy = QuotationCopy.offered(viewer)
    // V8C4's confirm, asked on the screen: the detail's own state, keyed on the
    // quotation so another one never opens with the question already asked.
    var askingCancel by rememberSaveable(quotation.id) { mutableStateOf(false) }
    var askingCopy by rememberSaveable(quotation.id) { mutableStateOf(false) }

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
                    // The date the PDF prints: the server's, else the phone's
                    // (the Owner's decision 1.1 of 2026-10-06).
                    Fact("Issued", quotation.issuedAt.takeIf { it > 0 }?.let(::formatDate).orEmpty())
                    if (quotation.lastEditedAt > 0) {
                        Fact(LAST_EDITED, lastEditedText(quotation))
                    }
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
                    // What was stored at issue or at the last edit — never
                    // recomputed, like every figure on this screen.
                    quotation.installation?.amount?.let { MoneyLine(INSTALLATION_ROW, it) }
                    quotation.discount?.amount?.takeIf { it > 0.0 }?.let {
                        MoneyLine(DISCOUNT_ROW, it, deduction = true)
                    }
                    MoneyLine("Subtotal", quotation.subtotal)
                    if (quotation.gstEnabled) {
                        MoneyLine(gstLabel(quotation.gstPercent), quotation.total - quotation.subtotal)
                    }
                    MoneyLine(GRAND_TOTAL, quotation.total, emphasis = true)
                }
            }
        }

        if (offersPdf || offersEdit || offersCancel || offersCopy) {
            item(key = DETAIL_ACTIONS_KEY) {
                Column(verticalArrangement = Arrangement.spacedBy(dimens.gapS)) {
                    // At the top of the actions, in place of the note that said
                    // these would come with the rest of the Quotation phase.
                    if (offersPdf) {
                        PdfButtons(
                            onOutput = { action -> actions.onOutput(quotation, action) },
                            enabled = !cancelling,
                            preparing = preparing
                        )
                        if (pdfFailure != null) {
                            Text(
                                pdfFailure,
                                style = MaterialTheme.typography.bodyMedium,
                                color = SmartieColors.Danger
                            )
                        }
                    }
                    if (offersEdit) {
                        SmartiePrimaryButton(
                            text = EDIT_QUOTATION,
                            onClick = { actions.onEdit(quotation) },
                            enabled = !cancelling,
                            modifier = Modifier
                                .semantics { contentDescription = EDIT_QUOTATION }
                                .fillMaxWidth()
                        )
                    }
                    if (offersCopy) {
                        DuplicateControls(
                            start = copyStart,
                            asking = askingCopy,
                            onPress = {
                                when (copyStart) {
                                    is CopyStart.EditOpen -> Unit
                                    CopyStart.AskFirst -> askingCopy = true
                                    CopyStart.Go -> actions.onDuplicate(quotation)
                                }
                            },
                            onAnswer = { replace ->
                                askingCopy = false
                                if (replace) actions.onDuplicate(quotation)
                            }
                        )
                    }
                    if (offersCancel) {
                        CancelControls(
                            number = quotation.number,
                            asking = askingCancel,
                            cancelling = cancelling,
                            failure = cancelFailure,
                            onAsk = { if (!cancelling) askingCancel = true },
                            onAnswer = { cancel ->
                                askingCancel = false
                                if (cancel && !cancelling) actions.onCancel(quotation)
                            }
                        )
                    }
                }
            }
        }

        // The last item: an absence check scrolls here first. It held the
        // note that Download, Print and WhatsApp were still to come (until
        // N5.11); now only the tag that proves the list was reached.
        item(key = DETAIL_END_KEY) {
            Spacer(Modifier.fillMaxWidth().height(dimens.gapS).testTag(DETAIL_END_TAG))
        }
    }
}

/**
 * Duplicate (amendment C), offered on every quotation — a cancelled one
 * included, since cancel and duplicate is how a quotation is reissued.
 *
 * While an edit is open it is not offered, and the line says why. When the
 * quotation being worked on has lines, **V8C4's question** comes first —
 * "Replace the quotation you are working on with a copy of this one?" —
 * answered "Replace it" / "Keep mine" (a choice: V8C4's is `window.confirm`).
 */
@Composable
private fun DuplicateControls(
    start: CopyStart,
    asking: Boolean,
    onPress: () -> Unit,
    onAnswer: (Boolean) -> Unit
) {
    val dimens = LocalSmartieDimens.current
    Column(verticalArrangement = Arrangement.spacedBy(dimens.gapS)) {
        when {
            start is CopyStart.EditOpen -> Text(
                QuotationEdit.finishEditFirst(start.number),
                style = MaterialTheme.typography.bodyMedium,
                color = SmartieColors.Steel,
                modifier = Modifier.testTag(COPY_WAITS_TAG)
            )
            asking -> {
                Text(
                    QuotationCopy.REPLACE_QUESTION,
                    style = MaterialTheme.typography.bodyMedium,
                    color = SmartieColors.Ink
                )
                SmartieGhostButton(
                    text = REPLACE_IT,
                    onClick = { onAnswer(true) },
                    modifier = Modifier
                        .semantics { contentDescription = REPLACE_IT }
                        .fillMaxWidth()
                )
                SmartieGhostButton(
                    text = KEEP_MINE,
                    onClick = { onAnswer(false) },
                    modifier = Modifier
                        .semantics { contentDescription = KEEP_MINE }
                        .fillMaxWidth()
                )
            }
            else -> SmartieGhostButton(
                text = DUPLICATE,
                onClick = onPress,
                modifier = Modifier
                    .semantics { contentDescription = DUPLICATE }
                    .fillMaxWidth()
            )
        }
    }
}

/**
 * Cancel, and V8C4's question before it.
 *
 * **The question is V8C4's, word for word** (`QuotationCancel.confirmText`);
 * its two answers, "Cancel quotation" and "Keep it", are **a choice, not a
 * port** — V8C4 asks through `window.confirm`, whose OK / Cancel are the
 * browser's, and "Cancel" would name both buttons here. Nothing is marked
 * cancelled on this screen: the write is remote-first, and the quotation
 * turns Cancelled when the listener brings it.
 */
@Composable
private fun CancelControls(
    number: String,
    asking: Boolean,
    cancelling: Boolean,
    failure: String?,
    onAsk: () -> Unit,
    onAnswer: (Boolean) -> Unit
) {
    val dimens = LocalSmartieDimens.current
    Column(verticalArrangement = Arrangement.spacedBy(dimens.gapS)) {
        if (asking) {
            Text(
                QuotationCancel.confirmText(number),
                style = MaterialTheme.typography.bodyMedium,
                color = SmartieColors.Ink,
                modifier = Modifier.testTag(CANCEL_QUESTION_TAG)
            )
            SmartieGhostButton(
                text = CONFIRM_CANCEL,
                onClick = { onAnswer(true) },
                danger = true,
                modifier = Modifier
                    .semantics { contentDescription = CONFIRM_CANCEL }
                    .fillMaxWidth()
            )
            SmartieGhostButton(
                text = KEEP_IT,
                onClick = { onAnswer(false) },
                modifier = Modifier
                    .semantics { contentDescription = KEEP_IT }
                    .fillMaxWidth()
            )
        } else {
            SmartieGhostButton(
                text = if (cancelling) CANCELLING else CANCEL_QUOTATION,
                onClick = onAsk,
                enabled = !cancelling,
                danger = true,
                modifier = Modifier
                    .semantics { contentDescription = if (cancelling) CANCELLING else CANCEL_QUOTATION }
                    .fillMaxWidth()
            )
        }
        if (failure != null) {
            Text(failure, style = MaterialTheme.typography.bodyMedium, color = SmartieColors.Danger)
        }
    }
}

/**
 * One line: what it was, how much, at what rate, and what it came to.
 *
 * A manual line — a site visit, anything typed by hand — carries no product
 * key, and is labelled so a reader can tell it from a catalogue item.
 * Transport is not one: V8C4 stores it with `manual: false`.
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

/** A labelled rupee figure; a [deduction] reads "- ₹…", as the builder shows one. */
@Composable
private fun MoneyLine(
    label: String,
    amount: Double,
    emphasis: Boolean = false,
    deduction: Boolean = false
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            label,
            style = if (emphasis) MaterialTheme.typography.titleSmall
            else MaterialTheme.typography.bodyMedium,
            color = if (emphasis) SmartieColors.Ink else SmartieColors.Steel
        )
        Text(
            (if (deduction) "- " else "") + Money.formatRupees(amount, decimals = 0),
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

/** A date with its time, for the "Last edited" stamp. */
internal fun formatDateTime(millis: Long): String =
    SimpleDateFormat("d MMM yyyy, h:mm a", Locale.forLanguageTag("en-IN")).format(Date(millis))

/**
 * "<name>, <date time>" — the stamp the card shows and N5.11 prints. A stamp
 * with no name still says when; the name reads "Not recorded" rather than
 * leaving a bare comma.
 */
internal fun lastEditedText(quotation: QuotationRecord): String =
    "${quotation.lastEditedBy.ifBlank { NOT_RECORDED }}, ${formatDateTime(quotation.lastEditedAt)}"

internal const val DETAIL_LIST_TAG = "quotation-detail"
internal const val TOTALS_KEY = "totals"
internal const val PARTY_SECTION = "Party, as it was issued"
internal const val LINES_SECTION = "Items"
internal const val GRAND_TOTAL = "Grand total"
internal const val LAST_EDITED = "Last edited"
internal const val INSTALLATION_ROW = "Installation"
internal const val DISCOUNT_ROW = "Discount"
internal const val BACK_TO_QUOTATIONS = "Back to quotations"
internal const val UNNUMBERED = "Quotation"
internal const val UNTITLED_LINE = "Item"
internal const val MANUAL_LINE = "Typed by hand"
internal const val BETA_RECORD = "Beta record"
internal const val NO_LINES = "This quotation stored no item lines."
internal const val NOT_RECORDED = "Not recorded"
internal const val DETAIL_ACTIONS_KEY = "detail-actions"
internal const val DETAIL_END_KEY = "detail-end"
internal const val DETAIL_END_TAG = "detail-end"
internal const val CANCEL_QUESTION_TAG = "cancel-question"
internal const val EDIT_QUOTATION = "Edit"
internal const val CANCEL_QUOTATION = "Cancel this quotation"
internal const val CONFIRM_CANCEL = "Cancel quotation"
internal const val KEEP_IT = "Keep it"
internal const val CANCELLING = "Cancelling…"
internal const val DUPLICATE = "Duplicate"
internal const val REPLACE_IT = "Replace it"
internal const val KEEP_MINE = "Keep mine"
internal const val COPY_WAITS_TAG = "copy-waits"
