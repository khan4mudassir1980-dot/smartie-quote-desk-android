package `in`.smartie.quotedesk.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.util.Base64
import androidx.core.content.res.ResourcesCompat
import `in`.smartie.quotedesk.R
import `in`.smartie.quotedesk.domain.DrawOp
import `in`.smartie.quotedesk.domain.ImageRole
import `in`.smartie.quotedesk.domain.LaidOutPage
import `in`.smartie.quotedesk.domain.PdfImages
import `in`.smartie.quotedesk.domain.QuotationDocument
import `in`.smartie.quotedesk.domain.QuotationLayout
import `in`.smartie.quotedesk.domain.StockPhoto
import `in`.smartie.quotedesk.domain.TextMeasurer
import `in`.smartie.quotedesk.domain.Tone
import java.io.Closeable
import java.io.OutputStream

/**
 * **The one renderer** (N5.11 commit 7): a [QuotationDocument] → an A4 PDF,
 * by the platform's `PdfDocument` and Inter, which has "₹" and the dashes.
 * Download, Print and the WhatsApp share all use it.
 *
 * It decides nothing. The words are [QuotationDocument]'s, every position
 * and line break [QuotationLayout]'s, and what may be decoded [PdfImages]';
 * this measures the text with the real font, decodes the images, and draws
 * each mark where it is told.
 */
class QuotationPdf(private val context: Context) {

    /** What was written: the page count, and the stored images left out because they could not be read. */
    data class Written(val pages: Int, val unreadable: List<ImageRole>)

    /** Writes [document] as a PDF to [out]. */
    fun write(document: QuotationDocument, out: OutputStream): Written = prepare(document).use { prepared ->
        val pdf = PdfDocument()
        try {
            for (page in prepared.pages) {
                val info = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, page.number).create()
                val sheet = pdf.startPage(info)
                prepared.draw(page, sheet.canvas)
                pdf.finishPage(sheet)
            }
            pdf.writeTo(out)
        } finally {
            pdf.close()
        }
        Written(prepared.pages.size, prepared.unreadable)
    }

    /**
     * [document] laid out with this renderer's own measure and images, ready
     * to draw onto any canvas — a PDF page here, a bitmap in the tests.
     * Closing it releases the decoded images.
     */
    fun prepare(document: QuotationDocument): Prepared {
        val sources = PdfImages.sources(document)
        val bitmaps = sources.mapNotNull { (role, url) -> decode(url, PdfImages.edge(role))?.let { role to it } }.toMap()
        val ink = Ink(
            regular = font(R.font.inter_regular, Typeface.DEFAULT),
            bold = font(R.font.inter_bold, Typeface.DEFAULT_BOLD),
            bitmaps = bitmaps
        )
        val aspects = bitmaps.mapValues { (_, bitmap) -> bitmap.width.toFloat() / bitmap.height.toFloat() }
        val pages = try {
            QuotationLayout.lay(document, TextMeasurer { text, size, bold -> ink.width(text, size, bold) }, aspects)
        } catch (e: Throwable) {
            ink.recycle()
            throw e
        }
        return Prepared(pages, ImageRole.entries.filter { it in sources && it !in bitmaps }, ink)
    }

    class Prepared internal constructor(
        val pages: List<LaidOutPage>,
        val unreadable: List<ImageRole>,
        private val ink: Ink
    ) : Closeable {
        fun draw(page: LaidOutPage, canvas: Canvas) = ink.draw(page, canvas)
        override fun close() = ink.recycle()
    }

    private fun font(id: Int, fallback: Typeface): Typeface =
        runCatching { ResourcesCompat.getFont(context, id) }.getOrNull() ?: fallback

    /**
     * A data URL to a bitmap no longer than [edge] on its longer side, or
     * null: not a data URL, too large, not base64, not an image. Bounds
     * first, then sampled down, then scaled — never a full-size decode.
     */
    private fun decode(url: String, edge: Int): Bitmap? {
        val payload = PdfImages.payload(url) ?: return null
        val bytes = runCatching { Base64.decode(payload, Base64.DEFAULT) }.getOrNull() ?: return null
        if (bytes.isEmpty() || bytes.size > PdfImages.MAX_BYTES) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = StockPhoto.sampleSize(bounds.outWidth, bounds.outHeight, edge)
        }
        val decoded = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }.getOrNull()
            ?: return null
        val (width, height) = StockPhoto.scaledSize(decoded.width, decoded.height, edge)
        if (width == decoded.width && height == decoded.height) return decoded
        val scaled = runCatching { Bitmap.createScaledBitmap(decoded, width, height, true) }.getOrNull()
            ?: return decoded
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    /** The fonts, the images and the colours, and the drawing of each mark. */
    class Ink internal constructor(
        private val regular: Typeface,
        private val bold: Typeface,
        private val bitmaps: Map<ImageRole, Bitmap>
    ) {
        private val measuring = Paint(Paint.ANTI_ALIAS_FLAG)

        fun width(text: String, size: Float, bold: Boolean): Float {
            measuring.typeface = if (bold) this.bold else regular
            measuring.textSize = size
            return measuring.measureText(text)
        }

        fun draw(page: LaidOutPage, canvas: Canvas) {
            canvas.drawColor(Color.WHITE)
            for (op in page.ops) {
                when (op) {
                    is DrawOp.Text -> canvas.drawText(op.text, op.x, op.y, text(op.size, op.bold, op.tone))
                    is DrawOp.Rule -> canvas.drawLine(op.x1, op.y1, op.x2, op.y2, line(op.width, op.tone))
                    is DrawOp.Fill -> canvas.drawRect(op.x, op.y, op.x + op.w, op.y + op.h, fill(op.tone))
                    is DrawOp.Image -> bitmaps[op.role]?.let { bitmap ->
                        canvas.drawBitmap(bitmap, null, RectF(op.x, op.y, op.x + op.w, op.y + op.h), images)
                    }
                    is DrawOp.Stamp -> {
                        val paint = text(op.size, true, Tone.ALERT_FAINT).apply { textAlign = Paint.Align.CENTER }
                        canvas.save()
                        canvas.rotate(op.degrees, op.cx, op.cy)
                        // Centred on the point: the baseline a third of the size below it.
                        canvas.drawText(op.text, op.cx, op.cy + op.size / 3, paint)
                        canvas.restore()
                    }
                }
            }
        }

        fun recycle() = bitmaps.values.forEach { it.recycle() }

        private val images = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        private fun text(size: Float, bold: Boolean, tone: Tone) =
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                typeface = if (bold) this@Ink.bold else regular
                textSize = size
                color = QuotationPdf.colour(tone)
            }

        private fun line(width: Float, tone: Tone) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = width
            color = QuotationPdf.colour(tone)
        }

        private fun fill(tone: Tone) = Paint().apply {
            style = Paint.Style.FILL
            color = QuotationPdf.colour(tone)
        }
    }

    companion object {
        val PAGE_WIDTH: Int = QuotationLayout.PAGE_WIDTH.toInt()
        val PAGE_HEIGHT: Int = QuotationLayout.PAGE_HEIGHT.toInt()

        /** Print colours: near-black text, grey for the quiet parts, a red for CANCELLED. */
        fun colour(tone: Tone): Int = when (tone) {
            Tone.INK -> 0xFF14181F.toInt()
            Tone.MUTED -> 0xFF5B6573.toInt()
            Tone.ALERT -> 0xFFB3261E.toInt()
            Tone.ALERT_FAINT -> 0x2EB3261E
            Tone.RULE -> 0xFFC5CBD3.toInt()
            Tone.BAND -> 0xFFEEF1F4.toInt()
        }
    }
}
