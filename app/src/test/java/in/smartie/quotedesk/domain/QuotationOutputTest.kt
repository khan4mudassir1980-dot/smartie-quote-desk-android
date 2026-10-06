package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.domain.QuotationOutput.ShareTarget
import org.junit.Assert.assertEquals
import org.junit.Test

/** Where the PDF goes (N5.11 commit 8). */
class QuotationOutputTest {

    @Test
    fun `only the file in use is kept in the cache`() {
        // The Owner's addition of 2026-10-06: the cache holds customers' quotations.
        assertEquals(
            listOf("Quotation-A-One.pdf", "Quotation-B-Two.pdf"),
            QuotationOutput.stale(
                listOf("Quotation-A-One.pdf", "Quotation-C-Three.pdf", "Quotation-B-Two.pdf"),
                keep = "Quotation-C-Three.pdf"
            )
        )
        assertEquals(emptyList<String>(), QuotationOutput.stale(listOf("Quotation-C-Three.pdf"), "Quotation-C-Three.pdf"))
        assertEquals(emptyList<String>(), QuotationOutput.stale(emptyList(), "Quotation-C-Three.pdf"))
    }

    @Test
    fun `one WhatsApp goes straight to it, both ask, neither opens the share sheet`() {
        assertEquals(ShareTarget.App(QuotationOutput.WHATSAPP), QuotationOutput.shareTarget(setOf(QuotationOutput.WHATSAPP)))
        assertEquals(
            ShareTarget.App(QuotationOutput.WHATSAPP_BUSINESS),
            QuotationOutput.shareTarget(setOf(QuotationOutput.WHATSAPP_BUSINESS))
        )
        assertEquals(
            ShareTarget.Choose,
            QuotationOutput.shareTarget(setOf(QuotationOutput.WHATSAPP, QuotationOutput.WHATSAPP_BUSINESS))
        )
        assertEquals(ShareTarget.Sheet, QuotationOutput.shareTarget(emptySet()))
        assertEquals(ShareTarget.Sheet, QuotationOutput.shareTarget(setOf("com.example.other")))
    }

    @Test
    fun `the packages, the type and the folder`() {
        assertEquals("com.whatsapp", QuotationOutput.WHATSAPP)
        assertEquals("com.whatsapp.w4b", QuotationOutput.WHATSAPP_BUSINESS)
        assertEquals("application/pdf", QuotationOutput.MIME)
        assertEquals("quotations", QuotationOutput.CACHE_DIRECTORY)
        assertEquals("SMARTIE", QuotationOutput.DOWNLOAD_FOLDER)
    }
}
