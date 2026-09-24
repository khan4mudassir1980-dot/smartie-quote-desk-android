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
        val draft = QuoteDraft().add(motor).setCatalogueQuantity(motor.key, 0.0)
        assertTrue(draft.isEmpty)
        assertEquals(0.0, draft.total, 0.0)
    }

    @Test
    fun `a negative quantity is refused and changes nothing`() {
        val draft = QuoteDraft().add(motor, quantity = 2.0)
        val refused = draft.setCatalogueQuantity(motor.key, -1.0)
        assertEquals(2.0, refused.quantityOf(motor.key), 0.0)
        assertEquals(draft, refused)
        assertFalse(QuoteDraft.isValidQuantity(-0.5))
        assertTrue(QuoteDraft.isValidQuantity(0.0))
    }

    @Test
    fun `the stepper cannot take a line below zero`() {
        val draft = QuoteDraft().add(motor).changeCatalogueQuantity(motor.key, -1.0)
        assertTrue(draft.isEmpty)
        assertEquals(QuoteDraft(), QuoteDraft().changeCatalogueQuantity(motor.key, -1.0))
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
        val added = QuoteDraft(tier = RateTierV2.CLIENT).add(motor)
        val draft = added.setRate(added.lines.single().id, 999.0)
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

    // --- a line is identified by itself, not by its product ----------------------

    @Test
    fun `two hand-typed lines coexist, where keying by product allowed only one`() {
        // Both have no product key. Keyed by product, the second would have
        // driven the first and `remove` would have deleted both.
        val draft = QuoteDraft()
            .addManual(id = "ln_1", title = "Site visit", rate = 2000.0)
            .addManual(id = "ln_2", title = "Crane hire", rate = 8000.0)

        assertEquals(2, draft.lineCount)
        assertEquals(10000.0, draft.total, 0.0)
        assertEquals("Site visit", draft.line("ln_1")!!.title)
        assertEquals("Crane hire", draft.line("ln_2")!!.title)
    }

    @Test
    fun `two openings of the same product are two lines, not one merged figure`() {
        // The defect that would have shipped: a quotation for two different
        // shutter openings of one product silently became a single line with
        // the wrong area.
        val wide = AreaLine(width = 3000.0, height = 3500.0)
        val narrow = AreaLine(width = 1200.0, height = 2100.0)
        val draft = QuoteDraft()
            .addArea(id = "ln_w", area = wide, rate = 450.0, title = "Shutter", key = motor.key)
            .addArea(id = "ln_n", area = narrow, rate = 450.0, title = "Shutter", key = motor.key)

        assertEquals(2, draft.lineCount)
        assertEquals(113.5, draft.line("ln_w")!!.quantity, 0.0)
        assertEquals(27.5, draft.line("ln_n")!!.quantity, 0.0)
    }

    @Test
    fun `removing one line leaves the other, where keying by product removed both`() {
        val draft = QuoteDraft()
            .addManual(id = "ln_1", title = "Site visit", rate = 2000.0)
            .addManual(id = "ln_2", title = "Crane hire", rate = 8000.0)
            .remove("ln_1")

        assertEquals(1, draft.lineCount)
        assertEquals("Crane hire", draft.lines.single().title)
    }

    @Test
    fun `a catalogue line still merges, because two taps mean two of the thing`() {
        // The one place merging is right, and it is kept.
        val draft = QuoteDraft().add(motor, id = "ln_1").add(motor, id = "ln_2")
        assertEquals(1, draft.lineCount)
        assertEquals(2.0, draft.quantityOf(motor.key), 0.0)
        assertEquals("ln_1", draft.lines.single().id)
    }

    @Test
    fun `a manual line sharing a product is not driven by the catalogue stepper`() {
        val draft = QuoteDraft()
            .add(motor, id = "ln_cat")
            .addArea(
                id = "ln_area",
                area = AreaLine(width = 3000.0, height = 3500.0),
                rate = 450.0,
                title = "Shutter",
                key = motor.key
            )
            .changeCatalogueQuantity(motor.key, 4.0)

        assertEquals(5.0, draft.line("ln_cat")!!.quantity, 0.0)
        assertEquals(113.5, draft.line("ln_area")!!.quantity, 0.0)
    }

    @Test
    fun `a hand-typed line is never repriced by a change of tier`() {
        // It has no product key, so pricing it from the catalogue would answer
        // null and wipe the rate somebody typed.
        val draft = QuoteDraft(tier = RateTierV2.CLIENT)
            .addManual(id = "ln_m", title = "Site visit", rate = 2000.0)
        val outcome = draft.withTier(RateTierV2.DEALER) { null }

        assertEquals(0, outcome.repriced)
        assertEquals(1, outcome.kept)
        assertEquals(2000.0, outcome.draft.lines.single().rate!!, 0.0)
    }

    @Test
    fun `an area line stores the total chargeable area and prices from it`() {
        val draft = QuoteDraft().addArea(
            id = "ln_x",
            area = AreaLine(width = 3000.0, height = 3500.0, count = 2.0),
            rate = 450.0,
            title = "Shutter",
            key = motor.key
        )
        val line = draft.lines.single()
        assertEquals(227.0, line.quantity, 0.0)
        assertEquals(102150.0, line.amount!!, 0.0)

        val record = line.toRecord()!!
        assertEquals(227.0, record.quantity, 0.0)
        assertEquals(450.0, record.rate, 0.0)
        assertEquals(102150.0, record.amount, 0.0)
        assertEquals(ProductUnit.AREA, record.unit)
        // The opening travels in the spec, without the rate, so an edited rate
        // cannot leave a contradiction inside the sentence.
        assertEquals("3000 \u00d7 3500 mm = 113.5 sq ft \u00d7 2 nos", record.spec)
    }

    @Test
    fun `a line amount is whole rupees, so the printed page adds up`() {
        val draft = QuoteDraft().addManual(id = "ln_1", title = "Odd", quantity = 3.0, rate = 10.5)
        assertEquals(32.0, draft.lines.single().amount!!, 0.0)
        assertEquals(32.0, draft.total, 0.0)
    }
}
