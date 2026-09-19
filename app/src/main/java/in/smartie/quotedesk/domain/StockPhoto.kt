package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.mapping.Keys

/**
 * Everything about a stock photo that can be decided without Android.
 *
 * The bitmap work itself lives at the platform edge; every *decision* it makes
 * — how far to scale, which quality to try, what an EXIF orientation means,
 * whether the result may be stored at all — is here, so it is unit-tested off
 * device. That is the split that made the rest of N3 testable.
 */
object StockPhoto {

    /** The rules enforce this exact figure; the client must not exceed it. */
    const val MAX_BYTES: Int = 81_920

    /** Neither edge may exceed this after scaling. */
    const val MAX_EDGE: Int = 800

    /**
     * Qualities tried in order until the encoded result fits [MAX_BYTES].
     *
     * Quality floats; the ceiling does not. Stopping at 35 rather than going
     * lower is deliberate: below that the picture stops being evidence of
     * anything, and refusing is more honest than storing a smear.
     */
    val QUALITY_LADDER: List<Int> = listOf(85, 75, 65, 55, 45, 35)

    const val NOT_AN_IMAGE: String = "That file could not be read as a picture"

    const val WILL_NOT_FIT: String =
        "That picture could not be made small enough — try a closer, plainer shot"

    /**
     * The photo document id, which is the **stock** document id.
     *
     * Derived from the immutable stock key, so renaming what a product is
     * called can never move a photo or orphan one.
     */
    fun documentId(key: String): String = Keys.stockDocId(key)

    /**
     * The `inSampleSize` to decode with: the largest power of two that still
     * leaves the longer edge at or above [maxEdge], so nothing is decoded at
     * full size only to be thrown away.
     */
    fun sampleSize(width: Int, height: Int, maxEdge: Int = MAX_EDGE): Int {
        if (width <= 0 || height <= 0 || maxEdge <= 0) return 1
        var sample = 1
        while (maxOf(width, height) / (sample * 2) >= maxEdge) sample *= 2
        return sample
    }

    /**
     * The final size, aspect ratio preserved and **never enlarged** — a small
     * photograph stays small rather than being blown up to fill the ceiling.
     */
    fun scaledSize(width: Int, height: Int, maxEdge: Int = MAX_EDGE): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return width to height
        val longest = maxOf(width, height)
        if (longest <= maxEdge) return width to height
        val factor = maxEdge.toDouble() / longest
        return maxOf(1, Math.round(width * factor).toInt()) to
            maxOf(1, Math.round(height * factor).toInt())
    }

    /** Degrees clockwise implied by an EXIF orientation value. */
    fun rotationFor(orientation: Int): Int = when (orientation) {
        ORIENTATION_ROTATE_180, ORIENTATION_FLIP_VERTICAL -> 180
        ORIENTATION_TRANSPOSE, ORIENTATION_ROTATE_90 -> 90
        ORIENTATION_ROTATE_270, ORIENTATION_TRANSVERSE -> 270
        else -> 0
    }

    /** Whether the same orientation also needs a horizontal flip. */
    fun mirrorFor(orientation: Int): Boolean = orientation in setOf(
        ORIENTATION_FLIP_HORIZONTAL,
        ORIENTATION_FLIP_VERTICAL,
        ORIENTATION_TRANSPOSE,
        ORIENTATION_TRANSVERSE
    )

    /** A lossy WebP begins `RIFF....WEBP`. */
    fun isWebP(bytes: ByteArray): Boolean =
        bytes.size >= 12 &&
            bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()

    fun fits(size: Int): Boolean = size in 1..MAX_BYTES

    /**
     * Why this cannot be stored, or null when it can.
     *
     * Checked before anything reaches Firestore so the refusal is a sentence
     * rather than a rejected write.
     */
    fun refusal(bytes: ByteArray?): String? = when {
        bytes == null || bytes.isEmpty() -> NOT_AN_IMAGE
        !isWebP(bytes) -> NOT_AN_IMAGE
        !fits(bytes.size) -> WILL_NOT_FIT
        else -> null
    }

    /**
     * What to do about one row's photo, given what the cache holds.
     *
     * This is the whole of the quota argument in four lines: a matching
     * revision is served from the cache and **spends no read**, and a row with
     * no photo spends none either. Only a revision the device has not seen
     * costs anything.
     *
     * A cached revision *higher* than the row's is treated as current: the
     * revision is monotonic, so that can only be this device's own write
     * arriving before the listener caught up.
     */
    fun action(hasPhoto: Boolean, cachedRev: Double?, rowRev: Double): PhotoAction = when {
        !hasPhoto -> PhotoAction.NONE
        cachedRev == null -> PhotoAction.FETCH
        cachedRev >= rowRev -> PhotoAction.USE_CACHE
        else -> PhotoAction.FETCH
    }

    // EXIF orientation constants, repeated so the pure layer needs no Android.
    private const val ORIENTATION_FLIP_HORIZONTAL = 2
    private const val ORIENTATION_ROTATE_180 = 3
    private const val ORIENTATION_FLIP_VERTICAL = 4
    private const val ORIENTATION_TRANSPOSE = 5
    private const val ORIENTATION_ROTATE_90 = 6
    private const val ORIENTATION_TRANSVERSE = 7
    private const val ORIENTATION_ROTATE_270 = 8
}

/** What the screen should do about a row's photo. */
enum class PhotoAction { USE_CACHE, FETCH, NONE }

/** An encoded photo, ready to be written. */
class StockPhotoImage(val bytes: ByteArray, val width: Int, val height: Int) {
    val size: Int get() = bytes.size

    override fun equals(other: Any?): Boolean = this === other ||
        (other is StockPhotoImage && width == other.width && height == other.height &&
            bytes.contentEquals(other.bytes))

    override fun hashCode(): Int =
        (bytes.contentHashCode() * 31 + width) * 31 + height

    override fun toString(): String = "StockPhotoImage(${width}x$height, ${bytes.size} bytes)"
}

/** What a photo change comes to, decided before anything reaches Firestore. */
sealed interface StockPhotoPlan {
    /**
     * Both documents, written in one transaction. [photo] is null when the
     * photo document is to be **deleted** — a removal is a write to the stock
     * row and a delete of the photo, never one without the other.
     */
    data class Write(
        val stockDocId: String,
        val stock: Map<String, Any?>,
        val photoDocId: String,
        val photo: Map<String, Any?>?,
        val rev: Double
    ) : StockPhotoPlan

    data object NoChange : StockPhotoPlan

    data class Refused(val message: String) : StockPhotoPlan
}
