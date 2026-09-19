package `in`.smartie.quotedesk.util

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import `in`.smartie.quotedesk.domain.StockPhoto
import `in`.smartie.quotedesk.domain.StockPhotoImage
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The bitmap edge: the only part of a stock photo that needs Android.
 *
 * Every decision it makes is [StockPhoto]'s — how far to sample down, what
 * size to finish at, what an EXIF orientation means, which qualities to try
 * and whether the result may be stored. This file does the work and none of
 * the deciding, which is what keeps the rules of the feature unit-tested off
 * device.
 */
object StockImage {

    private const val CAPTURES = "stock-photos"

    /**
     * Somewhere for the camera app to write, as a `content://` Uri it is
     * allowed to write to.
     *
     * The capture is a temporary: it is compressed into a few tens of
     * kilobytes and the original is deleted. [clearCaptures] is how.
     */
    fun captureTarget(context: Context): Uri {
        val directory = File(context.cacheDir, CAPTURES).apply { mkdirs() }
        val file = File(directory, "capture-${System.currentTimeMillis()}.jpg")
        return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

    /** Throws away every capture. Safe to call whenever a sheet closes. */
    fun clearCaptures(context: Context) {
        File(context.cacheDir, CAPTURES).listFiles()?.forEach { it.delete() }
    }

    /**
     * Read [uri], stand it upright, scale it down and compress it until it
     * fits — or return null when it cannot be read or cannot be made to fit.
     *
     * Null is not an error to swallow: the caller turns it into one of
     * [StockPhoto]'s two refusals, so the person is told which happened.
     */
    fun prepare(resolver: ContentResolver, uri: Uri): StockPhotoImage? {
        val bounds = readBounds(resolver, uri) ?: return null
        val decoded = decode(resolver, uri, bounds) ?: return null
        val upright = orient(decoded, orientationOf(resolver, uri))
        val scaled = scale(upright)
        return try {
            encode(scaled)
        } finally {
            // createScaledBitmap and createBitmap may each hand back the same
            // instance, so only a genuinely new one is recycled.
            if (scaled !== decoded) scaled.recycle()
            if (upright !== decoded && upright !== scaled) upright.recycle()
            decoded.recycle()
        }
    }

    /** A preview, decoded straight from what will be stored. */
    fun decodePreview(bytes: ByteArray): Bitmap? =
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()

    // --- the steps ---------------------------------------------------------

    private fun readBounds(resolver: ContentResolver, uri: Uri): BitmapFactory.Options? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val read = runCatching {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        }
        if (read.isFailure) return null
        return options.takeIf { it.outWidth > 0 && it.outHeight > 0 }
    }

    private fun decode(
        resolver: ContentResolver,
        uri: Uri,
        bounds: BitmapFactory.Options
    ): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inSampleSize = StockPhoto.sampleSize(bounds.outWidth, bounds.outHeight)
        }
        return runCatching {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        }.getOrNull()
    }

    private fun orientationOf(resolver: ContentResolver, uri: Uri): Int = runCatching {
        resolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        }
    }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

    private fun orient(bitmap: Bitmap, orientation: Int): Bitmap {
        val rotation = StockPhoto.rotationFor(orientation)
        val mirror = StockPhoto.mirrorFor(orientation)
        if (rotation == 0 && !mirror) return bitmap
        val matrix = Matrix().apply {
            if (rotation != 0) postRotate(rotation.toFloat())
            if (mirror) postScale(-1f, 1f)
        }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrDefault(bitmap)
    }

    private fun scale(bitmap: Bitmap): Bitmap {
        val (width, height) = StockPhoto.scaledSize(bitmap.width, bitmap.height)
        if (width == bitmap.width && height == bitmap.height) return bitmap
        return runCatching {
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        }.getOrDefault(bitmap)
    }

    /**
     * Quality floats; the ceiling does not. The first quality that fits wins,
     * and if the bottom of the ladder still will not fit, nothing is stored.
     */
    private fun encode(bitmap: Bitmap): StockPhotoImage? {
        for (quality in StockPhoto.QUALITY_LADDER) {
            val stream = ByteArrayOutputStream()
            if (!bitmap.compress(webp(), quality, stream)) return null
            val bytes = stream.toByteArray()
            if (StockPhoto.fits(bytes.size)) {
                return StockPhotoImage(bytes, bitmap.width, bitmap.height)
            }
        }
        return null
    }

    /** `WEBP` is deprecated from API 30, where `WEBP_LOSSY` replaces it. */
    @Suppress("DEPRECATION")
    private fun webp(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY
        else Bitmap.CompressFormat.WEBP
}
