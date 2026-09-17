package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.mapping.Keys
import `in`.smartie.quotedesk.data.model.ProductRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StockEntryTest {

    private fun product(
        group: String = "gateMotors",
        model: String = "SIE1000",
        seedModel: String = model,
        name: String = "Sliding gate motor",
        unit: String = "each",
        categoryId: String = "cat-motors"
    ) = ProductRecord(
        documentId = Keys.productDocId(group, seedModel),
        key = Keys.productKey(group, seedModel),
        group = group,
        seedModel = seedModel,
        model = model,
        name = name,
        unit = unit,
        categoryId = categoryId,
        gst = 18.0,
        dealer = 18500.0,
        contractor = 22200.0,
        client = 25900.0
    )

    // --- from the catalogue ----------------------------------------------

    @Test
    fun `a catalogue entry is keyed by group and model`() {
        val entry = StockEntry.fromProduct(product())
        assertEquals("gateMotors|SIE1000", entry.key)
        assertEquals("gateMotors", entry.group)
        assertEquals("SIE1000", entry.model)
        assertEquals("Sliding gate motor", entry.name)
        assertEquals("each", entry.unit)
        assertEquals("cat-motors", entry.categoryId)
        assertTrue(!entry.manual)
    }

    /**
     * A stock document uses the logical key with only `/` replaced, where a
     * product document uses `group__model` with six characters replaced. The
     * two must never be confused.
     */
    @Test
    fun `a catalogue entry's document id is the stock scheme, not the product one`() {
        val entry = StockEntry.fromProduct(product(group = "gate", model = "SIE2.5MSMALL"))
        assertEquals("gate|SIE2.5MSMALL", entry.documentId)
        assertEquals("gate__SIE2_5MSMALL", Keys.productDocId("gate", "SIE2.5MSMALL"))
    }

    @Test
    fun `a slash in the model is replaced in the stock document id`() {
        val entry = StockEntry.fromProduct(product(group = "hwWheel", model = "SIEBAL58H/V"))
        assertEquals("hwWheel|SIEBAL58H/V", entry.key)
        assertEquals("hwWheel|SIEBAL58H_V", entry.documentId)
    }

    /** A Worker may read `/stock` and never `/products`; no price may cross. */
    @Test
    fun `no price or tax field is carried across from the catalogue`() {
        val entry = StockEntry.fromProduct(product())
        val text = entry.toString()
        for (forbidden in listOf("18500", "22200", "25900")) {
            assertTrue("A price leaked into the stock entry: $forbidden", !text.contains(forbidden))
        }
    }

    @Test
    fun `a product with no name falls back to its model`() {
        assertEquals("SIE1000", StockEntry.fromProduct(product(name = "  ")).name)
    }

    @Test
    fun `a product with no unit is counted in each`() {
        assertEquals("each", StockEntry.fromProduct(product(unit = "")).unit)
    }

    // --- manual items -----------------------------------------------------

    @Test
    fun `a manual entry is keyed under manualstock`() {
        val entry = StockEntry.manual(model = "Shed Padlock", name = "Brass padlock")
        assertEquals("manualstock|shed_padlock", entry.key)
        assertEquals("manualstock", entry.group)
        assertEquals("shed_padlock", entry.model)
        assertEquals("Brass padlock", entry.manualName)
        assertEquals("Shed Padlock", entry.manualModel)
        assertTrue(entry.manual)
    }

    @Test
    fun `either field alone is enough`() {
        assertEquals("manualstock|brass_padlock", StockEntry.manual(model = "", name = "Brass padlock").key)
        assertEquals("manualstock|shed_padlock", StockEntry.manual(model = "Shed padlock", name = "").key)
    }

    @Test
    fun `a manual name stands in when only a model was typed`() {
        assertEquals("Shed padlock", StockEntry.manual(model = "Shed padlock", name = "").name)
    }

    @Test
    fun `the slug collapses punctuation rather than repeating underscores`() {
        assertEquals("gd_3_0_a", StockEntry.slug("GD-3.0-A"))
        assertEquals("shed_padlock", StockEntry.slug("  Shed   Padlock!!  "))
        assertEquals("", StockEntry.slug("   "))
        assertEquals("", StockEntry.slug("!!!"))
    }

    @Test
    fun `a manual document id needs no replacement because the slug has none`() {
        val entry = StockEntry.manual(model = "A/B rail", name = "")
        assertEquals("manualstock|a_b_rail", entry.key)
        assertEquals("manualstock|a_b_rail", entry.documentId)
    }

    // --- refusals ---------------------------------------------------------

    @Test
    fun `a blank item is refused`() {
        assertEquals(StockEntry.BLANK, StockEntry.manual(model = "", name = "").refusal(0.0, 0.0))
        assertEquals(StockEntry.BLANK, StockEntry.manual(model = "!!!", name = "").refusal(0.0, 0.0))
    }

    @Test
    fun `a negative starting quantity is refused`() {
        assertEquals(
            StockEntry.NEGATIVE_QUANTITY,
            StockEntry.fromProduct(product()).refusal(-1.0, 0.0)
        )
    }

    @Test
    fun `a negative reorder level is refused`() {
        assertEquals(
            StockEntry.NEGATIVE_REORDER,
            StockEntry.fromProduct(product()).refusal(0.0, -1.0)
        )
    }

    @Test
    fun `a key that is already tracked is refused rather than merged onto`() {
        assertEquals(
            StockEntry.ALREADY_TRACKED,
            StockEntry.fromProduct(product()).refusal(1.0, 0.0, setOf("gateMotors|SIE1000"))
        )
    }

    @Test
    fun `a sound entry is accepted, including zero quantity and zero reorder`() {
        assertNull(StockEntry.fromProduct(product()).refusal(0.0, 0.0))
        assertNull(StockEntry.fromProduct(product()).refusal(12.0, 3.0, setOf("other|KEY")))
    }
}
