package `in`.smartie.quotedesk.ui

import `in`.smartie.quotedesk.data.model.QuotationRecord
import `in`.smartie.quotedesk.ui.products.QuoteRequest
import `in`.smartie.quotedesk.ui.products.QuoteRequests
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hand-off from the Quotations tab to the builder (N5.10 commit 6): one
 * slot, taken once, so an edit is never opened twice.
 */
class QuoteRequestsTest {

    private val nine = QuoteRequest.Edit(QuotationRecord(id = "qd_9", number = "SIE/QD/2025-26/009"), requestedBy = "u_m")
    private val ten = QuoteRequest.Edit(QuotationRecord(id = "qd_10", number = "SIE/QD/2025-26/010"), requestedBy = "u_m")

    @Test
    fun `a request is taken exactly once`() {
        val requests = QuoteRequests()
        requests.request(nine)

        assertTrue(requests.take(nine))
        assertFalse("taken twice", requests.take(nine))
        assertNull(requests.pending.value)
    }

    @Test
    fun `a newer request replaces one not yet taken, and the older can no longer be taken`() {
        val requests = QuoteRequests()
        requests.request(nine)
        requests.request(ten)

        assertEquals(ten, requests.pending.value)
        assertFalse(requests.take(nine))
        assertTrue(requests.take(ten))
    }

    @Test
    fun `nothing asked, nothing taken`() {
        assertFalse(QuoteRequests().take(nine))
    }

    @Test
    fun `the same quotation asked for again after it was taken is taken again`() {
        val requests = QuoteRequests()
        requests.request(nine)
        requests.take(nine)
        requests.request(nine)

        assertTrue(requests.take(nine))
    }
}
