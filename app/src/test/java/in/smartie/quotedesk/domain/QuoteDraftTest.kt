package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.mapping.Keys
import `in`.smartie.quotedesk.data.model.ProductRecord
import `in`.smartie.quotedesk.data.model.RateTierV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuoteDraftTest {

    private fun product(
        seedModel: String,
        dealer: Double? = 100.0,
        contractor: Double? = 120.0,
        client: Double? = 140.0,
    ) = ProductRecord(
        documentId = Keys.productDocId("gate", seedModel),
        key = Keys.productKey("gate", seedModel),
        group = "gate",
        seedModel = seedModel,
        model = seedModel,
        dealer = dealer,
        contractor = contractor,
        client = client,
    )

    private val motor = product("SIE1000")
    private val unpriced = product("SIE600", dealer = null, contractor = null, client = null)

    @Test
    fun `adding a product takes the rate of the current tier`() {
        val draft = QuoteDraft(tier = RateTierV2.DEALER).add(motor)
        assertEquals(1, draft.lineCount)
        assertEquals(100.0, draft.lines.single().rate!!, 0.0)
        assertEquals(100.0, draft.total, 0.0)
    }

    @Test
    fun `adding the same product again raises its quantity`() {
        val draft = QuoteDraft().add(motor).add(motor).add(motor)
        assertEquals(1, draft.lineCount)
        assertEquals(3.0, draft.quantityOf(motor.key), 0.0)
        assertEquals(420.0, draft.total, 0.0)
    }

    @Test
    fun `setting a quantity to zero removes the line`() {
        val draft = QuoteDraft().add(motor).setQuantity(motor.key, 0.0)
        assertTrue(draft.isEmpty)
        assertEquals(0.0, draft.total, 0.0)
    }

    @Test
    fun `a negative quantity is refused and changes nothing`() {
        val draft = QuoteDraft().add(motor, quantity = 2.0)
        val refused = draft.setQuantity(motor.key, -1.0)
        assertEquals(2.0, refused.quantityOf(motor.key), 0.0)
        assertEquals(draft, refused)
        assertFalse(QuoteDraft.isValidQuantity(-0.5))
        assertTrue(QuoteDraft.isValidQuantity(0.0))
    }

    @Test
    fun `the stepper cannot take a line below zero`() {
        val draft = QuoteDraft().add(motor).changeQuantity(motor.key, -1.0)
        assertTrue(draft.isEmpty)
        assertEquals(QuoteDraft(), QuoteDraft().changeQuantity(motor.key, -1.0))
    }

    @Test
    fun `an unpriced product joins the quotation without becoming zero`() {
        val draft = QuoteDraft().add(unpriced)
        val line = draft.lines.single()
        assertNull(line.rate)
        assertNull(line.amount)
        assertTrue(line.needsRate)
        assertEquals(1, draft.needsRateCount)
        // The bar shows the priced part of the total; nothing pretends the
        // unpriced line is free.
        assertEquals(0.0, draft.total, 0.0)
        assertNull(line.toRecord())
    }

    @Test
    fun `a priced line converts to a quotation line with its amount`() {
        val record = QuoteDraft().add(motor, quantity = 2.0).lines.single().toRecord()!!
        assertEquals("SIE1000", record.title)
        assertEquals(140.0, record.rate, 0.0)
        assertEquals(280.0, record.amount, 0.0)
        assertEquals(motor.key, record.key)
        assertFalse(record.manual)
    }

    @Test
    fun `changing tier reprices the lines nobody edited`() {
        val draft = QuoteDraft(tier = RateTierV2.CLIENT).add(motor)
        val outcome = draft.withTier(RateTierV2.DEALER) { key ->
            if (key == motor.key) motor.dealer else null
        }
        assertEquals(1, outcome.repriced)
        assertEquals(0, outcome.kept)
        assertEquals(100.0, outcome.draft.lines.single().rate!!, 0.0)
        assertEquals(RateTierV2.DEALER, outcome.draft.tier)
    }

    @Test
    fun `a rate typed by hand survives a change of tier`() {
        val draft = QuoteDraft(tier = RateTierV2.CLIENT).add(motor).setRate(motor.key, 999.0)
        val outcome = draft.withTier(RateTierV2.DEALER) { motor.dealer }
        assertEquals(0, outcome.repriced)
        assertEquals(1, outcome.kept)
        assertEquals(999.0, outcome.draft.lines.single().rate!!, 0.0)
    }

    @Test
    fun `switching to the tier already in use changes nothing`() {
        val draft = QuoteDraft(tier = RateTierV2.CLIENT).add(motor)
        val outcome = draft.withTier(RateTierV2.CLIENT) { 1.0 }
        assertEquals(draft, outcome.draft)
        assertEquals(0, outcome.repriced)
    }

    @Test
    fun `repricing to a tier with no price leaves the line needing a rate`() {
        val onlyClient = product("SIE800", dealer = null, contractor = null, client = 500.0)
        val draft = QuoteDraft(tier = RateTierV2.CLIENT).add(onlyClient)
        val outcome = draft.withTier(RateTierV2.DEALER) { onlyClient.dealer }
        assertTrue(outcome.draft.lines.single().needsRate)
        assertEquals(1, outcome.draft.needsRateCount)
    }

    @Test
    fun `clearing empties the draft but keeps the tier`() {
        val draft = QuoteDraft(tier = RateTierV2.CONTRACTOR).add(motor).clear()
        assertTrue(draft.isEmpty)
        assertEquals(RateTierV2.CONTRACTOR, draft.tier)
    }
}
