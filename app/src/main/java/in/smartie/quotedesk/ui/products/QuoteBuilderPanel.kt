package `in`.smartie.quotedesk.ui.products

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import `in`.smartie.quotedesk.data.mapping.Money
import `in`.smartie.quotedesk.data.model.PartyRecord
import `in`.smartie.quotedesk.data.model.QuotationPartySnapshot
import `in`.smartie.quotedesk.data.model.RateTierV2
import `in`.smartie.quotedesk.domain.DraftLine
import `in`.smartie.quotedesk.domain.Parties
import `in`.smartie.quotedesk.domain.QuoteArea
import `in`.smartie.quotedesk.domain.QuoteDraft
import `in`.smartie.quotedesk.domain.QuoteTier
import `in`.smartie.quotedesk.ui.components.CompactStepper
import `in`.smartie.quotedesk.ui.components.EmptyState
import `in`.smartie.quotedesk.ui.components.ListRow
import `in`.smartie.quotedesk.ui.components.SegmentedChoice
import `in`.smartie.quotedesk.ui.components.SmartieField
import `in`.smartie.quotedesk.ui.components.SmartieGhostButton
import `in`.smartie.quotedesk.ui.components.Tag
import `in`.smartie.quotedesk.ui.components.TagTone
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
    onRemoveLine: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    parties: List<PartyRecord> = emptyList(),
    onPartyChange: (QuotationPartySnapshot) -> Unit = {},
    onChooseParty: (PartyRecord) -> Unit = {},
    onSaveCustomer: (String) -> Unit = {},
    savingCustomer: Boolean = false,
    customerFailure: String? = null,
    /** Minted once, and reused on a retry. See `ProductsViewModel.mintPartyId`. */
    newPartyId: () -> String = { "" },
    /** Reopening an opening's form. The area form itself is the next commit. */
    onOpenArea: (String) -> Unit = {}
) {
    // Which saved customer the picker is showing, and what is typed into its
    // search box. The panel's own state: nothing about it belongs on a draft
    // that survives the app being killed.
    var picking by rememberSaveable { mutableStateOf(false) }
    var partyQuery by rememberSaveable { mutableStateOf("") }
    // One id for as long as this person is filling one quotation in, so a
    // retry after an ambiguous failure lands on the same customer rather than
    // writing a second — N4.4's B2 lesson, the shape `PartiesScreen` uses.
    var mintedPartyId by rememberSaveable { mutableStateOf("") }
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

        item(key = BUILDER_PICK_PARTY_KEY) {
            SmartieGhostButton(
                text = if (picking) CLOSE_PARTY_PICKER else CHOOSE_PARTY,
                onClick = { picking = !picking },
                modifier = Modifier
                    .semantics {
                        contentDescription = if (picking) CLOSE_PARTY_PICKER else CHOOSE_PARTY
                    }
                    .fillMaxWidth()
            )
        }

        if (picking) {
            item(key = BUILDER_PARTY_SEARCH_KEY) {
                SmartieField(
                    label = PARTY_SEARCH_LABEL,
                    value = partyQuery,
                    onValueChange = { partyQuery = it },
                    placeholder = PARTY_SEARCH_HINT,
                    modifier = Modifier.semantics { contentDescription = PARTY_SEARCH_LABEL }
                )
            }
            // Archived parties are not offered. A quotation raised today
            // against a customer somebody retired is a mistake nobody would
            // make on purpose, and `PartyBook` already separates the two.
            val book = Parties.build(parties, partyQuery)
            if (book.active.isEmpty()) {
                item(key = BUILDER_NO_PARTIES_KEY) { EmptyState(noSavedParty(partyQuery)) }
            }
            items(book.active, key = { party -> "party-${party.id}" }) { party ->
                PartyChoice(party) {
                    onChooseParty(party)
                    picking = false
                    partyQuery = ""
                }
            }
        }

        partyFields(draft.party, onPartyChange)

        if (customerFailure != null) {
            item(key = BUILDER_CUSTOMER_FAILURE_KEY) {
                Text(
                    customerFailure,
                    style = MaterialTheme.typography.bodyMedium,
                    color = SmartieColors.Danger
                )
            }
        }

        item(key = BUILDER_SAVE_CUSTOMER_KEY) {
            SmartieGhostButton(
                text = SAVE_CUSTOMER,
                onClick = {
                    val id = mintedPartyId.ifBlank { newPartyId().also { mintedPartyId = it } }
                    onSaveCustomer(id)
                },
                enabled = !savingCustomer,
                modifier = Modifier
                    .semantics { contentDescription = SAVE_CUSTOMER }
                    .fillMaxWidth()
            )
        }

        if (draft.isEmpty) {
            item(key = BUILDER_EMPTY_KEY) { EmptyState(NOTHING_ON_IT) }
        }

        items(draft.lines, key = { line -> line.id }) { line ->
            BuilderLine(line, onChangeLineQuantity, onRemoveLine, onOpenArea)
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
 * The customer block: seven boxes, each keyed by its own label.
 *
 * **Every keystroke reaches the draft, and that is deliberate.** The party is
 * part of the quotation, not part of a form that is saved at the end, so a
 * half-typed customer has to survive the app being killed exactly as a
 * half-built line list does (audit C8). That costs one small device write per
 * character, which is the same shape as one per tap on a stepper and is the
 * price of the draft being honest about what it holds.
 *
 * Each item's key **is** its label, so a test scrolls to a box by the name it
 * then types into and the two cannot drift apart — `PartyEditPanel`'s idiom.
 */
private fun LazyListScope.partyFields(
    party: QuotationPartySnapshot,
    onPartyChange: (QuotationPartySnapshot) -> Unit
) {
    fun field(label: String, value: String, update: (String) -> QuotationPartySnapshot) {
        item(key = label) {
            SmartieField(
                label = label,
                value = value,
                onValueChange = { onPartyChange(update(it)) },
                modifier = Modifier.semantics { contentDescription = label }
            )
        }
    }
    field(PARTY_NAME_LABEL, party.name) { party.copy(name = it) }
    // Where the job is, which is not where the firm is. `QuoteParty` keeps
    // the two apart in both directions.
    field(SITE_LABEL, party.site) { party.copy(site = it) }
    field(GSTIN_LABEL, party.gstin) { party.copy(gstin = it) }
    field(CONTACT_LABEL, party.contact) { party.copy(contact = it) }
    field(PHONE_LABEL, party.phone) { party.copy(phone = it) }
    field(EMAIL_LABEL, party.email) { party.copy(email = it) }
    field(ADDRESS_LABEL, party.address) { party.copy(address = it) }
}

/** One saved customer, with enough to tell two of the same name apart. */
@Composable
private fun PartyChoice(party: PartyRecord, onChoose: () -> Unit) {
    val name = Parties.displayName(party)
    ListRow(
        title = name,
        secondary = listOf(party.contact, party.city).filter { it.isNotBlank() }
            .joinToString(" · ")
            .takeIf { it.isNotBlank() },
        meta = party.phone.takeIf { it.isNotBlank() },
        modifier = Modifier.semantics { contentDescription = chooseLabel(name) },
        onClick = onChoose
    )
}

internal fun chooseLabel(name: String): String = "Quote to $name"

internal fun noSavedParty(query: String): String =
    if (query.isBlank()) "No saved customers yet. Type the details below instead."
    else "No saved customer matches \u201c${query.trim()}\u201d."

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

/**
 * One line, in one of three shapes, reusing `QuotationDetail`'s row so a
 * draft and the finalised quotation read alike.
 *
 * **The area line is the one that is different, and it is not a preference.**
 * A catalogue or hand-typed line gets a stepper; an opening gets neither
 * stepper nor quantity box. Its stored quantity is the *total chargeable
 * area* — `chargeableSqft × nos` — which `QuoteDraft.setArea` recomputes
 * from the opening every time the opening changes. Letting the quantity be
 * typed directly would break that: the stored figure would stop matching the
 * geometry, and the spec sentence — `3000 × 3500 mm = 113.5 sq ft × 2 nos` —
 * would start describing a line that no longer exists. The editable inputs
 * are W, H, the unit and the door count, all inside the area form, which
 * tapping the card reopens.
 *
 * The amount is the trailing figure, as it is on the detail screen, and the
 * controls sit in the card's own footer row rather than beside the
 * information. That is `ListRow`'s `footer` slot doing what N4.4's B1 taught:
 * a second thing in the trailing position is what wraps and gets clipped.
 */
@Composable
private fun BuilderLine(
    line: DraftLine,
    onChangeLineQuantity: (String, Double) -> Unit,
    onRemoveLine: (String) -> Unit,
    onOpenArea: (String) -> Unit
) {
    val dimens = LocalSmartieDimens.current
    ListRow(
        title = line.title,
        secondary = lineSecondary(line),
        meta = lineMeta(line),
        tags = {
            // An opening is labelled too: `unit` reads "per sq ft", which is
            // not something a reader should have to infer from the spec.
            if (line.isArea) Tag(AREA_LINE, TagTone.PURPLE)
            else if (line.manual) Tag(MANUAL_LINE, TagTone.NEUTRAL)
        },
        trailing = {
            Text(
                line.amount?.let { Money.formatRupees(it, decimals = 0) } ?: RATE_NEEDED,
                style = MaterialTheme.typography.titleSmall,
                color = if (line.needsRate) SmartieColors.Warn else SmartieColors.Ink
            )
        },
        onClick = if (line.isArea) ({ onOpenArea(line.id) }) else null,
        footer = {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(dimens.gapS)
            ) {
                if (!line.isArea) {
                    CompactStepper(
                        value = Money.formatQuantity(line.quantity),
                        onDecrement = { onChangeLineQuantity(line.id, -1.0) },
                        onIncrement = { onChangeLineQuantity(line.id, 1.0) },
                        // Named, because a list with a stepper on every row
                        // makes "+" the name of four controls at once.
                        incrementLabel = moreOf(line.title),
                        decrementLabel = fewerOf(line.title)
                    )
                }
                Spacer(Modifier.weight(1f))
                SmartieGhostButton(
                    text = REMOVE,
                    onClick = { onRemoveLine(line.id) },
                    danger = true,
                    compact = true,
                    modifier = Modifier.semantics { contentDescription = removeLabel(line.title) }
                )
            }
        }
    )
}

internal fun moreOf(title: String): String = "One more $title"

internal fun fewerOf(title: String): String = "One fewer $title"

internal fun removeLabel(title: String): String = "Remove $title"

/**
 * The line under the title: the opening's own working for an area line, and
 * whatever spec the product carries for every other kind.
 *
 * `QuoteArea.describe` is `3000 × 3500 mm = 113.5 sq ft × ₹450 × 2 nos` — the
 * rate included, because this is the card and not the stored `s` field.
 * `describeGeometry` is the rate-free one that goes on the wire, so a later
 * correction to the rate cannot leave a stored sentence contradicting the
 * rate column printed beside it.
 */
internal fun lineSecondary(line: DraftLine): String? {
    val area = line.area ?: return line.spec.takeIf { it.isNotBlank() }
    val rate = line.rate ?: return QuoteArea.describeGeometry(area)
    return QuoteArea.describe(area, rate)
}

/** `2 each × ₹22,200`, or the warning when there is no rate yet. */
internal fun lineMeta(line: DraftLine): String {
    val rate = line.rate ?: return RATE_NEEDED
    return "${Money.formatQuantity(line.quantity)} ${line.unit} × " +
        Money.formatRupees(rate, decimals = 0)
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
internal const val BUILDER_PICK_PARTY_KEY = "builder-pick-party"
internal const val BUILDER_PARTY_SEARCH_KEY = "builder-party-search"
internal const val BUILDER_NO_PARTIES_KEY = "builder-no-parties"
internal const val BUILDER_SAVE_CUSTOMER_KEY = "builder-save-customer"
internal const val BUILDER_CUSTOMER_FAILURE_KEY = "builder-customer-failure"
internal const val BUILDER_EMPTY_KEY = "builder-empty"
internal const val BUILDER_NEEDS_RATE_KEY = "builder-needs-rate"
internal const val BUILDER_CLEAR_KEY = "builder-clear"

/** A scroll target and nothing more. See the panel's KDoc. */
internal const val BUILDER_TAIL_KEY = "builder-end"

internal const val BUILDER_HEADING = "Quotation"
internal const val TIER_LABEL = "Rate"
internal const val CHOOSE_PARTY = "Choose a saved customer"
internal const val CLOSE_PARTY_PICKER = "Type the customer instead"
internal const val PARTY_SEARCH_LABEL = "Find a customer"
internal const val PARTY_SEARCH_HINT = "Name, contact, phone or GSTIN"
internal const val PARTY_NAME_LABEL = "Customer"
internal const val SITE_LABEL = "Site"
internal const val GSTIN_LABEL = "GSTIN"
internal const val CONTACT_LABEL = "Contact person"
internal const val PHONE_LABEL = "Phone"
internal const val EMAIL_LABEL = "Email"
internal const val ADDRESS_LABEL = "Address"
internal const val SAVE_CUSTOMER = "Save this customer"
internal const val BACK_TO_PRODUCTS = "Back to products"
internal const val CLEAR_LINES = "Clear"
internal const val RATE_NEEDED = "Rate needed"
internal const val REMOVE = "Remove"
internal const val MANUAL_LINE = "Typed by hand"
internal const val AREA_LINE = "By area"
internal const val NOTHING_ON_IT = "Nothing on this quotation yet. Go back and add a product."
internal const val ISSUING_LATER =
    "A number is issued when this is downloaded, printed or shared."
