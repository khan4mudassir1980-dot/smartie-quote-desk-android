package `in`.smartie.quotedesk.data.mapping

import `in`.smartie.quotedesk.data.model.CompanySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `teamSettings/company` read with V8C4's own names (N5.11 commit 2).
 * Synthetic values only — no firm's real details.
 */
class CompanyReaderTest {

    private val full = DocData(
        "company",
        mapOf(
            "name" to "  Test Gates & Shutters ",
            "tagline" to "Synthetic test data",
            "address" to "1 Test Road, Test City 400001",
            "phone" to "+91 90000 00001 · +91 90000 00002",
            "email" to "quotes@example.invalid",
            "web" to "example.invalid",
            "gstin" to "27AAAAA0000A1Z5",
            "pan" to "AAAAA0000A",
            "bankName" to "Test Bank",
            "bankBranch" to "Test Branch",
            "bankAcc" to "000000000000",
            "bankIfsc" to "TEST0000000",
            "upi" to "test-only@invalid",
            "validityDays" to 15L,
            "payTerms" to "50% advance (test)",
            "warranty" to "12 months (test)",
            "pdfFooter" to "Synthetic test footer",
            "defaultGst" to 18L,
            "logo" to "data:image/png;base64,AAAA",
            "qr" to "data:image/png;base64,BBBB",
            "signature" to "data:image/png;base64,CCCC",
            "terms" to listOf("Test term one.", "  ", "Test term two."),
            "notes" to listOf("Test note one.")
        )
    )

    @Test
    fun `every field V8C4 stores, by V8C4's name, and the signature`() {
        val read = full.toCompanySettings()
        assertEquals("Test Gates & Shutters", read.name)
        assertEquals("Synthetic test data", read.tagline)
        assertEquals("1 Test Road, Test City 400001", read.address)
        assertEquals("+91 90000 00001 · +91 90000 00002", read.phone)
        assertEquals("quotes@example.invalid", read.email)
        assertEquals("example.invalid", read.web)
        assertEquals("27AAAAA0000A1Z5", read.gstin)
        assertEquals("AAAAA0000A", read.pan)
        assertEquals("Test Bank", read.bankName)
        assertEquals("Test Branch", read.bankBranch)
        assertEquals("000000000000", read.bankAcc)
        assertEquals("TEST0000000", read.bankIfsc)
        assertEquals("test-only@invalid", read.upi)
        assertEquals(15, read.validityDays)
        assertEquals("50% advance (test)", read.payTerms)
        assertEquals("12 months (test)", read.warranty)
        assertEquals("Synthetic test footer", read.pdfFooter)
        assertEquals(18.0, read.defaultGst!!, 0.0)
        assertEquals("data:image/png;base64,AAAA", read.logo)
        assertEquals("data:image/png;base64,BBBB", read.qr)
        assertEquals("data:image/png;base64,CCCC", read.signature)
        assertEquals(listOf("Test term one.", "Test term two."), read.terms)
        assertEquals(listOf("Test note one."), read.notes)
    }

    @Test
    fun `nothing absent is defaulted`() {
        val read = DocData("company").toCompanySettings()
        assertEquals(CompanySettings(), read)
        assertEquals("", read.name)
        assertNull(read.validityDays)
        assertEquals(emptyList<String>(), read.terms)
    }

    @Test
    fun `V8C4's unset marker and a blank are both blank`() {
        val read = DocData("company", mapOf("gstin" to UNSET_MARKER, "pan" to "   ")).toCompanySettings()
        assertEquals("", read.gstin)
        assertEquals("", read.pan)
    }

    @Test
    fun `terms as one string are read a line at a time, blank lines dropped`() {
        val read = DocData("company", mapOf("terms" to "One.\n\n  Two.  \n", "notes" to emptyList<String>()))
            .toCompanySettings()
        assertEquals(listOf("One.", "Two."), read.terms)
        assertEquals(emptyList<String>(), read.notes)
    }

    @Test
    fun `validity days from a numeric string, and never zero or less`() {
        assertEquals(30, DocData("c", mapOf("validityDays" to "30")).toCompanySettings().validityDays)
        assertNull(DocData("c", mapOf("validityDays" to 0L)).toCompanySettings().validityDays)
        assertNull(DocData("c", mapOf("validityDays" to "soon")).toCompanySettings().validityDays)
    }
}
