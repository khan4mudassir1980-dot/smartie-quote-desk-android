package `in`.smartie.quotedesk.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import `in`.smartie.quotedesk.data.model.NumberingRecord
import `in`.smartie.quotedesk.data.model.QuotingRecord
import `in`.smartie.quotedesk.domain.DiscountCap
import `in`.smartie.quotedesk.domain.Numbering
import `in`.smartie.quotedesk.domain.NumberingDraft
import `in`.smartie.quotedesk.ui.components.ConfirmDialog
import `in`.smartie.quotedesk.ui.components.SectionHeader
import `in`.smartie.quotedesk.ui.components.SmartieCard
import `in`.smartie.quotedesk.ui.components.SmartieField
import `in`.smartie.quotedesk.ui.components.SmartiePrimaryButton
import `in`.smartie.quotedesk.ui.theme.LocalSmartieDimens
import `in`.smartie.quotedesk.ui.theme.SmartieColors

/**
 * Settings: the quotation counter and the Manager discount limit.
 *
 * **Only the Owner changes anything here, and everybody else who can quote can
 * still look.** That is not a courtesy — the deployed read rule allows it, and
 * a Manager who can see that the next number is `SIE/QD/2025-26/010` can tell
 * the Owner the counter is wrong. So an Administrator and a Manager get the
 * same facts with no controls, and a Staff account never reaches the screen at
 * all.
 *
 * **The preview is the reason the screen exists.** A prefix, a year, a count
 * and a padding are four fields nobody assembles in their head, and getting
 * one wrong is invisible until a quotation has gone out under the wrong
 * reference — so the number is shown exactly as it will be issued, live, from
 * the same function that will issue it.
 *
 * **Two edits are confirmed and the rest are not.** Changing the financial
 * year or the next number changes *which numbers exist*; changing the prefix
 * or the padding only changes how the next one reads, which the preview
 * already shows. A confirmation on every edit is a confirmation nobody reads.
 *
 * Nothing here issues a number. N5.9 builds that.
 */
@Composable
internal fun SettingsScreen(
    numbering: NumberingRecord?,
    quoting: QuotingRecord?,
    capabilities: SettingsCapabilities,
    loading: Boolean = false,
    saving: Boolean = false,
    error: String? = null,
    actions: SettingsActions = SettingsActions(),
) {
    val dimens = LocalSmartieDimens.current
    val stored = numbering ?: NumberingRecord()
    val cap = quoting?.managerDiscountPct ?: DiscountCap.NONE

    // Seeded once, when the first snapshot lands. Keyed on *whether* there is
    // a record rather than on its contents, so a later snapshot — including
    // the echo of this screen's own save — cannot wipe what somebody is in the
    // middle of typing.
    val seeded = numbering != null
    var prefix by rememberSaveable(seeded) { mutableStateOf(stored.prefix) }
    var year by rememberSaveable(seeded) { mutableStateOf(stored.financialYear) }
    var next by rememberSaveable(seeded) { mutableStateOf(stored.next.toString()) }
    var pad by rememberSaveable(seeded) { mutableStateOf(stored.pad.toString()) }
    var capTyped by rememberSaveable(quoting != null) { mutableStateOf(trimZero(cap)) }
    var confirming by rememberSaveable { mutableStateOf(false) }

    val draft = NumberingDraft(
        prefix = prefix,
        financialYear = year,
        next = next.trim().toIntOrNull() ?: 0,
        pad = pad.trim().toIntOrNull() ?: 0
    )
    // Live, so a next that is already spent is refused while it is being
    // typed rather than when Save is pressed. The rules refuse the same write
    // for the same reason; this is so the person is told which number to use.
    val refusal = if (capabilities.canConfigureNumbering) Numbering.refusal(stored, draft) else null
    // One refusal gates Save, but it is shown under the box it is about: a
    // message about the financial year sitting under the next number is how a
    // person ends up changing the wrong field.
    val prefixError = refusal?.takeIf { it == Numbering.PREFIX_REQUIRED }
    val yearError = refusal?.takeIf { it == Numbering.YEAR_REQUIRED }
    val padError = refusal?.takeIf { it == Numbering.PAD_OUT_OF_RANGE }
    val nextError = refusal?.takeIf { prefixError == null && yearError == null && padError == null }
    val consequence = Numbering.consequenceOf(stored, draft)
    // A box that is empty or not a number is as unsavable as one that is out
    // of range, and `?.let { refusal(it) } ?: OUT_OF_RANGE` would report a
    // *valid* figure as out of range, because a valid one returns null too.
    val capValue = capTyped.trim().toDoubleOrNull()
    val capRefusal = if (capValue == null) DiscountCap.OUT_OF_RANGE else DiscountCap.refusal(capValue)

    fun submit() {
        if (refusal != null) return
        if (consequence != null) confirming = true else actions.onSaveNumbering(draft)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag(SETTINGS_LIST_TAG),
        contentPadding = PaddingValues(
            start = dimens.screenPadding,
            end = dimens.screenPadding,
            top = dimens.gapM,
            bottom = dimens.listBottomInset
        ),
        verticalArrangement = Arrangement.spacedBy(dimens.gapS)
    ) {
        item(key = NUMBERING_SECTION) { SectionHeader(NUMBERING_SECTION) }

        item(key = PREVIEW_KEY) {
            SmartieCard {
                Column {
                    Text(
                        PREVIEW_LABEL,
                        style = MaterialTheme.typography.labelSmall,
                        color = SmartieColors.Steel
                    )
                    Text(
                        Numbering.format(draft),
                        style = MaterialTheme.typography.headlineSmall,
                        color = SmartieColors.Ink
                    )
                    if (loading && numbering == null) {
                        Text(LOADING, style = MaterialTheme.typography.bodySmall, color = SmartieColors.Steel)
                    } else if (numbering == null) {
                        Text(NOT_SEEDED, style = MaterialTheme.typography.bodySmall, color = SmartieColors.Steel)
                    }
                }
            }
        }

        if (error != null) {
            item(key = "error") {
                Text(error, style = MaterialTheme.typography.bodyMedium, color = SmartieColors.Danger)
            }
        }

        if (capabilities.canConfigureNumbering) {
            item(key = PREFIX_LABEL) {
                Field(PREFIX_LABEL, prefix, saving, error = prefixError) { prefix = it }
            }
            item(key = YEAR_LABEL) {
                Field(YEAR_LABEL, year, saving, error = yearError) { year = it }
            }
            item(key = NEXT_LABEL) {
                Field(NEXT_LABEL, next, saving, numeric = true, error = nextError) { next = it }
            }
            item(key = PAD_LABEL) {
                Field(PAD_LABEL, pad, saving, numeric = true, error = padError) { pad = it }
            }
            item(key = SAVE_NUMBERING_KEY) {
                SmartiePrimaryButton(
                    text = SAVE_NUMBERING,
                    onClick = { submit() },
                    enabled = !saving && refusal == null,
                    modifier = Modifier
                        .semantics { contentDescription = SAVE_NUMBERING }
                        .fillMaxWidth()
                )
            }
        } else {
            item(key = "numbering-facts") {
                SmartieCard {
                    Column(verticalArrangement = Arrangement.spacedBy(dimens.gapXs)) {
                        Fact(PREFIX_LABEL, stored.prefix)
                        Fact(YEAR_LABEL, stored.financialYear)
                        Fact(NEXT_LABEL, stored.next.toString())
                        Fact(PAD_LABEL, stored.pad.toString())
                    }
                }
            }
            item(key = VIEW_ONLY_KEY) {
                Text(
                    NUMBERING_VIEW_ONLY,
                    style = MaterialTheme.typography.bodySmall,
                    color = SmartieColors.Steel
                )
            }
        }

        item(key = CAP_SECTION) { SectionHeader(CAP_SECTION) }

        item(key = "cap-describes") {
            Text(
                DiscountCap.describe(cap),
                style = MaterialTheme.typography.bodyMedium,
                color = SmartieColors.Ink
            )
        }

        if (capabilities.canSetDiscountCap) {
            item(key = CAP_LABEL) {
                Field(
                    CAP_LABEL, capTyped, saving, numeric = true,
                    error = capRefusal.takeIf { capTyped.isNotBlank() }
                ) { capTyped = it }
            }
            item(key = SAVE_CAP_KEY) {
                SmartiePrimaryButton(
                    text = SAVE_CAP,
                    onClick = { capValue?.let(actions.onSaveDiscountCap) },
                    enabled = !saving && capRefusal == null,
                    modifier = Modifier
                        .semantics { contentDescription = SAVE_CAP }
                        .fillMaxWidth()
                )
            }
        } else {
            item(key = CAP_VIEW_ONLY_KEY) {
                Text(
                    CAP_VIEW_ONLY,
                    style = MaterialTheme.typography.bodySmall,
                    color = SmartieColors.Steel
                )
            }
        }

        item(key = LATER_KEY) {
            Text(
                LATER_NOTE,
                style = MaterialTheme.typography.bodySmall,
                color = SmartieColors.Steel
            )
        }
    }

    if (confirming && consequence != null) {
        ConfirmDialog(
            title = consequence.headline,
            message = consequence.detail,
            confirmText = CONFIRM,
            danger = true,
            onConfirm = {
                confirming = false
                actions.onSaveNumbering(draft)
            },
            onDismiss = { confirming = false }
        )
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    saving: Boolean,
    numeric: Boolean = false,
    error: String? = null,
    onChange: (String) -> Unit
) {
    SmartieField(
        label = label,
        value = value,
        onValueChange = onChange,
        enabled = !saving,
        isError = error != null,
        supportingText = error,
        keyboardOptions = if (numeric) {
            KeyboardOptions(keyboardType = KeyboardType.Number)
        } else {
            KeyboardOptions.Default
        },
        modifier = Modifier.semantics { contentDescription = label }
    )
}

/** A labelled fact, which says so when it was never recorded. */
@Composable
private fun Fact(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = SmartieColors.Steel)
        Text(
            value.ifBlank { NOT_SET },
            style = MaterialTheme.typography.bodyMedium,
            color = if (value.isBlank()) SmartieColors.Steel else SmartieColors.Ink
        )
    }
}

/** `10.0` reads as `10`; `12.5` keeps its half. */
private fun trimZero(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

/**
 * The settings list, and the keys a test scrolls to. The discount limit sits
 * below four numbering fields and a Save button, so on any real phone it
 * starts below the fold — and a `LazyColumn` does not merely hide an
 * off-screen item, it never composes it.
 *
 * Each field's list key **is** its label, so a test scrolls to a box by the
 * same name it types into and the two cannot drift apart.
 */
internal const val SETTINGS_LIST_TAG = "settings-list"
internal const val PREVIEW_KEY = "preview"
internal const val SAVE_NUMBERING_KEY = "save-numbering"
internal const val SAVE_CAP_KEY = "save-cap"
internal const val VIEW_ONLY_KEY = "numbering-view-only"
internal const val CAP_VIEW_ONLY_KEY = "cap-view-only"
internal const val LATER_KEY = "later"

internal const val NUMBERING_SECTION = "Quotation numbering"
internal const val CAP_SECTION = "Manager discount limit"
internal const val PREVIEW_LABEL = "Next quotation number"
internal const val PREFIX_LABEL = "Prefix"
internal const val YEAR_LABEL = "Financial year"
internal const val NEXT_LABEL = "Next number"
internal const val PAD_LABEL = "Digits in the number"
internal const val CAP_LABEL = "Limit (%)"
internal const val SAVE_NUMBERING = "Save numbering"
internal const val SAVE_CAP = "Save discount limit"
internal const val CONFIRM = "Save"
internal const val NOT_SET = "Not set"
internal const val LOADING = "Reading the counter…"
internal const val NOT_SEEDED =
    "The counter has not been set up yet. Fill this in before the first quotation is issued."
internal const val NUMBERING_VIEW_ONLY =
    "Only the Owner can change the numbering. This is what the next quotation will be numbered."
internal const val CAP_VIEW_ONLY =
    "Only the Owner can change the discount limit."
internal const val LATER_NOTE =
    "Company details, bank details, terms and device preferences are not here yet — this screen " +
        "holds the quotation numbering and the discount limit."
