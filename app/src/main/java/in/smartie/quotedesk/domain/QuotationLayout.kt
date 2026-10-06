package `in`.smartie.quotedesk.domain

/**
 * How wide a string is when drawn: the renderer's `Paint.measureText` on
 * Inter, and a fixed-width fake in the JVM tests.
 */
fun interface TextMeasurer {
    fun width(text: String, size: Float, bold: Boolean): Float
}

/** What colour a mark is drawn in; the renderer maps each to one. */
enum class Tone { INK, MUTED, ALERT, ALERT_FAINT, RULE, BAND }

/** Which stored image an [DrawOp.Image] draws, and so how it sits in its box. */
enum class ImageRole { LOGO, QR, SIGNATURE }

/** One mark on a page, in PDF points from the top left. */
sealed class DrawOp {
    /** [y] is the baseline. */
    data class Text(
        val x: Float,
        val y: Float,
        val text: String,
        val size: Float,
        val bold: Boolean = false,
        val tone: Tone = Tone.INK
    ) : DrawOp()

    data class Rule(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val width: Float = 0.5f,
        val tone: Tone = Tone.RULE
    ) : DrawOp()

    data class Fill(val x: Float, val y: Float, val w: Float, val h: Float, val tone: Tone = Tone.BAND) : DrawOp()

    /** The image already fitted to its proportions: drawn into exactly this box. */
    data class Image(val x: Float, val y: Float, val w: Float, val h: Float, val role: ImageRole) : DrawOp()

    /** CANCELLED across the page, centred on ([cx], [cy]), drawn first so the content sits on it. */
    data class Stamp(val cx: Float, val cy: Float, val text: String, val size: Float, val degrees: Float) : DrawOp()
}

data class LaidOutPage(val number: Int, val ops: List<DrawOp>)

/**
 * **The quotation's pages, laid out** — N5.11 commit 6. Every position and
 * every line break is decided here, in plain Kotlin, so the JVM tests the
 * pagination; the renderer (commit 7) draws each [DrawOp] and decides nothing.
 *
 * A4 in PDF points, the order of [QuotationDocument] (the Owner's approved
 * order of 2026-10-06). The item table's header is repeated at the top of
 * every page it continues onto; "footer | web" and "Page p of n" are on every
 * page; a cancelled quotation is stamped CANCELLED across every page and
 * marked under the title. A block is kept whole on one page when it fits on
 * one; one taller than a page is split between its lines, so nothing is ever
 * cut off.
 *
 * [images] holds the proportions (width ÷ height) of the images that
 * decoded. One that is absent, or did not decode, is not drawn: no box, no
 * SCAN TO PAY block, the signatory's words alone.
 */
object QuotationLayout {

    const val PAGE_WIDTH = 595f
    const val PAGE_HEIGHT = 842f
    const val MARGIN = 36f
    const val CONTENT_WIDTH = PAGE_WIDTH - 2 * MARGIN
    const val RIGHT = PAGE_WIDTH - MARGIN

    const val NAME_SIZE = 16f
    const val LETTERHEAD_SIZE = 8.5f
    const val TITLE_SIZE = 14f
    const val MARK_SIZE = 12f
    const val HEADING_SIZE = 7.5f
    const val BODY_SIZE = 9f
    const val SMALL_SIZE = 7.5f
    const val GRAND_SIZE = 11f
    const val STAMP_SIZE = 80f
    const val STAMP_DEGREES = -35f

    const val LOGO_HEIGHT = 48f
    const val LOGO_MAX_WIDTH = 160f
    const val QR_SIZE = 96f
    const val SIGNATURE_WIDTH = 150f
    const val SIGNATURE_HEIGHT = 50f

    /** A line's height is its size times this; its baseline sits [BASELINE] sizes down. */
    const val LEADING = 1.35f
    const val BASELINE = 1.04f

    private const val GAP = 12f
    private const val GUTTER = 16f
    private const val CELL_GAP = 6f
    private const val PAD = 3f
    private const val LABEL_WIDTH = 72f
    private const val TOTALS_WIDTH = 250f
    private const val QR_COLUMN = 140f
    private const val SIGNATORY_WIDTH = 230f
    private const val ACCEPT_RULE = 180f
    private const val BULLET = "• "

    /** The item table's columns: #, model and product, description, qty, rate, amount. */
    const val NUMBER_WIDTH = 18f
    const val PRODUCT_WIDTH = 130f
    const val QUANTITY_WIDTH = 50f
    const val RATE_WIDTH = 66f
    const val AMOUNT_WIDTH = 74f
    const val DESCRIPTION_WIDTH =
        CONTENT_WIDTH - NUMBER_WIDTH - PRODUCT_WIDTH - QUANTITY_WIDTH - RATE_WIDTH - AMOUNT_WIDTH - 5 * CELL_GAP

    fun lineHeight(size: Float): Float = size * LEADING

    fun lay(document: QuotationDocument, measure: TextMeasurer, images: Map<ImageRole, Float>): List<LaidOutPage> {
        val sheet = Sheet(measure)
        val blocks = buildList {
            letterhead(document, sheet, images)?.let(::add)
            add(title(document, sheet))
            panels(document, sheet)?.let(::add)
            addAll(table(document, sheet))
            add(totals(document, sheet))
            add(amountInWords(document, sheet))
            paragraphs(document.conditions, sheet)?.let(::add)
            notesAndTerms(document, sheet)?.let(::add)
            bankAndQr(document, sheet, images)?.let(::add)
            add(signatures(document, sheet, images))
        }
        val footer = footerLines(document, sheet)
        val footerHeight = footer.sumOf { it.height.toDouble() }.toFloat() + PAD * 2
        val pages = paginate(blocks, bottom = PAGE_HEIGHT - MARGIN - footerHeight)
        return pages.mapIndexed { index, ops ->
            val number = index + 1
            val all = buildList {
                if (document.cancelled) {
                    add(DrawOp.Stamp(PAGE_WIDTH / 2, PAGE_HEIGHT / 2, QuotationDocument.CANCELLED, STAMP_SIZE, STAMP_DEGREES))
                }
                addAll(ops)
                addAll(footerOps(footer, footerHeight, number, pages.size, sheet))
            }
            LaidOutPage(number, all)
        }
    }

    // --- the blocks, in the printed order -------------------------------------------------

    private fun letterhead(document: QuotationDocument, sheet: Sheet, images: Map<ImageRole, Float>): Block? {
        val head = document.letterhead
        val aspect = images[ImageRole.LOGO]?.takeIf { head.logo.isNotBlank() && it > 0f }
        if (head.name.isBlank() && head.lines.isEmpty() && aspect == null) return null
        var logoWidth = 0f
        val logo = aspect?.let {
            var h = LOGO_HEIGHT
            var w = h * it
            if (w > LOGO_MAX_WIDTH) {
                w = LOGO_MAX_WIDTH
                h = w / it
            }
            logoWidth = w
            DrawOp.Image(MARGIN, 0f, w, h, ImageRole.LOGO)
        }
        val textX = if (logo != null) MARGIN + logoWidth + GUTTER else MARGIN
        val width = RIGHT - textX
        val text = buildList {
            if (head.name.isNotBlank()) addAll(sheet.paragraph(head.name, textX, width, NAME_SIZE, bold = true))
            for (line in head.lines) addAll(sheet.paragraph(line, textX, width, LETTERHEAD_SIZE, tone = Tone.MUTED))
        }
        val tall = Line.stack(text)
        val height = maxOf(tall.height, logo?.h ?: 0f) + PAD * 2
        val ops = buildList {
            logo?.let { add(it) }
            addAll(tall.ops)
            add(DrawOp.Rule(MARGIN, height, RIGHT, height, width = 1f, tone = Tone.INK))
        }
        return Block(listOf(Line(height + PAD, ops)), keep = true, spaceBefore = 0f)
    }

    private fun title(document: QuotationDocument, sheet: Sheet): Block {
        val lines = buildList {
            add(sheet.centred(QuotationDocument.TITLE, TITLE_SIZE, bold = true))
            if (document.cancelled) add(sheet.centred(QuotationDocument.CANCELLED, MARK_SIZE, bold = true, tone = Tone.ALERT))
        }
        return Block(listOf(Line.stack(lines)), keep = true)
    }

    private fun panels(document: QuotationDocument, sheet: Sheet): Block? {
        val width = (CONTENT_WIDTH - GUTTER) / 2
        val columns = listOf(
            QuotationDocument.BILL_TO to document.billTo,
            QuotationDocument.DETAILS to document.details
        ).filter { it.second.isNotEmpty() }
        if (columns.isEmpty()) return null
        val stacks = columns.mapIndexed { index, (heading, rows) ->
            val x = MARGIN + index * (width + GUTTER)
            Line.stack(listOf(sheet.heading(heading, x, width)) + rows.flatMap { sheet.labelled(it, x, width) })
        }
        return Block(listOf(Line.beside(stacks)), keep = true)
    }

    /** The header and the first row kept together; every row after, kept whole, with the header to repeat. */
    private fun table(document: QuotationDocument, sheet: Sheet): List<Block> {
        val header = Block(listOf(sheet.tableHeader()), keep = true)
        val rows = document.items.map { sheet.itemRow(it) }
        if (rows.isEmpty()) return listOf(header)
        return listOf(Block(header.lines + rows.first(), keep = true)) +
            rows.drop(1).map { Block(it, keep = true, spaceBefore = 0f, header = header) }
    }

    private fun totals(document: QuotationDocument, sheet: Sheet): Block {
        val x = RIGHT - TOTALS_WIDTH
        val lines = document.totals.flatMap { row ->
            buildList {
                if (row.emphasis) add(Line(PAD, listOf(DrawOp.Rule(x, PAD / 2, RIGHT, PAD / 2, width = 0.8f, tone = Tone.INK))))
                val size = if (row.emphasis) GRAND_SIZE else BODY_SIZE
                add(sheet.labelAndAmount(row.label, row.amount, x, size, bold = row.emphasis))
                if (row.note.isNotBlank()) {
                    addAll(sheet.paragraph(row.note, x, TOTALS_WIDTH - AMOUNT_WIDTH, SMALL_SIZE, tone = Tone.MUTED))
                }
            }
        }
        return Block(lines, keep = true)
    }

    private fun amountInWords(document: QuotationDocument, sheet: Sheet): Block {
        val lines = buildList {
            add(Line(PAD, listOf(DrawOp.Rule(MARGIN, 0f, RIGHT, 0f))))
            add(sheet.heading(QuotationDocument.AMOUNT_IN_WORDS, MARGIN, CONTENT_WIDTH, rule = false))
            addAll(sheet.paragraph(document.amountInWords, MARGIN, CONTENT_WIDTH, BODY_SIZE, bold = true))
            add(Line(PAD, listOf(DrawOp.Rule(MARGIN, PAD, RIGHT, PAD))))
        }
        return Block(lines, keep = true)
    }

    private fun paragraphs(texts: List<String>, sheet: Sheet): Block? {
        if (texts.isEmpty()) return null
        return Block(texts.flatMap { sheet.paragraph(it, MARGIN, CONTENT_WIDTH, BODY_SIZE) }, keep = true)
    }

    /** Side by side when both are there, each alone at full width otherwise; split between lines if long. */
    private fun notesAndTerms(document: QuotationDocument, sheet: Sheet): Block? {
        val columns = listOf(QuotationDocument.NOTES to document.notes, QuotationDocument.TERMS to document.terms)
            .filter { it.second.isNotEmpty() }
        if (columns.isEmpty()) return null
        val width = if (columns.size == 2) (CONTENT_WIDTH - GUTTER) / 2 else CONTENT_WIDTH
        val each = columns.mapIndexed { index, (heading, items) ->
            val x = MARGIN + index * (width + GUTTER)
            listOf(sheet.heading(heading, x, width)) + items.flatMap { sheet.bulleted(it, x, width) }
        }
        return Block(Line.zip(each), keep = true)
    }

    private fun bankAndQr(document: QuotationDocument, sheet: Sheet, images: Map<ImageRole, Float>): Block? {
        val qr = images[ImageRole.QR]?.takeIf { document.qr.isNotBlank() && it > 0f }
        if (document.bank.isEmpty() && qr == null) return null
        val columns = buildList {
            if (document.bank.isNotEmpty()) {
                val width = if (qr != null) CONTENT_WIDTH - QR_COLUMN - GUTTER else CONTENT_WIDTH
                add(
                    Line.stack(
                        listOf(sheet.heading(QuotationDocument.BANK_DETAILS, MARGIN, width)) +
                            document.bank.flatMap { sheet.labelled(it, MARGIN, width) }
                    )
                )
            }
            if (qr != null) {
                val x = RIGHT - QR_COLUMN
                val (w, h) = fit(qr, QR_SIZE, QR_SIZE)
                add(
                    Line.stack(
                        listOf(
                            sheet.heading(QuotationDocument.SCAN_TO_PAY, x, QR_COLUMN),
                            Line(QR_SIZE + PAD, listOf(DrawOp.Image(x + (QR_COLUMN - w) / 2, PAD + (QR_SIZE - h) / 2, w, h, ImageRole.QR)))
                        ) + sheet.paragraph(QuotationDocument.UPI_APPS, x, QR_COLUMN, SMALL_SIZE, tone = Tone.MUTED, centred = true)
                    )
                )
            }
        }
        return Block(listOf(Line.beside(columns)), keep = true)
    }

    /**
     * The closing band: the customer's acceptance on the left — "Accepted
     * for <client>", room to sign, a line, "Signature & date" — and the
     * firm's on the right: the signature image when there is one, else room
     * to sign by hand, and "Authorised signatory for <firm>". Side by side, so
     * a short quotation stays on one page.
     */
    private fun signatures(document: QuotationDocument, sheet: Sheet, images: Map<ImageRole, Float>): Block {
        val aspect = images[ImageRole.SIGNATURE]?.takeIf { document.signature.isNotBlank() && it > 0f }
        val x = RIGHT - SIGNATORY_WIDTH
        val firm = Line.stack(
            buildList {
                if (aspect != null) {
                    val (w, h) = fit(aspect, SIGNATURE_WIDTH, SIGNATURE_HEIGHT)
                    add(Line(SIGNATURE_HEIGHT + PAD, listOf(DrawOp.Image(RIGHT - w, SIGNATURE_HEIGHT - h, w, h, ImageRole.SIGNATURE))))
                } else {
                    add(Line(SIGNATURE_HEIGHT + PAD, emptyList()))
                }
                addAll(sheet.paragraph(document.signatory, x, SIGNATORY_WIDTH, BODY_SIZE, bold = true, end = true))
            }
        )
        val customer = Line.stack(
            buildList {
                addAll(sheet.paragraph(document.acceptance, MARGIN, x - MARGIN - GUTTER, BODY_SIZE))
                val room = SIGNATURE_HEIGHT * 0.6f
                add(Line(room, listOf(DrawOp.Rule(MARGIN, room, MARGIN + ACCEPT_RULE, room, tone = Tone.INK))))
                addAll(sheet.paragraph(QuotationDocument.SIGNATURE_AND_DATE, MARGIN, ACCEPT_RULE, SMALL_SIZE, tone = Tone.MUTED))
            }
        )
        return Block(listOf(Line.beside(listOf(customer, firm))), keep = true)
    }

    // --- the footer --------------------------------------------------------------------------

    private fun footerLines(document: QuotationDocument, sheet: Sheet): List<Line> {
        val room = CONTENT_WIDTH - sheet.width(QuotationDocument.pageOf(999, 999), SMALL_SIZE, false) - GUTTER
        val text = sheet.paragraph(document.footer, MARGIN, room, SMALL_SIZE, tone = Tone.MUTED)
        return text.ifEmpty { listOf(Line(lineHeight(SMALL_SIZE), emptyList())) }
    }

    private fun footerOps(footer: List<Line>, height: Float, page: Int, pages: Int, sheet: Sheet): List<DrawOp> {
        val top = PAGE_HEIGHT - MARGIN - height
        val ops = mutableListOf<DrawOp>(DrawOp.Rule(MARGIN, top, RIGHT, top))
        var y = top + PAD
        for (line in footer) {
            ops += line.ops.map { it.shifted(y) }
            y += line.height
        }
        val label = QuotationDocument.pageOf(page, pages)
        ops += DrawOp.Text(
            RIGHT - sheet.width(label, SMALL_SIZE, false), top + PAD + SMALL_SIZE * BASELINE, label, SMALL_SIZE,
            tone = Tone.MUTED
        )
        return ops
    }

    // --- pagination --------------------------------------------------------------------------

    /**
     * Blocks onto pages. A kept block that does not fit what is left of the
     * page starts the next one, if it fits a page at all; otherwise — and for
     * every block not kept — the page breaks between lines. A block with a
     * [Block.header] repeats it at the top of the page it moves onto.
     */
    private fun paginate(blocks: List<Block>, bottom: Float): List<List<DrawOp>> {
        val pages = mutableListOf(mutableListOf<DrawOp>())
        var y = MARGIN
        var pageTop = MARGIN

        fun place(line: Line) {
            pages.last() += line.ops.map { it.shifted(y) }
            y += line.height
        }

        fun newPage(header: Block?) {
            pages += mutableListOf<DrawOp>()
            y = MARGIN
            header?.lines?.forEach(::place)
            pageTop = y
        }

        for (block in blocks) {
            val gap = if (y == pageTop && block.header == null) 0f else block.spaceBefore
            val headerHeight = block.header?.height ?: 0f
            val fitsAPage = block.height <= bottom - MARGIN - headerHeight
            if (block.keep && fitsAPage && y + gap + block.height > bottom) {
                newPage(block.header)
            } else {
                y += gap
            }
            for (line in block.lines) {
                if (y + line.height > bottom && y > pageTop) newPage(block.header)
                place(line)
            }
        }
        return pages
    }

    /** [aspect] (width ÷ height) fitted inside [maxW] × [maxH]. */
    fun fit(aspect: Float, maxW: Float, maxH: Float): Pair<Float, Float> =
        if (maxW / maxH > aspect) (maxH * aspect) to maxH else maxW to (maxW / aspect)

    /**
     * [text] broken into lines no wider than [width]: at spaces where it can,
     * inside a word only when the word alone is too wide. Runs of spaces close
     * up; a line break in the text is kept; nothing is dropped.
     */
    fun wrap(text: String, width: Float, size: Float, bold: Boolean, measure: TextMeasurer): List<String> {
        val out = mutableListOf<String>()
        for (paragraph in text.split('\n')) {
            var line = ""
            for (word in paragraph.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }) {
                val joined = if (line.isEmpty()) word else "$line $word"
                if (measure.width(joined, size, bold) <= width) {
                    line = joined
                    continue
                }
                if (line.isNotEmpty()) out += line
                line = ""
                var index = 0
                while (index < word.length) {
                    val step = Character.charCount(word.codePointAt(index))
                    val next = line + word.substring(index, index + step)
                    if (line.isNotEmpty() && measure.width(next, size, bold) > width) {
                        out += line
                        line = word.substring(index, index + step)
                    } else {
                        line = next
                    }
                    index += step
                }
            }
            if (line.isNotEmpty()) out += line
        }
        return out
    }

    // --- the parts ---------------------------------------------------------------------------

    /** A strip of a page: its height and its marks, with `y` measured from its top. */
    private class Line(val height: Float, val ops: List<DrawOp>) {
        companion object {
            /** Lines one under another, as one line. */
            fun stack(lines: List<Line>): Line {
                var y = 0f
                val ops = mutableListOf<DrawOp>()
                for (line in lines) {
                    ops += line.ops.map { it.shifted(y) }
                    y += line.height
                }
                return Line(y, ops)
            }

            /** Columns side by side, as one line as tall as the tallest. */
            fun beside(columns: List<Line>): Line =
                Line(columns.maxOfOrNull { it.height } ?: 0f, columns.flatMap { it.ops })

            /** Columns side by side, line by line, so the page can break between them. */
            fun zip(columns: List<List<Line>>): List<Line> {
                val count = columns.maxOfOrNull { it.size } ?: 0
                return (0 until count).map { index ->
                    beside(columns.mapNotNull { it.getOrNull(index) })
                }
            }
        }
    }

    private class Block(
        val lines: List<Line>,
        val keep: Boolean,
        val spaceBefore: Float = GAP,
        val header: Block? = null
    ) {
        val height: Float get() = lines.sumOf { it.height.toDouble() }.toFloat()
    }

    /** The measuring and the small layouts every block uses. */
    private class Sheet(private val measure: TextMeasurer) {

        fun width(text: String, size: Float, bold: Boolean): Float = measure.width(text, size, bold)

        fun paragraph(
            text: String,
            x: Float,
            width: Float,
            size: Float,
            bold: Boolean = false,
            tone: Tone = Tone.INK,
            end: Boolean = false,
            centred: Boolean = false
        ): List<Line> = wrap(text, width, size, bold, measure).map { piece ->
            val w = measure.width(piece, size, bold)
            val at = when {
                end -> x + width - w
                centred -> x + (width - w) / 2
                else -> x
            }
            Line(lineHeight(size), listOf(DrawOp.Text(at, size * BASELINE, piece, size, bold, tone)))
        }

        fun centred(text: String, size: Float, bold: Boolean, tone: Tone = Tone.INK): Line =
            Line.stack(paragraph(text, MARGIN, CONTENT_WIDTH, size, bold, tone, centred = true))

        fun heading(text: String, x: Float, width: Float, rule: Boolean = true): Line {
            val words = paragraph(text, x, width, HEADING_SIZE, bold = true, tone = Tone.MUTED)
            val stacked = Line.stack(words)
            val ops = if (rule) stacked.ops + DrawOp.Rule(x, stacked.height + 1f, x + width, stacked.height + 1f) else stacked.ops
            return Line(stacked.height + PAD, ops)
        }

        /** "Label   value", the value wrapped beside the label. */
        fun labelled(row: DocRow, x: Float, width: Float): List<Line> {
            val label = paragraph(row.label, x, LABEL_WIDTH - CELL_GAP, BODY_SIZE, tone = Tone.MUTED)
            val value = paragraph(row.value, x + LABEL_WIDTH, width - LABEL_WIDTH, BODY_SIZE)
            return Line.zip(listOf(label, value))
        }

        fun bulleted(text: String, x: Float, width: Float): List<Line> {
            val indent = measure.width(BULLET, BODY_SIZE, false)
            val body = paragraph(text, x + indent, width - indent, BODY_SIZE)
            return body.mapIndexed { index, line ->
                if (index == 0) Line(line.height, listOf(DrawOp.Text(x, BODY_SIZE * BASELINE, BULLET.trim(), BODY_SIZE)) + line.ops) else line
            }
        }

        fun labelAndAmount(label: String, amount: String, x: Float, size: Float, bold: Boolean): Line {
            val labels = paragraph(label, x, TOTALS_WIDTH - AMOUNT_WIDTH - CELL_GAP, size, bold)
            val amounts = paragraph(amount, RIGHT - AMOUNT_WIDTH, AMOUNT_WIDTH, size, bold, end = true)
            return Line.stack(Line.zip(listOf(labels, amounts)))
        }

        private val columns: List<Pair<Float, Float>> = run {
            val widths = listOf(NUMBER_WIDTH, PRODUCT_WIDTH, DESCRIPTION_WIDTH, QUANTITY_WIDTH, RATE_WIDTH, AMOUNT_WIDTH)
            var x = MARGIN
            widths.map { w -> (x to w).also { x += w + CELL_GAP } }
        }

        fun tableHeader(): Line {
            val titles = listOf(
                QuotationDocument.COLUMN_NUMBER, QuotationDocument.COLUMN_PRODUCT, QuotationDocument.COLUMN_DESCRIPTION,
                QuotationDocument.COLUMN_QUANTITY, QuotationDocument.COLUMN_RATE, QuotationDocument.COLUMN_AMOUNT
            )
            val cells = titles.mapIndexed { index, title ->
                val (x, w) = columns[index]
                paragraph(title, x, w, HEADING_SIZE, bold = true, end = index >= 3)
            }
            val body = Line.stack(Line.zip(cells))
            val height = body.height + PAD * 2
            return Line(
                height,
                listOf(DrawOp.Fill(MARGIN, 0f, CONTENT_WIDTH, height)) + body.ops.map { it.shifted(PAD) }
            )
        }

        /** One item's lines: its cells side by side, a rule beneath. */
        fun itemRow(item: ItemRow): List<Line> {
            val product = buildList {
                if (item.model.isNotBlank()) addAll(paragraph(item.model, columns[1].first, columns[1].second, BODY_SIZE, bold = true))
                addAll(paragraph(item.product, columns[1].first, columns[1].second, BODY_SIZE))
            }
            val cells = listOf(
                paragraph(item.number.toString(), columns[0].first, columns[0].second, BODY_SIZE),
                product,
                paragraph(item.description, columns[2].first, columns[2].second, SMALL_SIZE, tone = Tone.MUTED),
                paragraph(item.quantity, columns[3].first, columns[3].second, BODY_SIZE, end = true),
                paragraph(item.rate, columns[4].first, columns[4].second, BODY_SIZE, end = true),
                paragraph(item.amount, columns[5].first, columns[5].second, BODY_SIZE, end = true)
            )
            val lines = Line.zip(cells)
            return listOf(Line(PAD, emptyList())) + lines +
                Line(PAD, listOf(DrawOp.Rule(MARGIN, PAD, RIGHT, PAD)))
        }
    }
}

/** [this] moved down the page by [dy]. */
internal fun DrawOp.shifted(dy: Float): DrawOp = when (this) {
    is DrawOp.Text -> copy(y = y + dy)
    is DrawOp.Rule -> copy(y1 = y1 + dy, y2 = y2 + dy)
    is DrawOp.Fill -> copy(y = y + dy)
    is DrawOp.Image -> copy(y = y + dy)
    is DrawOp.Stamp -> this
}
