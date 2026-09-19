package `in`.smartie.quotedesk.ui.stock

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
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

/**
 * The picture on a stock card.
 *
 * Deliberately small and deliberately dumb: it draws what it is handed and
 * decides nothing. [image] null means there is nothing to draw **yet or at
 * all** — still loading, or a load that failed — and both show the same quiet
 * placeholder rather than a spinner that might never stop. A row with no
 * photo does not render this at all, so an empty slot never appears where
 * there was never a picture.
 */
@Composable
internal fun StockPhotoThumbnail(
    image: ImageBitmap?,
    name: String,
    onOpen: () -> Unit = {}
) {
    val dimens = LocalSmartieDimens.current
    Box(
        Modifier
            .size(THUMBNAIL)
            .clip(RoundedCornerShape(dimens.radius))
            .background(SmartieColors.Panel2)
            .clickable(onClick = onOpen)
            .semantics {
                contentDescription = if (image != null) photoLabel(name) else photoMissing(name)
            },
        contentAlignment = Alignment.Center
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                "—",
                style = MaterialTheme.typography.bodyLarge,
                color = SmartieColors.Steel2
            )
        }
    }
}

/**
 * The photo at a size worth looking at.
 *
 * Everyone who can see the row can see this. **Replace** and **Remove** are
 * only rendered for someone who may change it — a Worker gets no disabled
 * button, just no button, which is the rule the rest of the board already
 * follows. Offline they are rendered and disabled, carrying the standing
 * wording, because being offline is temporary and being a Worker is not.
 */
@Composable
internal fun StockPhotoViewPanel(
    image: ImageBitmap?,
    name: String,
    canManage: Boolean = false,
    online: Boolean = true,
    saving: Boolean = false,
    actions: StockPhotoActions = StockPhotoActions()
) {
    val dimens = LocalSmartieDimens.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 300.dp)
                .aspectRatio(4f / 3f)
                .clip(RoundedCornerShape(dimens.radius))
                .background(SmartieColors.Panel2)
                .semantics {
                    contentDescription = if (image != null) photoLabel(name) else photoMissing(name)
                },
            contentAlignment = Alignment.Center
        ) {
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Text(
                    PHOTO_UNAVAILABLE,
                    style = MaterialTheme.typography.bodyLarge,
                    color = SmartieColors.Steel
                )
            }
        }

        if (canManage && !online) OfflineLine()

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (canManage) {
                SmartieGhostButton(
                    text = REPLACE_PHOTO,
                    onClick = actions.onRetake,
                    enabled = online && !saving,
                    modifier = Modifier.semantics {
                        contentDescription = if (online) REPLACE_PHOTO else PHOTO_OFFLINE
                    }
                )
                SmartieGhostButton(
                    text = REMOVE_PHOTO,
                    onClick = actions.onRemove,
                    enabled = online && !saving,
                    danger = true,
                    modifier = Modifier.semantics {
                        contentDescription = when {
                            !online -> PHOTO_OFFLINE
                            saving -> SAVING_PHOTO
                            else -> REMOVE_PHOTO
                        }
                    }
                )
            }
            SmartieGhostButton(text = "Close", onClick = actions.onCancel)
        }
    }
}

/** How a photo is named to a screen reader, and when there is not one. */
internal fun photoLabel(name: String): String = "Photo of $name"

internal fun photoMissing(name: String): String = "No photo showing for $name"

internal val THUMBNAIL = 56.dp

internal const val PHOTO_BUTTON: String = "Photo"
internal const val REPLACE_PHOTO: String = "Replace photo"
internal const val SAVING_PHOTO: String = "Saving the photo"
internal const val PHOTO_UNAVAILABLE: String = "Picture not available"
