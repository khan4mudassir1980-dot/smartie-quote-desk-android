package `in`.smartie.quotedesk.ui.stock

import `in`.smartie.quotedesk.data.model.StockRecord
import `in`.smartie.quotedesk.domain.StockPhoto
import `in`.smartie.quotedesk.domain.StockPhotoImage

/** Which photo sheet is open, if any. */
enum class PhotoStage {
    /** No sheet. */
    CLOSED,

    /** "Where is the picture coming from?" */
    SOURCE,

    /** What was taken, before anything is written. */
    PREVIEW,

    /** The stored photo, at a size worth looking at. */
    VIEW
}

/**
 * The whole photo flow as one value.
 *
 * It is a plain data class rather than a handful of flags because the stages
 * are mutually exclusive and the bugs in the first staging pass were all of
 * the two-flags-disagreeing kind. [StockPhotoFlow] is the only thing that
 * moves between stages, and it is pure, so every transition below is decided
 * off device and tested there.
 *
 * Nothing here writes. A [prepared] image is bytes sitting in memory that the
 * person has not confirmed yet; only [PhotoStage.PREVIEW] can lead to a write
 * and only through the screen's confirm.
 */
data class StockPhotoUi(
    val record: StockRecord? = null,
    val stage: PhotoStage = PhotoStage.CLOSED,
    /** Compressed bytes awaiting confirmation. Never written on its own. */
    val prepared: StockPhotoImage? = null,
    /** Why the picture cannot be stored, in a sentence. */
    val refusal: String? = null,
    /** A write is in flight; the sheet stays open and its controls do not. */
    val saving: Boolean = false
) {
    val isOpen: Boolean get() = stage != PhotoStage.CLOSED && record != null

    /** Waiting for the camera or the picker to come back. */
    val preparing: Boolean get() = stage == PhotoStage.PREVIEW && prepared == null && refusal == null
}

/**
 * Every move the photo flow can make, as pure functions.
 *
 * Kept out of the view model so the sequence that matters — open, choose,
 * prepare, confirm — can be asserted without Android, a camera or Firestore.
 */
object StockPhotoFlow {

    /**
     * Opening a row's photo.
     *
     * Someone who may change photos always gets somewhere useful: the picture
     * if there is one, the source sheet if there is not. Someone who may only
     * look gets the picture, and nothing at all when there is none — no empty
     * sheet offering options they do not have.
     */
    fun open(record: StockRecord, canManage: Boolean): StockPhotoUi = when {
        record.hasPhoto -> StockPhotoUi(record = record, stage = PhotoStage.VIEW)
        canManage -> StockPhotoUi(record = record, stage = PhotoStage.SOURCE)
        else -> StockPhotoUi()
    }

    /** Replace: back to the source sheet, keeping the row. */
    fun replace(current: StockPhotoUi): StockPhotoUi =
        if (current.record == null) current
        else StockPhotoUi(record = current.record, stage = PhotoStage.SOURCE)

    /**
     * The camera or the picker has been launched.
     *
     * The preview opens **empty** rather than after the bytes arrive, so the
     * person sees the sheet they are waiting on instead of a board that looks
     * as if nothing happened.
     */
    fun awaiting(current: StockPhotoUi): StockPhotoUi =
        if (current.record == null) current
        else current.copy(stage = PhotoStage.PREVIEW, prepared = null, refusal = null)

    /**
     * What came back.
     *
     * A null image means the file could not be read or could not be made
     * small enough — [StockPhoto.refusal] says which, in words, and the
     * preview shows it instead of a confirm button.
     */
    fun prepared(current: StockPhotoUi, image: StockPhotoImage?): StockPhotoUi = when {
        current.record == null -> current
        image == null -> current.copy(
            stage = PhotoStage.PREVIEW,
            prepared = null,
            refusal = StockPhoto.WILL_NOT_FIT
        )
        else -> {
            val refusal = StockPhoto.refusal(image.bytes)
            current.copy(
                stage = PhotoStage.PREVIEW,
                prepared = image.takeIf { refusal == null },
                refusal = refusal
            )
        }
    }

    /** The person backed out of the camera or the picker without choosing. */
    fun abandoned(current: StockPhotoUi): StockPhotoUi = when {
        current.record == null -> StockPhotoUi()
        // Back to wherever they came from, rather than out of the flow.
        current.record.hasPhoto -> StockPhotoUi(current.record, PhotoStage.VIEW)
        else -> StockPhotoUi(current.record, PhotoStage.SOURCE)
    }

    fun saving(current: StockPhotoUi): StockPhotoUi = current.copy(saving = true)

    /** A write that failed leaves the sheet exactly as it was, minus the spinner. */
    fun failed(current: StockPhotoUi): StockPhotoUi = current.copy(saving = false)

    fun closed(): StockPhotoUi = StockPhotoUi()
}
