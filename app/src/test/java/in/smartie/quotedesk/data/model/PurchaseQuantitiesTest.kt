package `in`.smartie.quotedesk.data.model

import `in`.smartie.quotedesk.data.mapping.DocData
import `in`.smartie.quotedesk.data.mapping.toPurchaseRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a requirement's quantities mean once part of it has arrived.
 *
 * `qty` is the total required and `rcvQty` is the **cumulative** quantity
 * received, so everything the shop floor reads — how many are still to come,
 * whether it is finished — is arithmetic on those two. It is arithmetic that
 * can be wrong in two ways that matter, and both are pinned here: a missing
 * `rcvQty` must not read as a debt, and a row somebody has marked received
 * must never be reopened by a sum.
 */
class PurchaseQuantitiesTest {

    private fun requirement(
        quantity: Double = 10.0,
        receivedQuantity: Double? = null,
        received: Boolean = false,
        status: String = "Needed"
    ) = PurchaseRecord(
        id = "pr_one",
        name = "Sliding gate rack",
        quantity = quantity,
        receivedQuantity = receivedQuantity,
        received = received,
        status = status
    )

    @Test
    fun `nothing received reads as none, never as zero of zero`() {
        val fresh = requirement()
        assertEquals(0.0, fresh.receivedTotal, 0.0)
        assertEquals(10.0, fresh.remaining, 0.0)
        assertFalse(fresh.isFullyReceived)
        assertFalse(fresh.isPartlyReceived)
    }

    @Test
    fun `five of ten is partly received and still open`() {
        val partly = requirement(receivedQuantity = 5.0)
        assertEquals(5.0, partly.receivedTotal, 0.0)
        assertEquals(5.0, partly.remaining, 0.0)
        assertTrue(partly.isPartlyReceived)
        assertFalse(partly.isFullyReceived)
        assertTrue("a part delivery must not close anything", partly.isOpen)
    }

    @Test
    fun `the whole lot is fully received`() {
        val whole = requirement(receivedQuantity = 10.0)
        assertTrue(whole.isFullyReceived)
        assertFalse(whole.isPartlyReceived)
        assertEquals(0.0, whole.remaining, 0.0)
    }

    @Test
    fun `remaining never goes below zero, whatever a legacy row holds`() {
        // The PWA overwrites `rcvQty`, so a stored figure above `qty` is not
        // impossible — and "minus two still to come" is not a sentence.
        assertEquals(0.0, requirement(receivedQuantity = 12.0).remaining, 0.0)
    }

    @Test
    fun `a nonsense stored quantity reads as none rather than as a debt`() {
        assertEquals(0.0, requirement(receivedQuantity = -4.0).receivedTotal, 0.0)
        assertEquals(0.0, requirement(receivedQuantity = Double.NaN).receivedTotal, 0.0)
        assertEquals(10.0, requirement(receivedQuantity = -4.0).remaining, 0.0)
    }

    @Test
    fun `a row with no usable total is never fully received`() {
        // Every write to such a row is refused until an edit gives it a
        // quantity, so calling it finished would be a card nobody can act on.
        assertFalse(requirement(quantity = 0.0, receivedQuantity = 3.0).isFullyReceived)
    }

    @Test
    fun `a legacy closed row stays closed although its total is short`() {
        val legacy = requirement(receivedQuantity = 4.0, received = true, status = "Received")
        assertTrue(legacy.isClosed)
        assertFalse(legacy.isOpen)
        // Partly received by arithmetic, and closed by what somebody recorded.
        // The status is what counts; the sum is only what is shown.
        assertTrue(legacy.isPartlyReceived)
    }

    @Test
    fun `a legacy string quantity is already a number by the time it gets here`() {
        // `asDoubleOrNull` coerces on the way in, so both fields read as
        // numbers whether the PWA wrote "10" or 10.
        val coerced = DocData(
            id = "pr_legacy",
            fields = mapOf("qty" to "10", "rcvQty" to "4", "status" to "Needed")
        ).toPurchaseRecord()

        assertEquals(10.0, coerced.quantity, 0.0)
        assertEquals(4.0, coerced.receivedTotal, 0.0)
        assertEquals(6.0, coerced.remaining, 0.0)
        assertTrue(coerced.isPartlyReceived)
    }

    @Test
    fun `quantities within a rounding step of each other count as the same`() {
        val awkward = requirement(quantity = 0.3, receivedQuantity = 0.1 + 0.1 + 0.1)
        assertTrue("three tenths make three tenths", awkward.isFullyReceived)
        assertEquals(0.0, awkward.remaining, PurchaseRecord.QUANTITY_TOLERANCE)
    }
}
