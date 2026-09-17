package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.mapping.Keys
import `in`.smartie.quotedesk.data.model.ProductRecord
import `in`.smartie.quotedesk.data.model.StockMove
import `in`.smartie.quotedesk.data.model.StockRecord
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
    fun `a catalogue entry is keyed by the product's immutable identity`() {
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

    // --- identity is immutable across a display-model rename --------------

    /**
     * The V8C4 `skey(gid, m)` takes the **seed** model. `L.m` is a display
     * field an Administrator may edit, and a rename must never open a second
     * stock row, orphan its movement history, or move its document id.
     */
    @Test
    fun `renaming the display model changes neither the stock key nor the document id`() {
        val before = product(model = "SIE1000", seedModel = "SIE1000")
        val renamed = before.copy(model = "SIE-1000 PRO", name = "Sliding gate motor mk II")

        val first = StockEntry.fromProduct(before)
        val second = StockEntry.fromProduct(renamed)

        assertEquals(first.key, second.key)
        assertEquals(first.documentId, second.documentId)
        assertEquals("gateMotors|SIE1000", second.key)
        // The display fields are free to move; the identity is not.
        assertEquals("SIE-1000 PRO", second.model)
        assertEquals("Sliding gate motor mk II", second.name)
    }

    @Test
    fun `stock and movement history still join after a display-model rename`() {
        val renamed = product(model = "SIE-1000 PRO", seedModel = "SIE1000")
        val entry = StockEntry.fromProduct(renamed)

        // Rows written before the rename, keyed the way they always were.
        val existingStock = StockRecord(
            documentId = "gateMotors|SIE1000",
            key = "gateMotors|SIE1000",
            quantity = 7.0
        )
        val existingMove = StockMove(id = "mv_1", key = "gateMotors|SIE1000", next = 7.0)

        assertEquals(existingStock.key, entry.key)
        assertEquals(existingStock.documentId, entry.documentId)
        assertEquals(existingMove.key, entry.key)
        // And the join the Products screen performs still resolves.
        assertEquals(existingStock, listOf(existingStock).associateBy { it.key }[renamed.stockKey])
    }

    @Test
    fun `the product's own stored key wins over everything else`() {
        val odd = product(model = "RENAMED", seedModel = "ALSO-NOT-THIS")
            .copy(key = "gateMotors|SIE1000")
        assertEquals("gateMotors|SIE1000", odd.stockKey)
        assertEquals("gateMotors|SIE1000", StockEntry.fromProduct(odd).key)
    }

    @Test
    fun `the seed model is the identity when there is no stored key`() {
        val noKey = product(model = "RENAMED", seedModel = "SIE1000").copy(key = "")
        assertEquals("gateMotors|SIE1000", noKey.stockKey)
        assertEquals("gateMotors|SIE1000", StockEntry.fromProduct(noKey).key)
    }

    /** A legacy document with neither: the display model is all there is. */
    @Test
    fun `a legacy product with no key and no seed model still works`() {
        val legacy = product(model = "SIE1000").copy(key = "", seedModel = "")
        assertEquals("gateMotors|SIE1000", legacy.stockKey)
        val entry = StockEntry.fromProduct(legacy)
        assertEquals("gateMotors|SIE1000", entry.key)
        assertNull(entry.refusal(0.0, 0.0))
    }

    @Test
    fun `a seed model carrying a slash keeps the stock rule, not the product one`() {
        val renamed = product(group = "hwWheel", model = "BALANCE WHEEL 58H", seedModel = "SIEBAL58H/V")
            .copy(key = "")
        val entry = StockEntry.fromProduct(renamed)

        assertEquals("hwWheel|SIEBAL58H/V", entry.key)
        assertEquals("hwWheel|SIEBAL58H_V", entry.documentId)
        // The product scheme would have produced something else entirely.
        assertEquals("hwWheel__SIEBAL58H_V", Keys.productDocId("hwWheel", "SIEBAL58H/V"))
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
