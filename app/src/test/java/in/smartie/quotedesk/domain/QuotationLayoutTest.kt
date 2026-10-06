package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.domain.QuotationLayout.CONTENT_WIDTH
import `in`.smartie.quotedesk.domain.QuotationLayout.MARGIN
import `in`.smartie.quotedesk.domain.QuotationLayout.PAGE_HEIGHT
import `in`.smartie.quotedesk.domain.QuotationLayout.RIGHT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pages, laid out (N5.11 commit 6), against a fixed-width measure: each
 * character half its size wide, a bold one a little more. Synthetic content
 * only.
 */
class QuotationLayoutTest {

    private val measure = TextMeasurer { text, size, bold -> text.length * size * (if (bold) 0.55f else 0.5f) }

    private val allImages = mapOf(ImageRole.LOGO to 2f, ImageRole.QR to 1f, ImageRole.SIGNATURE to 3f)

    private fun item(n: Int, description: String = "Synthetic spec $n") = ItemRow(
        number = n, model = "TM-$n", product = "Test item $n", description = description,
        quantity = "1", rate = "₹1,000", amount = "₹${n},000"
    )

    private fun document(
        items: Int = 3,
        cancelled: Boolean = false,
        notes: List<String> = listOf("Test note one."),
        terms: List<String> = listOf("Test term one.", "Test term two."),
        bank: List<DocRow> = listOf(DocRow("Name", "Test Firm"), DocRow("Account no", "000000000000")),
        qr: String = "data:image/png;base64,UVI=",
        signature: String = "data:image/png;base64,U0lH",
        rows: List<ItemRow> = (1..items).map { item(it) }
    ) = QuotationDocument(
        letterhead = LetterheadBlock(
            logo = "data:image/png;base64,TE9HTw==",
            name = "Test Gates & Shutters",
            lines = listOf("Synthetic test data", "1 Test Road, Test City", "quotes@example.invalid | example.invalid")
        ),
        cancelled = cancelled,
        billTo = listOf(DocRow("Customer", "Test Customer"), DocRow("Phone", "+91 90000 00009")),
        details = listOf(DocRow("Quotation no", "TEST/QD/2026-27/001"), DocRow("Date", "5 Oct 2026")),
        items = rows,
        totals = listOf(
            TotalRow("Products subtotal", "₹6,000"),
            TotalRow("Installation extra", ""),
            TotalRow("Transportation", "₹500", note = "Test depot to site"),
            TotalRow("Subtotal", "₹6,500"),
            TotalRow("GST 18%", "₹1,170"),
            TotalRow("Grand total", "₹7,670", emphasis = true)
        ),
        amountInWords = "Seven thousand six hundred and seventy rupees only",
        conditions = listOf("Rates hold for 15 days from the date of this quotation.", "Warranty: 12 months (test)"),
        notes = notes,
        terms = terms,
        bank = bank,
        qr = qr,
        signature = signature,
        signatory = "Authorised signatory for Test Gates & Shutters",
        acceptance = "Accepted for Test Customer",
        footer = "Synthetic test footer | example.invalid",
        fileName = "Quotation-TEST-QD-2026-27-001-Test-Customer.pdf",
        missing = emptyList()
    )

    private fun lay(doc: QuotationDocument = document(), images: Map<ImageRole, Float> = allImages) =
        QuotationLayout.lay(doc, measure, images)

    private fun List<DrawOp>.texts() = filterIsInstance<DrawOp.Text>()

    /** Where [text] is first drawn: its page (from 0) and its baseline. */
    private fun List<LaidOutPage>.find(text: String): Pair<Int, Float> {
        forEachIndexed { index, page ->
            page.ops.texts().firstOrNull { it.text == text }?.let { return index to it.y }
        }
        throw AssertionError("not drawn: $text")
    }

    private fun List<LaidOutPage>.pageOf(text: String): Int = find(text).first

    private fun List<LaidOutPage>.drawn(text: String): Boolean = any { page -> page.ops.texts().any { it.text == text } }

    // --- pages, footer, page numbers ----------------------------------------------------------

    @Test
    fun `a short quotation is one page, numbered Page 1 of 1, with the footer`() {
        val pages = lay()
        assertEquals(1, pages.size)
        assertTrue(pages.drawn("Page 1 of 1"))
        assertTrue(pages.drawn("Synthetic test footer | example.invalid"))
    }

    @Test
    fun `every page carries the footer and its own Page p of n`() {
        val pages = lay(document(items = 80))
        assertTrue("80 items run over several pages", pages.size >= 3)
        pages.forEachIndexed { index, page ->
            val texts = page.ops.texts().map { it.text }
            assertTrue(texts.contains("Page ${index + 1} of ${pages.size}"))
            assertTrue(texts.contains("Synthetic test footer | example.invalid"))
            assertEquals(index + 1, page.number)
        }
    }

    @Test
    fun `the item table's header is repeated at the top of every page it runs onto`() {
        val pages = lay(document(items = 80))
        for (page in pages) {
            val texts = page.ops.texts()
            val items = texts.filter { it.text.startsWith("Test item ") }
            if (items.isEmpty()) continue
            val header = texts.singleOrNull { it.text == QuotationDocument.COLUMN_PRODUCT }
            assertTrue("page ${page.number} has items and its header", header != null)
            assertTrue(header!!.y < items.minOf { it.y })
        }
    }

    @Test
    fun `no item row is split between pages`() {
        val pages = lay(document(items = 80))
        for (n in 1..80) {
            assertEquals("item $n", pages.pageOf("Test item $n"), pages.pageOf("₹$n,000"))
            assertEquals("item $n", pages.pageOf("Test item $n"), pages.pageOf("Synthetic spec $n"))
        }
    }

    @Test
    fun `a kept block is never split - the totals, the words, the bank and the signatory, whatever the length`() {
        for (count in 1..70) {
            val pages = lay(document(items = count))
            val totals = listOf("Products subtotal", "Subtotal", "GST 18%", "Grand total", "₹7,670")
                .map { pages.pageOf(it) }.toSet()
            assertEquals("totals, $count items", 1, totals.size)
            assertEquals(
                "amount in words, $count items",
                pages.pageOf(QuotationDocument.AMOUNT_IN_WORDS),
                pages.pageOf("Seven thousand six hundred and seventy rupees only")
            )
            assertEquals("bank, $count items", pages.pageOf(QuotationDocument.BANK_DETAILS), pages.pageOf("000000000000"))
            assertEquals("QR, $count items", pages.pageOf(QuotationDocument.BANK_DETAILS), pages.pageOf(QuotationDocument.SCAN_TO_PAY))
        }
    }

    @Test
    fun `nothing is drawn outside the margins, and below the content only the footer`() {
        for (count in listOf(1, 20, 37, 80)) {
            val pages = lay(document(items = count, cancelled = true))
            for (page in pages) {
                // The footer's top, from its own page number: its rule sits a
                // pad above the text. Nothing else may come below it.
                val label = page.ops.texts().single { it.text.startsWith("Page ") }
                val footerTop = label.y - label.size * QuotationLayout.BASELINE - 3f
                for (rule in page.ops.filterIsInstance<DrawOp.Rule>()) {
                    assertTrue("a rule at ${rule.y1} above the footer", maxOf(rule.y1, rule.y2) <= footerTop + 0.01f)
                }
                for (image in page.ops.filterIsInstance<DrawOp.Image>()) {
                    assertTrue("${image.role} above the footer", image.y + image.h <= footerTop + 0.01f)
                }
                for (text in page.ops.texts()) {
                    val top = text.y - text.size * QuotationLayout.BASELINE
                    val bottom = top + QuotationLayout.lineHeight(text.size)
                    assertTrue("${text.text} top", top >= MARGIN - 0.01f)
                    assertTrue("${text.text} bottom", bottom <= PAGE_HEIGHT - MARGIN + 0.01f)
                    assertTrue("${text.text} left", text.x >= MARGIN - 0.01f)
                    assertTrue("${text.text} right", text.x + measure.width(text.text, text.size, text.bold) <= RIGHT + 0.01f)
                    val isFooter = text.text.startsWith("Page ") || text.text.startsWith("Synthetic test footer")
                    if (!isFooter) assertTrue("${text.text} above the footer", bottom <= footerTop + 0.01f)
                }
            }
        }
    }

    // --- order and content --------------------------------------------------------------------

    @Test
    fun `the sections come in the approved order`() {
        val pages = lay(document(items = 30))
        val order = listOf(
            "Test Gates & Shutters",
            QuotationDocument.TITLE,
            QuotationDocument.BILL_TO,
            QuotationDocument.COLUMN_PRODUCT,
            "Test item 1",
            "Test item 30",
            "Products subtotal",
            "Grand total",
            QuotationDocument.AMOUNT_IN_WORDS,
            "Rates hold for 15 days from the date of this quotation.",
            QuotationDocument.NOTES,
            QuotationDocument.BANK_DETAILS,
            "Accepted for Test Customer",
            QuotationDocument.SIGNATURE_AND_DATE
        ).map { pages.find(it) }
        for (i in 1 until order.size) {
            val (page, y) = order[i]
            val (before, beforeY) = order[i - 1]
            assertTrue("step $i", page > before || (page == before && y > beforeY))
        }
        // The closing band: the firm's signatory beside the customer's
        // acceptance, on the right, after the bank block.
        val signatory = pages.find("Authorised signatory for Test Gates & Shutters")
        assertEquals(pages.pageOf("Accepted for Test Customer"), signatory.first)
        val bank = pages.find("000000000000")
        assertTrue(signatory.first > bank.first || (signatory.first == bank.first && signatory.second > bank.second))
        val x = pages.flatMap { it.ops.texts() }.single { it.text == "Authorised signatory for Test Gates & Shutters" }.x
        assertTrue(x > MARGIN + CONTENT_WIDTH / 2)
    }

    @Test
    fun `every word the document holds is drawn`() {
        val doc = document(items = 25)
        val pages = lay(doc)
        val drawn = pages.flatMap { page -> page.ops.texts().flatMap { it.text.split(' ') } }.toSet()
        val held = buildList {
            add(doc.letterhead.name); addAll(doc.letterhead.lines)
            doc.billTo.forEach { add(it.label); add(it.value) }
            doc.details.forEach { add(it.label); add(it.value) }
            doc.items.forEach { addAll(listOf(it.number.toString(), it.model, it.product, it.description, it.quantity, it.rate, it.amount)) }
            doc.totals.forEach { add(it.label); add(it.amount); add(it.note) }
            add(doc.amountInWords); addAll(doc.conditions); addAll(doc.notes); addAll(doc.terms)
            doc.bank.forEach { add(it.label); add(it.value) }
            add(QuotationDocument.UPI_APPS); add(doc.signatory); add(doc.acceptance); add(doc.footer)
            add(QuotationDocument.SIGNATURE_AND_DATE)
        }.flatMap { it.split(' ') }.filter { it.isNotEmpty() }
        val lost = held.filter { it !in drawn }
        assertEquals(emptyList<String>(), lost)
    }

    @Test
    fun `the letterhead is on the first page only`() {
        val pages = lay(document(items = 80))
        assertTrue(pages[0].ops.texts().any { it.text == "Test Gates & Shutters" })
        assertTrue(pages.drop(1).none { page -> page.ops.texts().any { it.text == "Test Gates & Shutters" } })
        assertEquals(1, pages.sumOf { page -> page.ops.count { it is DrawOp.Image && it.role == ImageRole.LOGO } })
    }

    @Test
    fun `money is right-aligned to its column`() {
        val pages = lay()
        val amount = pages[0].ops.texts().single { it.text == "₹7,670" }
        assertEquals(RIGHT, amount.x + measure.width(amount.text, amount.size, amount.bold), 0.01f)
        val line = pages[0].ops.texts().single { it.text == "₹3,000" }
        assertEquals(RIGHT, line.x + measure.width(line.text, line.size, line.bold), 0.01f)
    }

    // --- cancelled ------------------------------------------------------------------------------

    @Test
    fun `a cancelled quotation is stamped across every page and marked under the title`() {
        val pages = lay(document(items = 80, cancelled = true))
        for (page in pages) {
            val stamp = page.ops.first()
            assertTrue("drawn first, under the content", stamp is DrawOp.Stamp)
            assertEquals(QuotationDocument.CANCELLED, (stamp as DrawOp.Stamp).text)
        }
        val mark = pages[0].ops.texts().single { it.text == QuotationDocument.CANCELLED }
        assertEquals(Tone.ALERT, mark.tone)
        assertTrue(mark.y > pages.find(QuotationDocument.TITLE).second)
    }

    @Test
    fun `a quotation that is not cancelled carries no stamp and no mark`() {
        val pages = lay(document(items = 80))
        assertTrue(pages.all { page -> page.ops.none { it is DrawOp.Stamp } })
        assertFalse(pages.drawn(QuotationDocument.CANCELLED))
    }

    // --- images ----------------------------------------------------------------------------------

    @Test
    fun `the images are drawn fitted to their boxes, keeping their proportions`() {
        val ops = lay().single().ops.filterIsInstance<DrawOp.Image>().associateBy { it.role }
        assertEquals(96f to 48f, ops.getValue(ImageRole.LOGO).let { it.w to it.h })
        assertEquals(96f to 96f, ops.getValue(ImageRole.QR).let { it.w to it.h })
        assertEquals(150f to 50f, ops.getValue(ImageRole.SIGNATURE).let { it.w to it.h })

        val wide = lay(images = mapOf(ImageRole.LOGO to 10f, ImageRole.SIGNATURE to 1f)).single().ops
            .filterIsInstance<DrawOp.Image>().associateBy { it.role }
        assertEquals(160f to 16f, wide.getValue(ImageRole.LOGO).let { it.w to it.h })
        assertEquals(50f to 50f, wide.getValue(ImageRole.SIGNATURE).let { it.w to it.h })
    }

    @Test
    fun `an image that did not decode is not drawn - no SCAN TO PAY, and the signatory's words alone`() {
        val pages = lay(images = emptyMap())
        assertTrue(pages.all { page -> page.ops.none { it is DrawOp.Image } })
        assertFalse(pages.drawn(QuotationDocument.SCAN_TO_PAY))
        assertFalse(pages.drawn(QuotationDocument.UPI_APPS))
        assertTrue(pages.drawn("Authorised signatory for Test Gates & Shutters"))
        assertTrue("the bank block stands alone", pages.drawn(QuotationDocument.BANK_DETAILS))
    }

    @Test
    fun `an image the document does not hold is not drawn, whatever decoded`() {
        val pages = lay(document(qr = "", signature = ""))
        val roles = pages.flatMap { page -> page.ops.filterIsInstance<DrawOp.Image>().map { it.role } }
        assertEquals(listOf(ImageRole.LOGO), roles)
        assertFalse(pages.drawn(QuotationDocument.SCAN_TO_PAY))
    }

    // --- empty sections --------------------------------------------------------------------------

    @Test
    fun `an empty section draws no heading`() {
        val pages = lay(document(notes = emptyList(), terms = emptyList(), bank = emptyList(), qr = ""))
        assertFalse(pages.drawn(QuotationDocument.NOTES))
        assertFalse(pages.drawn(QuotationDocument.TERMS))
        assertFalse(pages.drawn(QuotationDocument.BANK_DETAILS))
        assertFalse(pages.drawn(QuotationDocument.SCAN_TO_PAY))
    }

    @Test
    fun `notes and terms sit side by side when both are there, and one alone takes the full width`() {
        val both = lay().single().ops.texts()
        assertEquals(MARGIN, both.single { it.text == QuotationDocument.NOTES }.x, 0.01f)
        assertTrue(both.single { it.text == QuotationDocument.TERMS }.x > MARGIN + CONTENT_WIDTH / 2 - 1f)

        val termsOnly = lay(document(notes = emptyList())).single().ops.texts()
        assertEquals(MARGIN, termsOnly.single { it.text == QuotationDocument.TERMS }.x, 0.01f)

        // 80 characters: one line across the full width, two in half of it.
        val long = "Synthetic term long enough to need more than half the page width to print, test."
        assertTrue(lay(document(notes = emptyList(), terms = listOf(long))).single().ops.texts().any { it.text == long })
        assertFalse(lay(document(terms = listOf(long))).single().ops.texts().any { it.text == long })
    }

    @Test
    fun `a QR with no bank details still prints, at the right`() {
        val pages = lay(document(bank = emptyList()))
        assertFalse(pages.drawn(QuotationDocument.BANK_DETAILS))
        assertTrue(pages.drawn(QuotationDocument.SCAN_TO_PAY))
    }

    // --- the long cases -------------------------------------------------------------------------

    @Test
    fun `an item taller than a page is split between its lines, and every word is printed`() {
        val words = (1..900).map { "w$it" }
        val pages = lay(document(rows = listOf(item(1, description = words.joinToString(" ")))))
        val drawn = pages.flatMap { page -> page.ops.texts().flatMap { it.text.split(' ') } }.toSet()
        assertTrue(pages.size >= 2)
        assertEquals(emptyList<String>(), words.filter { it !in drawn })
    }

    @Test
    fun `long terms run onto the next page, the notes beside them, nothing lost`() {
        val terms = (1..120).map { "Synthetic term number $it, long enough to wrap onto a second line here." }
        val pages = lay(document(terms = terms))
        assertTrue(pages.size >= 2)
        for (term in listOf(1, 60, 120)) {
            assertTrue(pages.any { page -> page.ops.texts().any { it.text.contains("number $term,") } })
        }
    }

    // --- the parts -------------------------------------------------------------------------------

    @Test
    fun `wrap breaks at spaces, inside a word only when it must, and drops nothing`() {
        // 10 characters at size 2 is 10 points wide.
        assertEquals(listOf("aaaa bbbb", "cc"), QuotationLayout.wrap("aaaa bbbb cc", 10f, 2f, false, measure))
        assertEquals(listOf("aaaaaaaaaa", "aaa"), QuotationLayout.wrap("aaaaaaaaaaaaa", 10f, 2f, false, measure))
        assertEquals(listOf("one", "two"), QuotationLayout.wrap("one\ntwo", 10f, 2f, false, measure))
        assertEquals(listOf("a b"), QuotationLayout.wrap("  a    b  ", 10f, 2f, false, measure))
        assertEquals(emptyList<String>(), QuotationLayout.wrap("   ", 10f, 2f, false, measure))
    }

    @Test
    fun `fit keeps the proportions inside the box`() {
        assertEquals(96f to 48f, QuotationLayout.fit(2f, 160f, 48f))
        assertEquals(160f to 16f, QuotationLayout.fit(10f, 160f, 48f))
        assertEquals(96f to 96f, QuotationLayout.fit(1f, 96f, 96f))
        assertEquals(48f to 96f, QuotationLayout.fit(0.5f, 96f, 96f))
    }
}
