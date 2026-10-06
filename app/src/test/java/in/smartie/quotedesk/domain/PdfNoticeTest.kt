package `in`.smartie.quotedesk.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the person is told after a PDF (N5.11 commit 9). */
class PdfNoticeTest {

    @Test
    fun `a complete PDF says nothing`() {
        assertNull(PdfNotice.after(emptyList(), emptyList()))
    }

    @Test
    fun `what the letterhead was made without is named`() {
        assertEquals(
            "Made without the GSTIN — it is not in company settings.",
            PdfNotice.after(listOf(Letterhead.GSTIN), emptyList())
        )
        assertEquals(
            "Made without the GSTIN and bank details — they are not in company settings.",
            PdfNotice.after(listOf(Letterhead.GSTIN, Letterhead.BANK_DETAILS), emptyList())
        )
        assertEquals(
            "Made without the firm name, address and phone — they are not in company settings.",
            PdfNotice.after(listOf(Letterhead.FIRM_NAME, Letterhead.ADDRESS, Letterhead.PHONE), emptyList())
        )
    }

    @Test
    fun `an image that could not be read is named apart`() {
        assertEquals(
            "The logo could not be read and was left out.",
            PdfNotice.after(emptyList(), listOf(ImageRole.LOGO))
        )
        assertEquals(
            "The QR code and signature could not be read and were left out.",
            PdfNotice.after(emptyList(), listOf(ImageRole.QR, ImageRole.SIGNATURE))
        )
    }

    @Test
    fun `both together, missing first`() {
        assertEquals(
            "Made without the PAN — it is not in company settings. The logo could not be read and was left out.",
            PdfNotice.after(listOf(Letterhead.PAN), listOf(ImageRole.LOGO))
        )
    }

    @Test
    fun `the refusal when settings have never loaded, in the approved plan's words`() {
        assertEquals("Company details have not loaded yet — connect and try again", PdfNotice.NOT_LOADED)
    }
}
