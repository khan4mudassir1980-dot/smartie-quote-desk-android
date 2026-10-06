package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.model.CompanySettings
import `in`.smartie.quotedesk.data.model.ProductRecord
import `in`.smartie.quotedesk.data.model.QuotationLineRecord
import `in`.smartie.quotedesk.data.model.QuotationPartySnapshot
import `in`.smartie.quotedesk.data.model.QuotationRecord
import java.io.IOException
import java.util.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** One PDF from an issued quotation (N5.11 commit 9). Synthetic only. */
class QuotationPdfMakerTest {

    private val complete = CompanySettings(
        name = "Test Gates & Shutters", address = "1 Test Road", phone = "+91 90000 00001",
        email = "quotes@example.invalid", gstin = "27AAAAA0000A1Z5", pan = "AAAAA0000A",
        bankName = "Test Bank", bankAcc = "000000000000", bankIfsc = "TEST0000000"
    )

    private val record = QuotationRecord(
        id = "q_test",
        number = "TEST/QD/2026-27/001",
        at = 1_791_051_600_000L,
        serverAt = 1_791_176_400_000L,
        party = QuotationPartySnapshot(name = "Test Customer"),
        lines = listOf(QuotationLineRecord(title = "Test item", key = "g|T-1", quantity = 1.0, rate = 100.0, amount = 100.0)),
        subtotal = 100.0,
        total = 100.0
    )

    private val motor = ProductRecord(
        documentId = "p", key = "g|T-1", group = "g", seedModel = "T-1", model = "T-1", name = "Test motor"
    )

    private val rendered = mutableListOf<QuotationDocument>()
    private var unreadable = emptyList<ImageRole>()
    private var renderFailure: Throwable? = null
    private val logged = mutableListOf<Throwable>()

    private val maker = QuotationPdfMaker(
        render = { document ->
            renderFailure?.let { throw it }
            rendered += document
            Rendered("file:${document.fileName}", unreadable)
        },
        describe = { it.message ?: "unknown" },
        log = { logged += it },
        zone = { TimeZone.getTimeZone("Asia/Kolkata") }
    )

    private fun make(company: CompanyState = CompanyState.Loaded(complete)) = runBlocking {
        maker.make(record, company, listOf(motor))
    }

    @Test
    fun `settings that have never loaded refuse, and nothing is drawn`() {
        assertEquals(PdfNotice.NOT_LOADED, QuotationPdfMaker.ready(CompanyState.NotLoaded))
        assertEquals(PdfOutcome.Refused(PdfNotice.NOT_LOADED), make(CompanyState.NotLoaded))
        assertTrue(rendered.isEmpty())
    }

    @Test
    fun `settings that loaded with no document at all still print, everything named`() {
        assertNull(QuotationPdfMaker.ready(CompanyState.Loaded(null)))
        val ready = make(CompanyState.Loaded(null)) as PdfOutcome.Ready
        assertEquals(1, rendered.size)
        assertEquals(
            "Made without the firm name, address, phone, email, GSTIN, PAN and bank details — " +
                "they are not in company settings.",
            ready.notice
        )
    }

    @Test
    fun `a complete PDF is ready with no notice, its number and its file name`() {
        val ready = make() as PdfOutcome.Ready
        assertEquals("file:Quotation-TEST-QD-2026-27-001-Test-Customer.pdf", ready.output)
        assertEquals("TEST/QD/2026-27/001", ready.number)
        assertEquals("Quotation-TEST-QD-2026-27-001-Test-Customer.pdf", ready.fileName)
        assertNull(ready.notice)
    }

    @Test
    fun `the document is built from the record, the settings and the catalogue`() {
        make()
        val document = rendered.single()
        assertEquals("Test Gates & Shutters", document.letterhead.name)
        // The server's issue time, in the phone's zone.
        assertEquals("5 Oct 2026", document.details.single { it.label == QuotationDocumentBuilder.DATE }.value)
        assertEquals("T-1" to "Test motor", document.items.single().let { it.model to it.product })
    }

    @Test
    fun `missing details and unreadable images are both in the notice`() {
        unreadable = listOf(ImageRole.LOGO)
        val ready = make(CompanyState.Loaded(complete.copy(gstin = ""))) as PdfOutcome.Ready
        assertEquals(
            "Made without the GSTIN — it is not in company settings. The logo could not be read and was left out.",
            ready.notice
        )
    }

    @Test
    fun `a failed render is reported in words and logged, never thrown`() {
        renderFailure = IOException("No space left on the phone")
        assertEquals(PdfOutcome.Refused("The PDF was not made — No space left on the phone."), make())
        assertEquals(1, logged.size)
    }

    @Test
    fun `a cancelled render is not swallowed`() {
        renderFailure = CancellationException("left the screen")
        assertThrows(CancellationException::class.java) { make() }
        assertTrue(logged.isEmpty())
    }
}
