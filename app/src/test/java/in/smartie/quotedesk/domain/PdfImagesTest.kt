package `in`.smartie.quotedesk.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The PDF images' rules (N5.11 commit 7). Synthetic values only. */
class PdfImagesTest {

    @Test
    fun `a base64 data URL gives its payload, whitespace removed`() {
        assertEquals("iVBORw0KGgo=", PdfImages.payload("data:image/png;base64,iVBORw0KGgo="))
        assertEquals("iVBORw0KGgo=", PdfImages.payload("  data:image/png;base64,iVBO\nRw0K Ggo=  "))
        assertEquals("AAAA", PdfImages.payload("DATA:IMAGE/JPEG;BASE64,AAAA"))
        assertEquals("AAAA", PdfImages.payload("data:image/svg+xml;base64,AAAA"))
    }

    @Test
    fun `anything that is not an image data URL is not read - a web address is never fetched`() {
        assertNull(PdfImages.payload("https://example.invalid/logo.png"))
        assertNull(PdfImages.payload("http://example.invalid/logo.png"))
        assertNull(PdfImages.payload("data:text/plain;base64,AAAA"))
        assertNull(PdfImages.payload("data:image/png,not-base64"))
        assertNull(PdfImages.payload("data:image/png;base64,"))
        assertNull(PdfImages.payload(""))
    }

    @Test
    fun `a stored image past the size bound is not decoded`() {
        // 4 base64 characters are 3 bytes.
        val atTheBound = "A".repeat(PdfImages.MAX_BYTES / 3 * 4)
        assertEquals(atTheBound, PdfImages.payload("data:image/png;base64,$atTheBound"))
        val past = "A".repeat((PdfImages.MAX_BYTES / 3 + 1) * 4)
        assertNull(PdfImages.payload("data:image/png;base64,$past"))
    }

    @Test
    fun `each image's longest edge - logo and QR 512, the signature 600`() {
        assertEquals(512, PdfImages.edge(ImageRole.LOGO))
        assertEquals(512, PdfImages.edge(ImageRole.QR))
        assertEquals(600, PdfImages.edge(ImageRole.SIGNATURE))
    }

    @Test
    fun `the images a document holds, the blank ones left out`() {
        val doc = QuotationDocument(
            letterhead = LetterheadBlock(logo = "data:image/png;base64,TE9HTw==", name = "", lines = emptyList()),
            cancelled = false, billTo = emptyList(), details = emptyList(), items = emptyList(),
            totals = emptyList(), amountInWords = "", conditions = emptyList(), notes = emptyList(),
            terms = emptyList(), bank = emptyList(), qr = " ", signature = "data:image/png;base64,U0lH",
            signatory = "", acceptance = "", footer = "", fileName = "", missing = emptyList()
        )
        assertEquals(
            mapOf(ImageRole.LOGO to "data:image/png;base64,TE9HTw==", ImageRole.SIGNATURE to "data:image/png;base64,U0lH"),
            PdfImages.sources(doc)
        )
    }

    @Test
    fun `the notice's names for the images`() {
        assertEquals(listOf("logo", "QR code", "signature"), ImageRole.entries.map { PdfImages.name(it) })
    }
}
