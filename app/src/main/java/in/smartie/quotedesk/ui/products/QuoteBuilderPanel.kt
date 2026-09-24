package `in`.smartie.quotedesk.ui.products

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import `in`.smartie.quotedesk.data.mapping.Money
import `in`.smartie.quotedesk.data.model.RateTierV2
import `in`.smartie.quotedesk.domain.DraftLine
import `in`.smartie.quotedesk.domain.QuoteDraft
import `in`.smartie.quotedesk.domain.QuoteTier
import `in`.smartie.quotedesk.ui.components.CompactStepper
import `in`.smartie.quotedesk.ui.components.EmptyState
import `in`.smartie.quotedesk.ui.components.ListRow
import `in`.smartie.quotedesk.ui.components.SegmentedChoice
import `in`.smartie.quotedesk.ui.components.SmartieGhostButton
import `in`.smartie.quotedesk.ui.quotations.formatDate
import `in`.smartie.quotedesk.ui.theme.LocalSmartieDimens
import `in`.smartie.quotedesk.ui.theme.SmartieColors

/**
 * The quotation being built, full height, in place of the catalogue.
 *
 * **This is the quote bar's sheet grown up, and it is deliberately no longer a
 * sheet.** Two reasons, and the second is the one that decided it:
 *
 * 1. `Dialogs.kt` caps a sheet body at half the screen or 430dp, which is far
 *    too short for a form carrying a party, a tier, a line list and five money
 *    rows.
 * 2. **A Compose `Dialog` — and a `ModalBottomSheet` is one — opens its own
 *    recomposer, which Robolectric's clock does not drive.** N4 paid for that
 *    lesson and wrote it down at `PurchasePanels.kt:60-64`, which is why every
 *    purchase panel is a plain composable its test drives directly. Keeping
 *    the largest surface in the phase inside a sheet would have made it the
 *    least testable thing in the app.
 *
 * So it replaces the catalogue and offers its own way back — the same
 * screen-replacement idiom `PartiesScreen` uses for party detail, and what
 * V8C4's own `class="dk"` panel amounts to on a phone.
 *
 * **One `LazyColumn`, every item keyed.** Nothing here may be asserted without
 * scrolling to its key first: a `LazyColumn` does not hide an off-screen item,
 * it never composes it. [BUILDER_TAIL_KEY] exists only to be scrolled to, so
 * an absence assertion can prove it reached the end of the list rather than
 * stopping at the fold.
 */
@Composable
internal fun QuoteBuilderPanel(
    draft: QuoteDraft,
    onBack: () -> Unit,
    onTierChange: (RateTierV2) -> Unit,
    onChangeLineQuantity: (String, Double) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dimens = LocalSmartieDimens.current
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag(QUOTE_BUILDER_TAG),
        contentPadding = PaddingValues(
            start = dimens.screenPadding,
            end = dimens.screenPadding,
            top = dimens.gapM,
            bottom = dimens.listBottomInset
        ),
        verticalArrangement = Arrangement.spacedBy(dimens.gapS)
    ) {
        item(key = BUILDER_HEADER_KEY) { BuilderHeader(draft, onBack) }

        // Above the lines, and not by habit: changing it reprices every line
        // nobody typed a rate into, so it is a decision taken before the
        // quotation is built rather than after.
        item(key = BUILDER_TIER_KEY) { TierPicker(draft.tier, onTierChange) }

        if (draft.isEmpty) {
            item(key = BUILDER_EMPTY_KEY) { EmptyState(NOTHING_ON_IT) }
        }

        items(draft.lines, key = { line -> line.id }) { line ->
            BuilderLine(line, onChangeLineQuantity)
        }

        if (draft.needsRateCount > 0) {
            item(key = BUILDER_NEEDS_RATE_KEY) {
                Text(
                    needsRateNote(draft.needsRateCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = SmartieColors.Warn
                )
            }
        }

        if (!draft.isEmpty) {
            item(key = BUILDER_CLEAR_KEY) {
                SmartieGhostButton(
                    text = CLEAR_LINES,
                    onClick = onClear,
                    danger = true,
                    modifier = Modifier
                        .semantics { contentDescription = CLEAR_LINES }
                        .fillMaxWidth()
                )
            }
        }

        // The end of the list, and nothing else. An absence assertion scrolls
        // here first, so "the control is not on the screen" cannot quietly
        // mean "the list stopped composing at the fold".
        item(key = BUILDER_TAIL_KEY) {
            Text(
                ISSUING_LATER,
                style = MaterialTheme.typography.bodySmall,
                color = SmartieColors.Steel
            )
        }
    }
}

/**
 * Back to the catalogue, the heading, and what this quotation is priced at.
 *
 * The date is the draft's own — when it was last touched. **The quotation's
 * date is not this**: a quotation is dated when it is issued and numbered,
 * which is N5.9's, and pretending a draft already has one would put a date on
 * a page that has no number to go with it.
 */
@Composable
private fun BuilderHeader(draft: QuoteDraft, onBack: () -> Unit) {
    val dimens = LocalSmartieDimens.current
    Column(verticalArrangement = Arrangement.spacedBy(dimens.gapXs)) {
        SmartieGhostButton(
            text = BACK_TO_PRODUCTS,
            onClick = onBack,
            modifier = Modifier.semantics { contentDescription = BACK_TO_PRODUCTS }
        )
        Text(
            BUILDER_HEADING,
            style = MaterialTheme.typography.titleMedium,
            color = SmartieColors.Ink
        )
        Text(
            eyebrow(draft),
            style = MaterialTheme.typography.labelMedium,
            color = SmartieColors.Steel
        )
    }
}

/**
 * Dealer or Client, and **never Contractor**.
 *
 * `RateTierV2` keeps all three because a quotation already issued at the
 * contractor tier must still display in full; `QuoteTier.OFFERED` is what may
 * be *built*. The catalogue's own picker offered all three until now, which
 * meant a person could price a quotation at a tier `QuoteDraft.refusal` then
 * refused to issue — a control that earns a refusal, which is the thing
 * `PartyEditPanel` already says is worse than no control at all.
 *
 * A draft that somehow arrives at Contractor — a `v1` record written before
 * the narrowing, say — lights neither option, so it says why instead of
 * showing an unlit picker with no explanation.
 */
@Composable
private fun TierPicker(tier: RateTierV2, onTierChange: (RateTierV2) -> Unit) {
    val dimens = LocalSmartieDimens.current
    Column(verticalArrangement = Arrangement.spacedBy(dimens.gapXs)) {
        Text(
            TIER_LABEL,
            style = MaterialTheme.typography.labelMedium,
            color = SmartieColors.Steel
        )
        SegmentedChoice(
            options = QuoteTier.OFFERED,
            selected = tier,
            label = { it.label },
            onSelect = onTierChange
        )
        if (!QuoteTier.offers(tier)) {
            Text(
                QuoteDraft.TIER_NOT_OFFERED,
                style = MaterialTheme.typography.labelMedium,
                color = SmartieColors.Warn
            )
        }
    }
}

/** One line: what it is, what it comes to, and how many. */
@Composable
private fun BuilderLine(line: DraftLine, onChangeLineQuantity: (String, Double) -> Unit) {
    ListRow(
        title = line.title,
        secondary = line.spec.takeIf { it.isNotBlank() },
        meta = lineMeta(line),
        trailing = {
            CompactStepper(
                value = Money.formatQuantity(line.quantity),
                onDecrement = { onChangeLineQuantity(line.id, -1.0) },
                onIncrement = { onChangeLineQuantity(line.id, 1.0) },
                // Named, because a list with a stepper on every row makes
                // "+" the name of four controls at once.
                incrementLabel = moreOf(line.title),
                decrementLabel = fewerOf(line.title)
            )
        }
    )
}

internal fun moreOf(title: String): String = "One more $title"

internal fun fewerOf(title: String): String = "One fewer $title"

/** `2 each × ₹22,200 = ₹44,400`, or the warning when there is no rate yet. */
internal fun lineMeta(line: DraftLine): String {
    val amount = line.amount ?: return RATE_NEEDED
    val rate = line.rate ?: return RATE_NEEDED
    return "${Money.formatQuantity(line.quantity)} ${line.unit} × " +
        "${Money.formatRupees(rate, decimals = 0)} = " +
        Money.formatRupees(amount, decimals = 0)
}

/** `Dealer rates · 24 Sep 2026`, with the date only once there is one. */
internal fun eyebrow(draft: QuoteDraft): String {
    val rates = "${draft.tier.label} rates"
    if (draft.updatedAt <= 0L) return rates
    return "$rates · ${formatDate(draft.updatedAt)}"
}

internal fun needsRateNote(count: Int): String =
    "$count line${if (count == 1) "" else "s"} still need${if (count == 1) "s" else ""} " +
        "a rate before this can be issued."

internal const val QUOTE_BUILDER_TAG = "quote-builder"

internal const val BUILDER_HEADER_KEY = "builder-header"
internal const val BUILDER_TIER_KEY = "builder-tier"
internal const val BUILDER_EMPTY_KEY = "builder-empty"
internal const val BUILDER_NEEDS_RATE_KEY = "builder-needs-rate"
internal const val BUILDER_CLEAR_KEY = "builder-clear"

/** A scroll target and nothing more. See the panel's KDoc. */
internal const val BUILDER_TAIL_KEY = "builder-end"

internal const val BUILDER_HEADING = "Quotation"
internal const val TIER_LABEL = "Rate"
internal const val BACK_TO_PRODUCTS = "Back to products"
internal const val CLEAR_LINES = "Clear"
internal const val RATE_NEEDED = "Rate needed"
internal const val NOTHING_ON_IT = "Nothing on this quotation yet. Go back and add a product."
internal const val ISSUING_LATER =
    "A number is issued when this is downloaded, printed or shared."
