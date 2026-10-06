package `in`.smartie.quotedesk.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The PDF's file name, exactly as V8C4 builds it (the Owner's review of
 * 2026-10-06): `clean(s) = s.replace(/[^A-Za-z0-9]+/g, "-")` with a leading
 * and a trailing "-" stripped; `no = clean(number)` or "Draft";
 * `client = clean(name)` cut to 36 characters, or "Client". Synthetic names
 * only.
 */
class QuotationFileNameTest {

    @Test
    fun `number and client, a run of other characters collapsed to one dash`() {
        assertEquals(
            "Quotation-SQ-2026-27-0042-Test-Builders.pdf",
            QuotationFileName.of("SQ/2026-27/0042", "Test Builders")
        )
        // The Owner's own example.
        assertEquals("Quotation-Q7-M-s-A-B.pdf", QuotationFileName.of("Q7", "M/s. A & B"))
    }

    @Test
    fun `a leading and a trailing dash are stripped from each part`() {
        assertEquals("Quotation-Q1-Test-Co.pdf", QuotationFileName.of(" /Q1/ ", "  (Test) Co.  "))
        assertEquals("Quotation-Q1-A.pdf", QuotationFileName.of("Q1", "-A-"))
    }

    @Test
    fun `the client part stops at 36 characters`() {
        val long = "Abcdefghij".repeat(5)
        val name = QuotationFileName.of("Q1", long)
        assertEquals("Quotation-Q1-${long.take(36)}.pdf", name)
        assertEquals(36, name.removePrefix("Quotation-Q1-").removeSuffix(".pdf").length)
    }

    @Test
    fun `the cut comes after the clean, as V8C4's does`() {
        // Cleaned first, "A" + ten spaces + forty "B"s is "A-" and forty "B"s,
        // cut to 36. Cut first, it would be "A-" and only 25 "B"s.
        val name = QuotationFileName.of("Q1", "A" + " ".repeat(10) + "B".repeat(40))
        assertEquals("Quotation-Q1-A-${"B".repeat(34)}.pdf", name)
        // The cut can end on a dash, as V8C4's slice can: it strips before it cuts.
        val dashAt36 = "Abcdefghij".repeat(3) + "Abcde" + " Xyz"
        assertEquals("Quotation-Q1-${"Abcdefghij".repeat(3)}Abcde-.pdf", QuotationFileName.of("Q1", dashAt36))
    }

    @Test
    fun `Draft and Client when either comes out empty`() {
        assertEquals("Quotation-Draft-Test-Builders.pdf", QuotationFileName.of("", "Test Builders"))
        assertEquals("Quotation-Q1-Client.pdf", QuotationFileName.of("Q1", "   "))
        assertEquals("Quotation-Draft-Client.pdf", QuotationFileName.of(" ", ""))
        // Nothing but other characters cleans to nothing.
        assertEquals("Quotation-Draft-Client.pdf", QuotationFileName.of("//", "& - &"))
    }

    @Test
    fun `letters outside ASCII are not alphanumeric here`() {
        assertEquals("Quotation-Q1-Caf-Test.pdf", QuotationFileName.of("Q1", "Café Test"))
        assertEquals("Quotation-Q1-Caf.pdf", QuotationFileName.of("Q1", "Café"))
    }

    @Test
    fun `the print job is the file name without its extension`() {
        assertEquals("Quotation-Q1-Test", QuotationFileName.jobName("Q1", "Test"))
    }
}
