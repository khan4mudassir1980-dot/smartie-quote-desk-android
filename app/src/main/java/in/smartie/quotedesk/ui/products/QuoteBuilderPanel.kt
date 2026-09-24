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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import `in`.smartie.quotedesk.data.mapping.Money
import `in`.smartie.quotedesk.data.model.PartyRecord
import `in`.smartie.quotedesk.data.model.QuotationPartySnapshot
import `in`.smartie.quotedesk.data.model.RateTierV2
import `in`.smartie.quotedesk.domain.AreaEntry
import `in`.smartie.quotedesk.domain.DimensionUnit
import `in`.smartie.quotedesk.domain.Discount
import `in`.smartie.quotedesk.domain.DiscountKind
import `in`.smartie.quotedesk.domain.DraftLine
import `in`.smartie.quotedesk.domain.Installation
import `in`.smartie.quotedesk.domain.InstallationMode
import `in`.smartie.quotedesk.domain.ManualEntry
import `in`.smartie.quotedesk.domain.Parties
import `in`.smartie.quotedesk.domain.QuoteArea
import `in`.smartie.quotedesk.domain.QuoteDiscount
import `in`.smartie.quotedesk.domain.QuoteDraft
import `in`.smartie.quotedesk.domain.QuoteGst
import `in`.smartie.quotedesk.domain.QuoteLineEntry
import `in`.smartie.quotedesk.domain.QuoteTier
import `in`.smartie.quotedesk.ui.components.CompactStepper
import `in`.smartie.quotedesk.ui.components.EmptyState
import `in`.smartie.quotedesk.ui.components.ListRow
import `in`.smartie.quotedesk.ui.components.SegmentedChoice
import `in`.smartie.quotedesk.ui.components.SegmentedChoiceGrid
import `in`.smartie.quotedesk.ui.components.SmartieCard
import `in`.smartie.quotedesk.ui.components.SmartieField
import `in`.smartie.quotedesk.ui.components.SmartieGhostButton
import `in`.smartie.quotedesk.ui.components.SmartiePrimaryButton
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
    onAddManual: (String, ManualEntry) -> Unit = { _, _ -> },
    onAddArea: (String, AreaEntry) -> Unit = { _, _ -> },
    onEditArea: (DraftLine, AreaEntry) -> Unit = { _, _ -> },
    /** Minted once per line being typed, and reused on a retry. */
    newLineId: () -> String = { "" },
    /** A product's GST rate, by its logical key. */
    gstOf: (String) -> Double? = { null },
    onGstEnabledChange: (Boolean) -> Unit = {},
    onGstPercentChange: (Double?) -> Unit = {},
    onTransportChange: (Double) -> Unit = {},
    onTransportNoteChange: (String) -> Unit = {},
    onInstallationChange: (Installation?) -> Unit = {},
    onDiscountChange: (Discount?) -> Unit = {},
    /** Null when the Owner has not configured one. See `QuoteDiscount`. */
    discountCap: Double? = null
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

    // The two "add a line" forms, collapsed until asked for. Their contents
    // are the panel's own: a half-typed form is not part of the quotation,
    // and pushing every keystroke of it onto the draft would store lines
    // nobody has added yet.
    //
    // Held as the plain strings somebody typed rather than as a parsed entry,
    // because that is what `rememberSaveable` can carry without a custom
    // `Saver` — and because an empty rate box has to stay distinguishable
    // from a zero all the way to `QuoteLineEntry`.
    var manualOpen by rememberSaveable { mutableStateOf(false) }
    var manualTitle by rememberSaveable { mutableStateOf("") }
    var manualQuantity by rememberSaveable { mutableStateOf("1") }
    var manualUnit by rememberSaveable { mutableStateOf("") }
    var manualRate by rememberSaveable { mutableStateOf("") }
    var manualSpec by rememberSaveable { mutableStateOf("") }

    var areaOpen by rememberSaveable { mutableStateOf(false) }
    var areaTitle by rememberSaveable { mutableStateOf("") }
    var areaWidth by rememberSaveable { mutableStateOf("") }
    var areaHeight by rememberSaveable { mutableStateOf("") }
    var areaUnitWire by rememberSaveable { mutableStateOf(DimensionUnit.MM.wireValue) }
    var areaCount by rememberSaveable { mutableStateOf("1") }
    var areaRate by rememberSaveable { mutableStateOf("") }
    var areaMinimum by rememberSaveable { mutableStateOf("") }
    // Which existing opening the area form is editing, if any.
    var editingAreaId by rememberSaveable { mutableStateOf("") }
    var mintedLineId by rememberSaveable { mutableStateOf("") }

    // The money boxes hold what was typed, so a half-typed "18." is not
    // rounded to 18 under the person's fingers and an emptied box is not
    // read as a zero. The draft takes the parsed value as it becomes one.
    var gstTyped by rememberSaveable(draft.gstPercent) {
        mutableStateOf(draft.gstPercent?.let(QuoteLineEntry::plain).orEmpty())
    }
    var transportTyped by rememberSaveable {
        mutableStateOf(if (draft.transport > 0.0) QuoteLineEntry.plain(draft.transport) else "")
    }

    // Installation and the discount are **absent by default, which is not
    // zero**: a quotation that does not charge for fitting carries no
    // installation at all, and one that charges nothing for it is a different
    // statement. The model keeps them nullable for that reason, and these
    // switches are what tell the two apart on screen.
    var installTyped by rememberSaveable { mutableStateOf(rateTextOf(draft.installation)) }
    var basisTyped by rememberSaveable { mutableStateOf(basisTextOf(draft.installation)) }
    var discountTyped by rememberSaveable {
        mutableStateOf(draft.discount?.value?.let(QuoteLineEntry::plain).orEmpty())
    }

    val manualEntry = ManualEntry(
        title = manualTitle,
        quantity = manualQuantity,
        unit = manualUnit,
        rate = manualRate,
        spec = manualSpec
    )
    val areaEntry = AreaEntry(
        width = areaWidth,
        height = areaHeight,
        unit = DimensionUnit.from(areaUnitWire),
        count = areaCount,
        rate = areaRate,
        minimumSqft = areaMinimum,
        title = areaTitle
    )

    fun closeForms() {
        manualOpen = false
        areaOpen = false
        editingAreaId = ""
        mintedLineId = ""
    }

    fun openArea(entry: AreaEntry) {
        areaTitle = entry.title
        areaWidth = entry.width
        areaHeight = entry.height
        areaUnitWire = entry.unit.wireValue
        areaCount = entry.count
        areaRate = entry.rate
        areaMinimum = entry.minimumSqft
        areaOpen = true
        manualOpen = false
    }
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
            BuilderLine(line, onChangeLineQuantity, onRemoveLine) { id ->
                draft.line(id)?.let { existing ->
                    openArea(AreaEntry.of(existing))
                    editingAreaId = id
                }
            }
        }

        item(key = BUILDER_ADD_MANUAL_KEY) {
            SmartieGhostButton(
                text = ADD_MANUAL,
                onClick = {
                    val opening = !manualOpen
                    closeForms()
                    manualOpen = opening
                },
                modifier = Modifier
                    .semantics { contentDescription = ADD_MANUAL }
                    .fillMaxWidth()
            )
        }

        if (manualOpen) {
            entryField(DESCRIPTION_LABEL, manualTitle) { manualTitle = it }
            entryField(QUANTITY_LABEL, manualQuantity, numeric = true) { manualQuantity = it }
            entryField(LINE_UNIT_LABEL, manualUnit, hint = UNIT_HINT) { manualUnit = it }
            entryField(RATE_LABEL, manualRate, numeric = true, hint = RATE_HINT) {
                manualRate = it
            }
            entryField(SPEC_LABEL, manualSpec) { manualSpec = it }
            formFooter(
                key = BUILDER_MANUAL_ADD_KEY,
                addLabel = ADD_MANUAL_LINE,
                refusal = manualEntry.refusal(),
                onCancel = { closeForms() },
                onAdd = {
                    val id = mintedLineId.ifBlank { newLineId().also { mintedLineId = it } }
                    onAddManual(id, manualEntry)
                    closeForms()
                }
            )
        }

        item(key = BUILDER_ADD_AREA_KEY) {
            SmartieGhostButton(
                text = ADD_AREA,
                onClick = {
                    val opening = !areaOpen
                    closeForms()
                    areaOpen = opening
                },
                modifier = Modifier
                    .semantics { contentDescription = ADD_AREA }
                    .fillMaxWidth()
            )
        }

        if (areaOpen) {
            val editing = draft.line(editingAreaId)
            entryField(DESCRIPTION_LABEL, areaTitle) { areaTitle = it }
            entryField(WIDTH_LABEL, areaWidth, numeric = true) { areaWidth = it }
            entryField(HEIGHT_LABEL, areaHeight, numeric = true) { areaHeight = it }
            item(key = MEASURED_IN_LABEL) {
                Column(verticalArrangement = Arrangement.spacedBy(dimens.gapXs)) {
                    Text(
                        MEASURED_IN_LABEL,
                        style = MaterialTheme.typography.labelMedium,
                        color = SmartieColors.Steel
                    )
                    SegmentedChoice(
                        options = DimensionUnit.entries.toList(),
                        selected = DimensionUnit.from(areaUnitWire),
                        label = { it.label },
                        onSelect = { areaUnitWire = it.wireValue }
                    )
                }
            }
            entryField(OPENINGS_LABEL, areaCount, numeric = true) { areaCount = it }
            entryField(RATE_LABEL, areaRate, numeric = true, hint = PER_SQFT_HINT) {
                areaRate = it
            }
            entryField(MINIMUM_LABEL, areaMinimum, numeric = true, hint = MINIMUM_HINT) {
                areaMinimum = it
            }
            item(key = BUILDER_AREA_WORKING_KEY) {
                Text(
                    areaWorking(areaEntry),
                    style = MaterialTheme.typography.labelMedium,
                    color = SmartieColors.Steel
                )
            }
            formFooter(
                key = BUILDER_AREA_ADD_KEY,
                addLabel = if (editing != null) SAVE_OPENING else ADD_AREA_LINE,
                refusal = areaEntry.refusal(),
                onCancel = { closeForms() },
                onAdd = {
                    if (editing != null) {
                        onEditArea(editing, areaEntry)
                    } else {
                        val id = mintedLineId.ifBlank { newLineId().also { mintedLineId = it } }
                        onAddArea(id, areaEntry)
                    }
                    closeForms()
                }
            )
        }

        item(key = BUILDER_INSTALLATION_KEY) {
            InstallationBlock(
                draft = draft,
                rateTyped = installTyped,
                basisTyped = basisTyped,
                onRateTyped = { installTyped = it },
                onBasisTyped = { basisTyped = it },
                onChange = onInstallationChange
            )
        }

        item(key = BUILDER_DISCOUNT_KEY) {
            DiscountBlock(
                draft = draft,
                typed = discountTyped,
                cap = discountCap,
                onTyped = { discountTyped = it },
                onChange = onDiscountChange
            )
        }

        item(key = BUILDER_TRANSPORT_KEY) {
            SmartieField(
                label = TRANSPORT_LABEL,
                value = transportTyped,
                onValueChange = {
                    transportTyped = it
                    // An emptied box is no carriage, which is zero. An
                    // unreadable one changes nothing at all rather than
                    // quietly zeroing a figure somebody entered.
                    if (it.isBlank()) onTransportChange(0.0)
                    else QuoteLineEntry.number(it)?.let(onTransportChange)
                },
                isError = transportRefusal(transportTyped) != null,
                supportingText = transportRefusal(transportTyped),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.semantics { contentDescription = TRANSPORT_LABEL }
            )
        }

        item(key = TRANSPORT_NOTE_LABEL) {
            SmartieField(
                label = TRANSPORT_NOTE_LABEL,
                value = draft.transportNote,
                onValueChange = onTransportNoteChange,
                placeholder = TRANSPORT_NOTE_HINT,
                modifier = Modifier.semantics { contentDescription = TRANSPORT_NOTE_LABEL }
            )
        }

        item(key = BUILDER_GST_KEY) {
            val rates = draft.gstRates(gstOf)
            Column(verticalArrangement = Arrangement.spacedBy(dimens.gapXs)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(dimens.gapS)
                ) {
                    Text(
                        GST_SWITCH_LABEL,
                        style = MaterialTheme.typography.labelMedium,
                        color = SmartieColors.Steel,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = draft.gstEnabled,
                        onCheckedChange = onGstEnabledChange,
                        modifier = Modifier.semantics { contentDescription = GST_SWITCH_LABEL }
                    )
                }
                if (draft.gstEnabled) {
                    SmartieField(
                        label = GST_PERCENT_LABEL,
                        value = gstTyped,
                        onValueChange = {
                            gstTyped = it
                            onGstPercentChange(QuoteLineEntry.number(it))
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.semantics { contentDescription = GST_PERCENT_LABEL }
                    )
                }
                Text(
                    QuoteGst.note(draft.gstEnabled, draft.gstPercent, rates),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (draft.gstEnabled && draft.gstPercent == null) {
                        SmartieColors.Warn
                    } else {
                        SmartieColors.Steel
                    }
                )
            }
        }

        item(key = BUILDER_TOTALS_KEY) { Totals(draft) }

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

/**
 * One box on an "add a line" form, keyed by its own label.
 *
 * A number box gets the decimal keyboard, but **never a filtered one**: a
 * person who types `12x` sees `12x` and a refusal that says why, rather than
 * a box that silently swallowed the `x` and priced the line at twelve.
 */
private fun LazyListScope.entryField(
    label: String,
    value: String,
    numeric: Boolean = false,
    hint: String? = null,
    onChange: (String) -> Unit
) {
    item(key = label) {
        SmartieField(
            label = label,
            value = value,
            onValueChange = onChange,
            placeholder = hint,
            keyboardOptions = if (numeric) {
                KeyboardOptions(keyboardType = KeyboardType.Decimal)
            } else {
                KeyboardOptions.Default
            },
            modifier = Modifier.semantics { contentDescription = label }
        )
    }
}

/**
 * Add and Cancel, with the refusal above them.
 *
 * **Add stays enabled and the refusal is shown**, rather than the control
 * greying out with no explanation. A disabled button is a question nobody
 * can answer; `QuoteMath.discountRefusal`'s KDoc already gives the general
 * reason — name the number the person may have, do not silently clamp.
 */
private fun LazyListScope.formFooter(
    key: String,
    addLabel: String,
    refusal: String?,
    onCancel: () -> Unit,
    onAdd: () -> Unit
) {
    if (refusal != null) {
        item(key = "$key-refusal") {
            Text(
                refusal,
                style = MaterialTheme.typography.labelMedium,
                color = SmartieColors.Danger
            )
        }
    }
    item(key = key) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmartiePrimaryButton(
                text = addLabel,
                onClick = onAdd,
                enabled = refusal == null,
                modifier = Modifier.semantics { contentDescription = addLabel }
            )
            SmartieGhostButton(
                text = CANCEL_LINE,
                onClick = onCancel,
                modifier = Modifier.semantics { contentDescription = CANCEL_LINE }
            )
        }
    }
}

/**
 * What the opening comes to as it is typed, so nobody has to add it up.
 *
 * `113.5 sq ft × 2 nos = 227 sq ft`. Silent while the measurements are not
 * yet a measurement — a running total that reads zero on a half-typed width
 * is worse than one that waits.
 */
internal fun areaWorking(entry: AreaEntry): String {
    val area = entry.toArea() ?: return AREA_NOT_YET
    if (QuoteArea.refusal(area) != null) return AREA_NOT_YET
    return "${Money.formatQuantity(QuoteArea.chargeableSqft(area))} sq ft × " +
        "${Money.formatQuantity(area.count)} nos = " +
        "${Money.formatQuantity(QuoteArea.totalSqft(area))} sq ft"
}

/**
 * Installation: absent, or one of four ways of charging for it.
 *
 * **Absent is not zero.** A quotation that does not charge for fitting
 * carries no installation at all; one that charges nothing for it is a
 * different statement, and `QuoteDraft.installation` is nullable to keep the
 * two apart. The switch is what says which.
 *
 * **The basis is defaulted and then editable**, which is why it is stored
 * rather than re-derived: a quotation reopened a month later must show the
 * figure the price was actually struck on, not whatever the lines would
 * produce today. The default comes from the lines — the door count for a
 * per-door charge, the chargeable area for a per-square-foot one, the
 * products figure for a percentage — and a fixed amount ignores it entirely.
 */
@Composable
private fun InstallationBlock(
    draft: QuoteDraft,
    rateTyped: String,
    basisTyped: String,
    onRateTyped: (String) -> Unit,
    onBasisTyped: (String) -> Unit,
    onChange: (Installation?) -> Unit
) {
    val dimens = LocalSmartieDimens.current
    val charge = draft.installation
    val mode = charge?.mode ?: InstallationMode.FIXED

    fun push(
        newMode: InstallationMode = mode,
        rate: String = rateTyped,
        basis: String = basisTyped
    ) {
        val amount = QuoteLineEntry.number(rate) ?: 0.0
        val against = QuoteLineEntry.number(basis) ?: draft.defaultBasisFor(newMode)
        onChange(Installation(newMode, amount, against))
    }

    Column(verticalArrangement = Arrangement.spacedBy(dimens.gapXs)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.gapS)
        ) {
            Text(
                INSTALLATION_LABEL,
                style = MaterialTheme.typography.labelMedium,
                color = SmartieColors.Steel,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = charge != null,
                onCheckedChange = { on ->
                    if (!on) {
                        onChange(null)
                    } else {
                        val basis = draft.defaultBasisFor(InstallationMode.FIXED)
                        onBasisTyped(QuoteLineEntry.plain(basis))
                        onChange(Installation(InstallationMode.FIXED, 0.0, basis))
                    }
                },
                modifier = Modifier.semantics { contentDescription = INSTALLATION_LABEL }
            )
        }
        if (charge == null) {
            Text(
                NO_INSTALLATION,
                style = MaterialTheme.typography.labelMedium,
                color = SmartieColors.Steel
            )
            return@Column
        }

        // Two rows of two. Four in one strip clips "% of products" at 360dp,
        // and a truncated label here is the difference between a fixed 8
        // rupees and 8% of the products.
        SegmentedChoiceGrid(
            options = InstallationMode.entries.toList(),
            selected = mode,
            label = { it.label },
            onSelect = { chosen ->
                // The basis follows the mode until somebody edits it: a
                // per-door figure means nothing to a per-square-foot charge.
                val basis = draft.defaultBasisFor(chosen)
                onBasisTyped(QuoteLineEntry.plain(basis))
                push(newMode = chosen, basis = QuoteLineEntry.plain(basis))
            },
            modifier = Modifier.semantics { contentDescription = INSTALLATION_MODE_LABEL }
        )
        SmartieField(
            label = installationRateLabel(mode),
            value = rateTyped,
            onValueChange = {
                onRateTyped(it)
                push(rate = it)
            },
            isError = negativeRefusal(rateTyped) != null,
            supportingText = negativeRefusal(rateTyped),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.semantics { contentDescription = INSTALLATION_RATE_LABEL }
        )
        if (mode != InstallationMode.FIXED) {
            SmartieField(
                label = basisLabel(mode),
                value = basisTyped,
                onValueChange = {
                    onBasisTyped(it)
                    push(basis = it)
                },
                isError = negativeRefusal(basisTyped) != null,
                supportingText = negativeRefusal(basisTyped),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.semantics { contentDescription = INSTALLATION_BASIS_LABEL }
            )
        }
        Text(
            installationWorking(charge),
            style = MaterialTheme.typography.labelMedium,
            color = SmartieColors.Steel
        )
    }
}

/**
 * The one discount a quotation may carry, as a percentage or as an amount.
 *
 * **The refusal names the number the person may have rather than clamping
 * what they typed** — `QuoteMath.discountRefusal`'s own rule, because a
 * quotation that went out at a discount nobody chose is worse than one that
 * would not save. And a cap nobody configured is told apart from a cap of
 * zero: both permit no discount, only one is somebody's mistake.
 */
@Composable
private fun DiscountBlock(
    draft: QuoteDraft,
    typed: String,
    cap: Double?,
    onTyped: (String) -> Unit,
    onChange: (Discount?) -> Unit
) {
    val dimens = LocalSmartieDimens.current
    val discount = draft.discount
    val kind = discount?.kind ?: DiscountKind.PERCENT
    val refusal = discount?.let { QuoteDiscount.refusal(it, draft.discountBase, cap) }

    Column(verticalArrangement = Arrangement.spacedBy(dimens.gapXs)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.gapS)
        ) {
            Text(
                DISCOUNT_LABEL,
                style = MaterialTheme.typography.labelMedium,
                color = SmartieColors.Steel,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = discount != null,
                onCheckedChange = { on ->
                    onChange(if (on) Discount(DiscountKind.PERCENT, 0.0) else null)
                },
                modifier = Modifier.semantics { contentDescription = DISCOUNT_LABEL }
            )
        }
        if (discount == null) {
            Text(
                NO_DISCOUNT,
                style = MaterialTheme.typography.labelMedium,
                color = SmartieColors.Steel
            )
            return@Column
        }

        SegmentedChoice(
            options = DiscountKind.entries.toList(),
            selected = kind,
            label = { discountKindLabel(it) },
            onSelect = { onChange(Discount(it, QuoteLineEntry.number(typed) ?: 0.0)) },
            modifier = Modifier.semantics { contentDescription = DISCOUNT_KIND_LABEL }
        )
        SmartieField(
            label = discountValueLabel(kind),
            value = typed,
            onValueChange = {
                onTyped(it)
                onChange(Discount(kind, QuoteLineEntry.number(it) ?: 0.0))
            },
            isError = refusal != null,
            supportingText = refusal,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.semantics { contentDescription = DISCOUNT_VALUE_LABEL }
        )
    }
}

internal fun rateTextOf(charge: Installation?): String =
    charge?.rate?.let(QuoteLineEntry::plain).orEmpty()

internal fun basisTextOf(charge: Installation?): String =
    charge?.basis?.let(QuoteLineEntry::plain).orEmpty()

/** Why this installation figure cannot be charged, or null when it can. */
internal fun negativeRefusal(typed: String): String? = when {
    typed.isBlank() -> null
    !QuoteLineEntry.reads(typed) -> QuoteLineEntry.NOT_A_RATE
    (QuoteLineEntry.number(typed) ?: 0.0) < 0.0 -> QuoteDraft.NEGATIVE_INSTALLATION
    else -> null
}

internal fun installationRateLabel(mode: InstallationMode): String = when (mode) {
    InstallationMode.FIXED -> "Amount"
    InstallationMode.PER_DOOR -> "Rate per door"
    InstallationMode.PER_SQFT -> "Rate per sq ft"
    InstallationMode.PERCENT -> "Percentage of products"
}

internal fun basisLabel(mode: InstallationMode): String = when (mode) {
    InstallationMode.PER_DOOR -> "How many doors"
    InstallationMode.PER_SQFT -> "How many sq ft"
    InstallationMode.PERCENT -> "Charged against"
    InstallationMode.FIXED -> "Charged against"
}

internal fun installationWorking(charge: Installation): String = when (charge.mode) {
    InstallationMode.FIXED -> Money.formatRupees(charge.amount, decimals = 0)
    InstallationMode.PERCENT ->
        "${Money.formatQuantity(charge.rate)}% of " +
            "${Money.formatRupees(charge.basis, decimals = 0)} = " +
            Money.formatRupees(charge.amount, decimals = 0)
    else ->
        "${Money.formatQuantity(charge.basis)} × " +
            "${Money.formatRupees(charge.rate, decimals = 0)} = " +
            Money.formatRupees(charge.amount, decimals = 0)
}

internal fun discountKindLabel(kind: DiscountKind): String = when (kind) {
    DiscountKind.PERCENT -> "Percentage"
    DiscountKind.RUPEES -> "Amount"
}

internal fun discountValueLabel(kind: DiscountKind): String = when (kind) {
    DiscountKind.PERCENT -> "Discount %"
    DiscountKind.RUPEES -> "Discount ₹"
}

/**
 * What the quotation comes to, in the order the printed page builds it.
 *
 * Products, then installation, then the discount, then transport, then GST on
 * the lot — `QuoteMath.totals`' own order, which is also V8C4's and the one
 * `docs/N5-plan.md` sets out. A row that is not on this quotation is not
 * drawn, so a quotation with no discount does not carry a line reading zero.
 *
 * **GST reads as unset rather than as nothing.** `toCharges` turns an
 * unresolved rate into "no GST", which is right for the arithmetic — it
 * refuses to invent a rate — and would be a lie on the page. So the row says
 * so in words instead of printing a zero somebody might trust.
 */
@Composable
private fun Totals(draft: QuoteDraft) {
    val dimens = LocalSmartieDimens.current
    val totals = draft.totals()
    val gstUnset = draft.gstEnabled && draft.gstPercent == null
    SmartieCard {
        Column(verticalArrangement = Arrangement.spacedBy(dimens.gapXs)) {
            TotalRow(PRODUCTS_ROW, Money.formatRupees(totals.products, decimals = 0))
            if (totals.installation != 0.0) {
                TotalRow(INSTALLATION_ROW, Money.formatRupees(totals.installation, decimals = 0))
            }
            if (totals.discount != 0.0) {
                TotalRow(DISCOUNT_ROW, "- ${Money.formatRupees(totals.discount, decimals = 0)}")
            }
            if (totals.transport != 0.0) {
                TotalRow(TRANSPORT_ROW, Money.formatRupees(totals.transport, decimals = 0))
            }
            TotalRow(SUBTOTAL_ROW, Money.formatRupees(totals.subtotal, decimals = 0))
            if (draft.gstEnabled) {
                TotalRow(
                    if (gstUnset) GST_ROW else gstRowLabel(totals.gstPercent),
                    if (gstUnset) GST_UNSET else Money.formatRupees(totals.gst, decimals = 0),
                    warn = gstUnset
                )
            }
            TotalRow(
                TOTAL_ROW,
                if (gstUnset) TOTAL_UNSET else Money.formatRupees(totals.total, decimals = 0),
                emphasise = true,
                warn = gstUnset
            )
        }
    }
}

@Composable
private fun TotalRow(
    label: String,
    value: String,
    emphasise: Boolean = false,
    warn: Boolean = false
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = if (emphasise) {
                MaterialTheme.typography.titleSmall
            } else {
                MaterialTheme.typography.bodySmall
            },
            color = SmartieColors.Steel
        )
        Text(
            value,
            style = if (emphasise) {
                MaterialTheme.typography.titleMedium
            } else {
                MaterialTheme.typography.bodyMedium
            },
            color = when {
                warn -> SmartieColors.Warn
                emphasise -> SmartieColors.Ink
                else -> SmartieColors.Ink2
            }
        )
    }
}

internal fun gstRowLabel(percent: Double): String = "GST ${Money.formatQuantity(percent)}%"

/** Why this carriage cannot be charged, or null when it can. */
internal fun transportRefusal(typed: String): String? = when {
    typed.isBlank() -> null
    !QuoteLineEntry.reads(typed) -> QuoteLineEntry.NOT_A_RATE
    (QuoteLineEntry.number(typed) ?: 0.0) < 0.0 -> QuoteDraft.NEGATIVE_TRANSPORT
    else -> null
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
internal const val BUILDER_ADD_MANUAL_KEY = "builder-add-manual"
internal const val BUILDER_ADD_AREA_KEY = "builder-add-area"
internal const val BUILDER_MANUAL_ADD_KEY = "builder-manual-add"
internal const val BUILDER_AREA_ADD_KEY = "builder-area-add"
internal const val BUILDER_AREA_WORKING_KEY = "builder-area-working"
internal const val BUILDER_INSTALLATION_KEY = "builder-installation"
internal const val BUILDER_DISCOUNT_KEY = "builder-discount"
internal const val BUILDER_TRANSPORT_KEY = "builder-transport"
internal const val BUILDER_GST_KEY = "builder-gst"
internal const val BUILDER_TOTALS_KEY = "builder-totals"
internal const val BUILDER_EMPTY_KEY = "builder-empty"
internal const val BUILDER_NEEDS_RATE_KEY = "builder-needs-rate"
internal const val BUILDER_CLEAR_KEY = "builder-clear"

/** A scroll target and nothing more. See the panel's KDoc. */
internal const val BUILDER_TAIL_KEY = "builder-end"

internal const val BUILDER_HEADING = "Quotation"
internal const val TIER_LABEL = "Rate type"
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

internal const val ADD_MANUAL = "Add an item by hand"
internal const val ADD_AREA = "Add an opening, priced by area"
internal const val ADD_MANUAL_LINE = "Add this item"
internal const val ADD_AREA_LINE = "Add this opening"
internal const val SAVE_OPENING = "Save this opening"
internal const val CANCEL_LINE = "Cancel"

internal const val DESCRIPTION_LABEL = "Description"
internal const val QUANTITY_LABEL = "How many"
internal const val LINE_UNIT_LABEL = "Unit"
internal const val UNIT_HINT = "each, nos, m, kg"
internal const val RATE_LABEL = "Rate"
internal const val RATE_HINT = "Leave empty if the price is not set yet"
internal const val SPEC_LABEL = "Details"
internal const val WIDTH_LABEL = "Width"
internal const val HEIGHT_LABEL = "Height"
internal const val MEASURED_IN_LABEL = "Measured in"
internal const val OPENINGS_LABEL = "How many openings this size"
internal const val PER_SQFT_HINT = "Per square foot"
internal const val MINIMUM_LABEL = "Minimum chargeable area"
internal const val MINIMUM_HINT = "Leave empty if there is no minimum"
internal const val AREA_NOT_YET = "Enter a width and a height to see the chargeable area."

internal const val INSTALLATION_LABEL = "Charge for installation"
internal const val INSTALLATION_MODE_LABEL = "How installation is charged"
internal const val INSTALLATION_RATE_LABEL = "Installation rate"
internal const val INSTALLATION_BASIS_LABEL = "Installation basis"
internal const val NO_INSTALLATION = "No installation charge on this quotation."
internal const val DISCOUNT_LABEL = "Give a discount"
internal const val DISCOUNT_KIND_LABEL = "Discount as"
internal const val DISCOUNT_VALUE_LABEL = "Discount"
internal const val NO_DISCOUNT = "No discount on this quotation."
internal const val TRANSPORT_LABEL = "Transport"
internal const val TRANSPORT_NOTE_LABEL = "What the transport is for"
internal const val TRANSPORT_NOTE_HINT = "e.g. Mumbai to Vadodara"
internal const val GST_SWITCH_LABEL = "Include GST"
internal const val GST_PERCENT_LABEL = "GST %"
internal const val PRODUCTS_ROW = "Products"
internal const val INSTALLATION_ROW = "Installation"
internal const val DISCOUNT_ROW = "Discount"
internal const val TRANSPORT_ROW = "Transport"
internal const val SUBTOTAL_ROW = "Subtotal"
internal const val GST_ROW = "GST"
internal const val TOTAL_ROW = "Grand total"
internal const val GST_UNSET = "rate not set"
internal const val TOTAL_UNSET = "set the GST rate"
internal const val NOTHING_ON_IT = "Nothing on this quotation yet. Go back and add a product."
internal const val ISSUING_LATER =
    "A number is issued when this is downloaded, printed or shared."
