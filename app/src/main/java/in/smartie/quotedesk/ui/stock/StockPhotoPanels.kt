package `in`.smartie.quotedesk.ui.stock

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import `in`.smartie.quotedesk.domain.StockPhotoImage
import `in`.smartie.quotedesk.ui.components.SmartieGhostButton
import `in`.smartie.quotedesk.ui.components.SmartiePrimaryButton
import `in`.smartie.quotedesk.ui.theme.LocalSmartieDimens
import `in`.smartie.quotedesk.ui.theme.SmartieColors

/**
 * The two photo sheets, as **panels** rather than dialogs.
 *
 * A Compose `Dialog` opens its own window with its own recomposer, which the
 * Robolectric test clock does not drive, so a test that opens one spins until
 * Espresso gives up. Every dialog body in this app is an internal panel the
 * tests drive directly; the wrapper holds no logic. See
 * `docs/PROJECT-STATUS.md`.
 */

/** Everything the photo sheets can do. */
data class StockPhotoActions(
    val onTakePhoto: () -> Unit = {},
    val onChooseFromGallery: () -> Unit = {},
    val onConfirm: () -> Unit = {},
    val onRetake: () -> Unit = {},
    val onRemove: () -> Unit = {},
    val onCancel: () -> Unit = {}
)

/**
 * Where the picture comes from. Two options and nothing else, the same shape
 * Add stock uses, because the first staging pass proved a single crowded sheet
 * is what people get wrong.
 */
@Composable
internal fun StockPhotoSourcePanel(
    online: Boolean,
    canRemove: Boolean = false,
    actions: StockPhotoActions = StockPhotoActions()
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Where is the picture coming from?",
            style = MaterialTheme.typography.titleSmall,
            color = SmartieColors.Ink
        )
        SmartiePrimaryButton(
            text = TAKE_PHOTO,
            onClick = actions.onTakePhoto,
            enabled = online,
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = if (online) TAKE_PHOTO else PHOTO_OFFLINE
            }
        )
        SmartieGhostButton(
            text = CHOOSE_FROM_GALLERY,
            onClick = actions.onChooseFromGallery,
            enabled = online,
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = if (online) CHOOSE_FROM_GALLERY else PHOTO_OFFLINE
            }
        )
        Text(
            "Nothing is saved until you confirm it on the next screen.",
            style = MaterialTheme.typography.labelMedium,
            color = SmartieColors.Steel
        )
        if (!online) OfflineLine()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (canRemove) {
                SmartieGhostButton(
                    text = REMOVE_PHOTO,
                    onClick = actions.onRemove,
                    enabled = online,
                    danger = true,
                    modifier = Modifier.semantics {
                        contentDescription = if (online) REMOVE_PHOTO else PHOTO_OFFLINE
                    }
                )
            }
            SmartieGhostButton(text = "Cancel", onClick = actions.onCancel)
        }
    }
}

/**
 * What was taken, before anything is written.
 *
 * [prepared] is the compressed image that **will be written** — it, not
 * [preview], decides whether there is anything to confirm. The two are
 * separate on purpose: the bitmap exists only to be drawn, and tying the
 * confirm button to it would mean a picture that failed to render could not
 * be saved even though its bytes were ready.
 *
 * [prepared] null with no [refusal] means it is still being prepared; a
 * [refusal] means it cannot be stored and says why. Either way **Add photo**
 * is unavailable, so nothing reaches Firestore that the person has not seen
 * and confirmed.
 */
@Composable
internal fun StockPhotoPreviewPanel(
    prepared: StockPhotoImage?,
    preview: ImageBitmap? = null,
    online: Boolean = true,
    saving: Boolean = false,
    refusal: String? = null,
    actions: StockPhotoActions = StockPhotoActions()
) {
    val dimens = LocalSmartieDimens.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 260.dp)
                .aspectRatio(4f / 3f)
                .clip(RoundedCornerShape(dimens.radius))
                .background(SmartieColors.Panel2),
            contentAlignment = Alignment.Center
        ) {
            if (preview != null) {
                Image(
                    bitmap = preview,
                    contentDescription = PREVIEW_LABEL,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Text(
                    refusal ?: PREPARING,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (refusal != null) SmartieColors.Danger else SmartieColors.Steel
                )
            }
        }

        if (refusal != null) {
            Text(
                refusal,
                style = MaterialTheme.typography.labelMedium,
                color = SmartieColors.Danger,
                modifier = Modifier.semantics { contentDescription = REFUSAL_LABEL }
            )
        } else if (prepared != null) {
            Text(
                storedSize(prepared.size),
                style = MaterialTheme.typography.labelMedium,
                color = SmartieColors.Steel2
            )
        }

        if (!online) OfflineLine()

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmartiePrimaryButton(
                text = if (saving) "Saving…" else ADD_PHOTO,
                onClick = actions.onConfirm,
                enabled = prepared != null && refusal == null && online && !saving,
                modifier = Modifier.semantics {
                    contentDescription = when {
                        !online -> PHOTO_OFFLINE
                        saving -> "Saving the photo"
                        else -> ADD_PHOTO
                    }
                }
            )
            SmartieGhostButton(text = RETAKE, onClick = actions.onRetake, enabled = !saving)
            SmartieGhostButton(text = "Cancel", onClick = actions.onCancel, enabled = !saving)
        }
    }
}

/**
 * Whole kibibytes, rounded up — the same unit the 80 KiB ceiling is in,
 * written "KB" because that is how a shop floor reads it.
 */
internal fun storedSize(bytes: Int): String = "${(bytes + 1023) / 1024} KB, stored with the item"

@Composable
private fun OfflineLine() {
    Text(
        PHOTO_OFFLINE,
        style = MaterialTheme.typography.labelMedium,
        color = SmartieColors.Warn
    )
}

/** One wording on every disabled photo control, as the stock controls do. */
const val PHOTO_OFFLINE: String = "Internet required to change a photo"

internal const val TAKE_PHOTO: String = "Take a photo"
internal const val CHOOSE_FROM_GALLERY: String = "Choose from gallery"
internal const val ADD_PHOTO: String = "Add photo"
internal const val RETAKE: String = "Choose another"
internal const val REMOVE_PHOTO: String = "Remove photo"
internal const val PREVIEW_LABEL: String = "Photo preview"
internal const val PREPARING: String = "Preparing the picture…"
internal const val REFUSAL_LABEL: String = "Why this picture cannot be saved"
