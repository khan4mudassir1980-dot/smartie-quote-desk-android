package `in`.smartie.quotedesk.util

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import `in`.smartie.quotedesk.data.model.CreatedQuotation
import java.io.File
import java.io.FileOutputStream
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

object QuotationPdf {
    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 38f
    private const val PURPLE = 0xFF6422C9.toInt()
    private const val DARK = 0xFF1E293B.toInt()
    private const val MUTED = 0xFF64748B.toInt()
    private const val LINE = 0xFFE2E8F0.toInt()
    private const val LIGHT = 0xFFF8F7FC.toInt()

    fun createAndShare(context: Context, quotation: CreatedQuotation) {
        val directory = File(context.cacheDir, "quotations").apply { mkdirs() }
        val safeNumber = quotation.number.replace(Regex("[^A-Za-z0-9._-]"), "-")
        val file = File(directory, "SMARTIE-Quotation-$safeNumber.pdf")
        PdfDocument().use { document ->
            drawQuotation(document, quotation)
            FileOutputStream(file).use(document::writeTo)
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Quotation ${quotation.number}")
            putExtra(Intent.EXTRA_TEXT, "Please find attached quotation ${quotation.number} from SMARTIE Quote Desk.")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share quotation"))
    }

    private fun drawQuotation(document: PdfDocument, quote: CreatedQuotation) {
        val money = NumberFormat.getCurrencyInstance(Locale("en", "IN"))
        val date = SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH).format(Date(quote.createdAt))
        var pageNumber = 1
        var page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
        var canvas = page.canvas
        var y = drawHeader(canvas, quote.number, date)
        y = drawParty(canvas, quote, y)
        y += 18f
        drawTableHeader(canvas, y)
        y += 28f

        quote.lines.forEachIndexed { index, line ->
            val description = listOf(line.model, line.description).filter { it.isNotBlank() }.joinToString(" — ")
            val wrapped = wrap(description.ifBlank { "Item ${index + 1}" }, 42)
            val rowHeight = maxOf(34f, 17f * wrapped.size + 12f)
            if (y + rowHeight > 704f) {
                drawFooter(canvas, pageNumber)
                document.finishPage(page)
                pageNumber += 1
                page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
                canvas = page.canvas
                y = drawHeader(canvas, quote.number, date, compact = true)
                drawTableHeader(canvas, y)
                y += 28f
            }
            if (index % 2 == 0) canvas.drawRect(MARGIN, y, PAGE_WIDTH - MARGIN, y + rowHeight, fill(LIGHT))
            canvas.drawText((index + 1).toString(), MARGIN + 6, y + 20, text(9f, MUTED))
            wrapped.forEachIndexed { lineIndex, value ->
                canvas.drawText(value, MARGIN + 28, y + 19 + lineIndex * 15, text(9f, DARK))
            }
            canvas.drawText(formatQty(line.quantity, line.unit), 338f, y + 20, text(9f, DARK, align = Paint.Align.RIGHT))
            canvas.drawText(money.format(line.rate), 427f, y + 20, text(9f, DARK, align = Paint.Align.RIGHT))
            canvas.drawText("${formatNumber(line.gst)}%", 466f, y + 20, text(9f, DARK, align = Paint.Align.RIGHT))
            canvas.drawText(money.format(line.amount + line.gstAmount), PAGE_WIDTH - MARGIN - 6, y + 20, text(9f, DARK, align = Paint.Align.RIGHT))
            canvas.drawLine(MARGIN, y + rowHeight, PAGE_WIDTH - MARGIN, y + rowHeight, stroke(LINE))
            y += rowHeight
        }

        if (y + 154f > 760f) {
            drawFooter(canvas, pageNumber)
            document.finishPage(page)
            pageNumber += 1
            page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
            canvas = page.canvas
            y = drawHeader(canvas, quote.number, date, compact = true) + 12f
        }
        y += 16f
        val totalsLeft = 350f
        totalLine(canvas, "Subtotal", money.format(quote.subtotal), y, totalsLeft)
        y += 23f
        totalLine(canvas, "GST", money.format(quote.gstTotal), y, totalsLeft)
        if (quote.additionalAmount > 0) {
            y += 23f
            totalLine(canvas, quote.additionalLabel.ifBlank { "Additional charge" }, money.format(quote.additionalAmount), y, totalsLeft)
        }
        y += 15f
        canvas.drawRoundRect(totalsLeft - 10, y, PAGE_WIDTH - MARGIN, y + 42, 8f, 8f, fill(PURPLE))
        canvas.drawText("Grand total", totalsLeft, y + 27, text(11f, Color.WHITE, bold = true))
        canvas.drawText(money.format(quote.total), PAGE_WIDTH - MARGIN - 10, y + 27, text(13f, Color.WHITE, bold = true, align = Paint.Align.RIGHT))

        val noteY = min(y + 78f, 758f)
        canvas.drawText("Terms", MARGIN, noteY, text(10f, DARK, bold = true))
        canvas.drawText("Prices are subject to the stated GST. This computer-generated quotation needs no signature.", MARGIN, noteY + 17, text(8.5f, MUTED))
        canvas.drawText("Prepared by ${quote.createdBy}", MARGIN, noteY + 34, text(8.5f, MUTED))
        drawFooter(canvas, pageNumber)
        document.finishPage(page)
    }

    private fun drawHeader(canvas: Canvas, number: String, date: String, compact: Boolean = false): Float {
        canvas.drawRect(0f, 0f, PAGE_WIDTH.toFloat(), if (compact) 82f else 116f, fill(DARK))
        canvas.drawCircle(MARGIN + 22, 37f, 22f, fill(PURPLE))
        canvas.drawText("S", MARGIN + 22, 45f, text(22f, Color.WHITE, true, Paint.Align.CENTER))
        canvas.drawText("SMARTIE", MARGIN + 56, 36f, text(18f, Color.WHITE, true))
        canvas.drawText("QUOTE DESK", MARGIN + 57, 53f, text(8f, 0xFFD8C8F5.toInt(), true))
        canvas.drawText("QUOTATION", PAGE_WIDTH - MARGIN, 34f, text(17f, Color.WHITE, true, Paint.Align.RIGHT))
        canvas.drawText(number, PAGE_WIDTH - MARGIN, 53f, text(9f, 0xFFE2E8F0.toInt(), align = Paint.Align.RIGHT))
        canvas.drawText(date, PAGE_WIDTH - MARGIN, 68f, text(8f, 0xFFCBD5E1.toInt(), align = Paint.Align.RIGHT))
        return if (compact) 98f else 132f
    }

    private fun drawParty(canvas: Canvas, quote: CreatedQuotation, top: Float): Float {
        val party = quote.customer
        canvas.drawText("QUOTATION FOR", MARGIN, top, text(8f, PURPLE, true))
        canvas.drawText(party.company.ifBlank { party.name }, MARGIN, top + 23, text(15f, DARK, true))
        if (party.company.isNotBlank()) canvas.drawText(party.name, MARGIN, top + 40, text(9f, MUTED))
        val detail = listOf(party.address, party.city, party.phone, party.email).filter { it.isNotBlank() }.joinToString("  •  ")
        wrap(detail, 75).take(2).forEachIndexed { i, value -> canvas.drawText(value, MARGIN, top + 58 + i * 15, text(8.5f, MUTED)) }
        if (party.gstin.isNotBlank()) canvas.drawText("GSTIN: ${party.gstin}", MARGIN, top + 88, text(8.5f, MUTED))
        canvas.drawText("RATE TYPE", PAGE_WIDTH - MARGIN, top, text(8f, PURPLE, true, Paint.Align.RIGHT))
        canvas.drawText(quote.tier.label, PAGE_WIDTH - MARGIN, top + 23, text(12f, DARK, true, Paint.Align.RIGHT))
        return top + 98f
    }

    private fun drawTableHeader(canvas: Canvas, y: Float) {
        canvas.drawRoundRect(MARGIN, y, PAGE_WIDTH - MARGIN, y + 28, 6f, 6f, fill(PURPLE))
        canvas.drawText("#", MARGIN + 6, y + 18, text(8f, Color.WHITE, true))
        canvas.drawText("ITEM / DESCRIPTION", MARGIN + 28, y + 18, text(8f, Color.WHITE, true))
        canvas.drawText("QTY", 338f, y + 18, text(8f, Color.WHITE, true, Paint.Align.RIGHT))
        canvas.drawText("RATE", 427f, y + 18, text(8f, Color.WHITE, true, Paint.Align.RIGHT))
        canvas.drawText("GST", 466f, y + 18, text(8f, Color.WHITE, true, Paint.Align.RIGHT))
        canvas.drawText("TOTAL", PAGE_WIDTH - MARGIN - 6, y + 18, text(8f, Color.WHITE, true, Paint.Align.RIGHT))
    }

    private fun totalLine(canvas: Canvas, label: String, value: String, y: Float, left: Float) {
        canvas.drawText(label, left, y, text(9f, MUTED))
        canvas.drawText(value, PAGE_WIDTH - MARGIN, y, text(9f, DARK, true, Paint.Align.RIGHT))
    }

    private fun drawFooter(canvas: Canvas, page: Int) {
        canvas.drawLine(MARGIN, 800f, PAGE_WIDTH - MARGIN, 800f, stroke(LINE))
        canvas.drawText("SMARTIE Quote Desk · Smart India Enterprises", MARGIN, 818f, text(8f, MUTED))
        canvas.drawText("Page $page", PAGE_WIDTH - MARGIN, 818f, text(8f, MUTED, align = Paint.Align.RIGHT))
    }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
    private fun stroke(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; strokeWidth = 1f }
    private fun text(size: Float, color: Int, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        textAlign = align
        typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
    }
    private fun wrap(value: String, max: Int): List<String> {
        if (value.isBlank()) return listOf("")
        val lines = mutableListOf<String>()
        var current = ""
        value.trim().split(Regex("\\s+")).forEach { word ->
            if (current.isEmpty() || current.length + word.length + 1 <= max) current = if (current.isEmpty()) word else "$current $word"
            else { lines += current; current = word }
        }
        if (current.isNotEmpty()) lines += current
        return lines
    }
    private fun formatNumber(value: Double) = if (value % 1.0 == 0.0) value.toInt().toString() else "%.2f".format(Locale.ENGLISH, value)
    private fun formatQty(value: Double, unit: String) = "${formatNumber(value)} ${unit.take(4)}"
}
