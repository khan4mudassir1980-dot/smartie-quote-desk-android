package `in`.smartie.quotedesk.ui.products

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.PaddingValues
import `in`.smartie.quotedesk.data.mapping.Money
import `in`.smartie.quotedesk.data.model.ProductRecord
import `in`.smartie.quotedesk.domain.ProductDraft
import `in`.smartie.quotedesk.domain.ProductUnit
import `in`.smartie.quotedesk.domain.ProductWrite
import `in`.smartie.quotedesk.ui.components.SmartieField
import `in`.smartie.quotedesk.ui.components.SmartieGhostButton
import `in`.smartie.quotedesk.ui.components.SmartiePrimaryButton
import `in`.smartie.quotedesk.ui.theme.LocalSmartieDimens
import `in`.smartie.quotedesk.ui.theme.SmartieColors

const val PRODUCT_EDITOR_TAG = "product-editor"

/** The catalogue card's control that opens this sheet. */
const val EDIT_PRODUCT = "Edit"

/** Distinct per row, so a test names the card it means. */
fun editLabel(model: String): String = "Edit $model"

const val PRODUCT_NAME_LABEL = "Product name"
const val UNIT_LABEL = "Unit"
const val DEALER_LABEL = "Dealer rate"
const val CLIENT_LABEL = "Client rate"
const val MIN_SQFT_LABEL = "Minimum chargeable sq ft"
const val GST_LABEL = "GST %"

const val IDENTITY_KEY = "identity"
const val CONTRACTOR_KEY = "contractor"
const val AREA_KEY = "area-chip"
const val ACTIVE_KEY = "active"
const val SAVE_KEY = "save"
const val VIEW_ONLY_KEY = "view-only"
const val FAILURE_KEY = "failure"

const val SAVE_PRODUCT = "Save product"
const val CANCEL_EDIT = "Cancel"
const val PRICE_THIS_PER_SQ_FT = "Price this per square foot"
const val ACTIVE_LABEL = "Offered on new quotations"
const val CONTRACTOR_LABEL = "Contractor rate"
const val PRICE_NOT_SET = "Price not set"
const val VIEW_ONLY =
    "Only an Owner or Administrator can change a product. These are the stored details."
const val UNIT_HELP =
    "Type it exactly as the price book does — each, per m, per pc, per sq ft. " +
        "Area pricing turns on for \"per sq ft\"."
const val CONTRACTOR_HELP =
    "Kept as it is stored. New quotations offer Dealer and Client only, and the ones " +
        "already issued at this rate keep it."
const val MIN_SQFT_HELP = "Leave blank for no minimum. Applies per door."

/** Everything the editor can do, so the sheet itself stays stateless. */
data class ProductEditorActions(
    val onSave: (ProductRecord, ProductDraft) -> Unit = { _, _ -> },
    val onCancel: () -> Unit = {}
)

/**
 * Correcting one product: its name, its unit, the two offered rates, the
 * minimum chargeable area and GST.
 *
 * **The unit is a free text box, and that is the whole point of it.** V8C4's
 * own `#fU` is a text input, and the price book uses `per m`, `per pc`,
 * `per kg`, `per rft`, `per ft` and `per rm` besides `per sq ft` — far more
 * products carry one of those than carry the area unit. Because the save
 * writes the complete document, a toggle that meant "off → each" would
 * convert every one of them the first time somebody corrected a rate. The
 * chip below can only ever *set* the box to `per sq ft`; nothing here clears
 * or overwrites a unit the person did not type.
 *
 * **The contractor rate is shown and never edited.** It is no longer offered
 * on a new quotation, but quotations already issued at it must keep it, so it
 * is displayed as a fact — including "Price not set" for a stored null, which
 * V8C4 writes deliberately — and carried through the save untouched.
 *
 * **Everything is in a `LazyColumn`, so a test must scroll before it looks.**
 * An off-screen item is not merely hidden, it is never composed, and with
 * seven fields above the Save button most of this sheet starts below the fold.
 * Each item's key is its label, so one name does both jobs.
 */
@Composable
fun ProductEditorSheet(
    record: ProductRecord,
    canEdit: Boolean,
    saving: Boolean = false,
    failure: String? = null,
    actions: ProductEditorActions = ProductEditorActions()
) {
    val dimens = LocalSmartieDimens.current
    // Keyed on the document, so opening a different product starts a fresh
    // sheet rather than carrying the last one's half-typed values across.
    var draft by remember(record.documentId) { mutableStateOf(ProductWrite.draftOf(record)) }
    var attempted by remember(record.documentId) { mutableStateOf(false) }

    val nameError = ProductWrite.NAME_REQUIRED.takeIf { attempted && draft.name.isBlank() }
    val dealerError = ProductWrite.PRICE_NOT_A_NUMBER
        .takeIf { attempted && !ProductWrite.priceIsSayable(draft.dealer) }
    val clientError = ProductWrite.PRICE_NOT_A_NUMBER
        .takeIf { attempted && !ProductWrite.priceIsSayable(draft.client) }
    val gstError = if (!attempted) null else gstRefusal(draft.gst)
    val minSqftError = if (!attempted) null else minSqftRefusal(draft.minSqft)

    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag(PRODUCT_EDITOR_TAG),
        contentPadding = PaddingValues(dimens.gapM),
        verticalArrangement = Arrangement.spacedBy(dimens.gapS)
    ) {
        item(key = IDENTITY_KEY) {
            Column {
                Text(
                    record.model,
                    style = MaterialTheme.typography.titleMedium,
                    color = SmartieColors.Ink
                )
                // The group and key are identity, never editable: they decide
                // which document the save lands on, and which document the
                // PWA reads.
                Text(
                    record.key,
                    style = MaterialTheme.typography.bodySmall,
                    color = SmartieColors.Steel
                )
            }
        }

        if (failure != null) {
            item(key = FAILURE_KEY) {
                Text(failure, style = MaterialTheme.typography.bodyMedium, color = SmartieColors.Danger)
            }
        }

        if (!canEdit) {
            item(key = VIEW_ONLY_KEY) {
                Text(VIEW_ONLY, style = MaterialTheme.typography.bodyMedium, color = SmartieColors.Steel)
            }
        }

        item(key = PRODUCT_NAME_LABEL) {
            Field(PRODUCT_NAME_LABEL, draft.name, canEdit && !saving, error = nameError) {
                draft = draft.copy(name = it)
            }
        }

        item(key = UNIT_LABEL) {
            Field(UNIT_LABEL, draft.unit, canEdit && !saving, help = UNIT_HELP) {
                draft = draft.copy(unit = it)
            }
        }

        // Offered only while the box does not already say it, so the chip can
        // set the unit and can never clear or replace one.
        if (canEdit && !ProductUnit.isArea(draft.unit)) {
            item(key = AREA_KEY) {
                SmartieGhostButton(
                    text = PRICE_THIS_PER_SQ_FT,
                    onClick = { draft = draft.copy(unit = ProductUnit.AREA) },
                    enabled = !saving,
                    modifier = Modifier.semantics { contentDescription = PRICE_THIS_PER_SQ_FT }
                )
            }
        }

        if (ProductUnit.isArea(draft.unit)) {
            item(key = MIN_SQFT_LABEL) {
                Field(
                    MIN_SQFT_LABEL, draft.minSqft, canEdit && !saving,
                    numeric = true, error = minSqftError, help = MIN_SQFT_HELP
                ) { draft = draft.copy(minSqft = it) }
            }
        }

        item(key = DEALER_LABEL) {
            Field(DEALER_LABEL, draft.dealer, canEdit && !saving, numeric = true, error = dealerError) {
                draft = draft.copy(dealer = it)
            }
        }

        item(key = CLIENT_LABEL) {
            Field(CLIENT_LABEL, draft.client, canEdit && !saving, numeric = true, error = clientError) {
                draft = draft.copy(client = it)
            }
        }

        item(key = CONTRACTOR_KEY) {
            Fact(
                CONTRACTOR_LABEL,
                record.contractor?.let { Money.formatRupees(it, decimals = 0) } ?: PRICE_NOT_SET,
                CONTRACTOR_HELP
            )
        }

        item(key = GST_LABEL) {
            Field(GST_LABEL, draft.gst, canEdit && !saving, numeric = true, error = gstError) {
                draft = draft.copy(gst = it)
            }
        }

        item(key = ACTIVE_KEY) {
            Row(
                Modifier.fillMaxWidth().semantics { contentDescription = ACTIVE_LABEL },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(ACTIVE_LABEL, style = MaterialTheme.typography.bodyMedium, color = SmartieColors.Ink)
                Switch(
                    checked = draft.active,
                    onCheckedChange = { draft = draft.copy(active = it) },
                    enabled = canEdit && !saving
                )
            }
        }

        if (canEdit) {
            item(key = SAVE_KEY) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(dimens.gapS)
                ) {
                    SmartieGhostButton(
                        text = CANCEL_EDIT,
                        onClick = actions.onCancel,
                        enabled = !saving,
                        modifier = Modifier.semantics { contentDescription = CANCEL_EDIT }
                    )
                    SmartiePrimaryButton(
                        text = SAVE_PRODUCT,
                        busy = saving,
                        onClick = {
                            attempted = true
                            if (draft.refusal() == null) actions.onSave(record, draft)
                        },
                        modifier = Modifier.semantics { contentDescription = SAVE_PRODUCT }
                    )
                }
            }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    enabled: Boolean,
    numeric: Boolean = false,
    error: String? = null,
    help: String? = null,
    onChange: (String) -> Unit
) {
    SmartieField(
        label = label,
        value = value,
        onValueChange = onChange,
        enabled = enabled,
        isError = error != null,
        supportingText = error ?: help,
        keyboardOptions = if (numeric) {
            KeyboardOptions(keyboardType = KeyboardType.Number)
        } else {
            KeyboardOptions.Default
        },
        modifier = Modifier.semantics { contentDescription = label }
    )
}

/** A stored value that is shown but never edited. */
@Composable
private fun Fact(label: String, value: String, help: String) {
    Column(Modifier.semantics { contentDescription = label }) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = SmartieColors.Steel)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = SmartieColors.Ink)
        Text(help, style = MaterialTheme.typography.bodySmall, color = SmartieColors.Steel)
    }
}

private fun gstRefusal(typed: String): String? = when {
    typed.isBlank() -> ProductWrite.GST_REQUIRED
    typed.trim().toDoubleOrNull() == null -> ProductWrite.GST_NOT_A_NUMBER
    typed.trim().toDouble() !in 0.0..ProductWrite.MAX_GST -> ProductWrite.GST_OUT_OF_RANGE
    else -> null
}

private fun minSqftRefusal(typed: String): String? = when {
    typed.isBlank() -> null
    typed.trim().toDoubleOrNull() == null -> ProductWrite.MIN_SQFT_NOT_A_NUMBER
    typed.trim().toDouble() < 0 -> ProductWrite.MIN_SQFT_NEGATIVE
    else -> null
}
