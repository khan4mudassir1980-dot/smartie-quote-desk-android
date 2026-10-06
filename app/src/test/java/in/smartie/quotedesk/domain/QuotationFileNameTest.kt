package `in`.smartie.quotedesk.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The PDF's file name (N5.11 commit 1), V8C4's rule: `Quotation-<no>-<client>.pdf`,
 * every non-alphanumeric a "-", the client part at most 36 characters, and
 * "Draft" and "Client" when either is missing. Synthetic names only.
 */
class QuotationFileNameTest {

    @Test
    fun `number and client, cleaned character by character`() {
        assertEquals(
            "Quotation-SQ-2026-27-0042-Test-Builders.pdf",
            QuotationFileName.of("SQ/2026-27/0042", "Test Builders")
        )
        assertEquals("Quotation-Q7-M-s--A---B.pdf", QuotationFileName.of("Q7", "M/s. A & B"))
    }

    @Test
    fun `the client part stops at 36 characters`() {
        val long = "Abcdefghij".repeat(5)
        val name = QuotationFileName.of("Q1", long)
        assertEquals("Quotation-Q1-${long.take(36)}.pdf", name)
        assertEquals(36, name.removePrefix("Quotation-Q1-").removeSuffix(".pdf").length)
    }

    @Test
    fun `Draft and Client when either is missing`() {
        assertEquals("Quotation-Draft-Test-Builders.pdf", QuotationFileName.of("", "Test Builders"))
        assertEquals("Quotation-Q1-Client.pdf", QuotationFileName.of("Q1", "   "))
        assertEquals("Quotation-Draft-Client.pdf", QuotationFileName.of(" ", ""))
    }

    @Test
    fun `letters outside ASCII are not alphanumeric here`() {
        assertEquals("Quotation-Q1-Caf--Test.pdf", QuotationFileName.of("Q1", "Café Test"))
    }

    @Test
    fun `the print job is the file name without its extension`() {
        assertEquals("Quotation-Q1-Test", QuotationFileName.jobName("Q1", "Test"))
    }
}
