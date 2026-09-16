package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.mapping.Keys
import `in`.smartie.quotedesk.data.model.ProductCategoryRecord
import `in`.smartie.quotedesk.data.model.ProductRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogueTest {

    private fun product(
        group: String,
        seedModel: String,
        model: String = seedModel,
        name: String = "",
        spec: String = "",
        categoryId: String = "cat-sliding",
        kg: Double? = null,
        active: Boolean = true,
    ): ProductRecord = ProductRecord(
        documentId = Keys.productDocId(group, seedModel),
        key = Keys.productKey(group, seedModel),
        group = group,
        seedModel = seedModel,
        model = model,
        name = name,
        spec = spec,
        categoryId = categoryId,
        kg = kg,
        active = active,
    )

    private val gate = product("gate", "SIE1000", name = "Sliding gate motor 1000 kg", kg = 1000.0)
    private val gateSmall = product("gate", "SIE600", name = "Sliding gate motor 600 kg", kg = 600.0)
    private val shutter = product("shuttermotor", "RS500", name = "Shutter motor", categoryId = "cat-shutter")

    private fun build(
        products: List<ProductRecord> = listOf(gate, gateSmall, shutter),
        categories: List<ProductCategoryRecord> = emptyList(),
        pins: List<String> = emptyList(),
        query: String = "",
        minimumKg: Double? = null,
    ) = Catalogue.build(products, categories, pins, query, minimumKg)

    @Test
    fun `products are filed on the shelf their categoryId names`() {
        val view = build()
        assertEquals(
            listOf(gate.key, gateSmall.key),
            view.shelves.first { it.id == "cat-sliding" }.entries.map { it.product.key },
        )
        assertEquals(
            listOf(shutter.key),
            view.shelves.first { it.id == "cat-shutter" }.entries.map { it.product.key },
        )
    }

    @Test
    fun `a product whose shelf does not exist falls to Other Products`() {
        val orphan = product("misc", "ODD1", categoryId = "cat-nonsense")
        val view = build(products = listOf(orphan))
        assertEquals(
            listOf(orphan.key),
            view.shelves.first { it.id == "cat-other" }.entries.map { it.product.key },
        )
    }

    @Test
    fun `an empty shelf is still shown, with a count of zero`() {
        val view = build(products = listOf(shutter))
        val sliding = view.shelves.first { it.id == "cat-sliding" }
        assertEquals(0, sliding.count)
        assertEquals(12, view.shelves.size)
    }

    @Test
    fun `an archived product is not on any shelf`() {
        val view = build(products = listOf(gate, gate.copy(documentId = "x", key = "gate|OLD", seedModel = "OLD", model = "OLD", active = false)))
        assertEquals(1, view.shelves.sumOf { it.count })
    }

    @Test
    fun `the same model in two price groups appears once`() {
        val twin = product("hwWheel", "SIE1000", name = "Duplicate listing")
        val view = build(products = listOf(gate, twin))
        assertEquals(1, view.shelves.sumOf { it.count })
        assertEquals(gate.key, view.shelves.flatMap { it.entries }.single().product.key)
    }

    @Test
    fun `spacing and punctuation cannot hide a duplicate model`() {
        val twin = product("hwWheel", "sie 1000", model = "SIE-1000")
        val view = build(products = listOf(gate, twin))
        assertEquals(1, view.shelves.sumOf { it.count })
    }

    @Test
    fun `pins come first, in the order they were saved, and leave their shelf`() {
        val view = build(pins = listOf(shutter.key, gate.key))
        assertEquals(listOf(shutter.key, gate.key), view.pinned.map { it.product.key })
        assertTrue(view.pinned.all { it.pinned })
        assertTrue(view.shelves.flatMap { it.entries }.none { it.product.key == gate.key })
        assertEquals(0, view.shelves.first { it.id == "cat-shutter" }.count)
    }

    @Test
    fun `a pin whose product has gone is dropped`() {
        val view = build(pins = listOf("gate|VANISHED", gate.key))
        assertEquals(listOf(gate.key), view.pinned.map { it.product.key })
    }

    @Test
    fun `no more than fifteen pins are shown`() {
        val many = (1..20).map { product("gate", "M$it") }
        val view = build(products = many, pins = many.map { it.key })
        assertEquals(Catalogue.MAX_PINS, view.pinned.size)
        assertEquals("gate|M15", view.pinned.last().product.key)
    }

    @Test
    fun `search matches the model, the name, the specification and the shelf`() {
        assertEquals(1, build(query = "SIE1000").matchCount)
        assertEquals(1, build(query = "600 kg").matchCount)
        assertEquals(1, build(products = listOf(product("gate", "S1", spec = "24V DC")), query = "24v dc").matchCount)
        // "Shutter Motors" is the shelf name, not a field on the product.
        assertEquals(1, build(products = listOf(shutter), query = "shutter motors").matchCount)
    }

    @Test
    fun `search finds a product on a shelf that is collapsed`() {
        val view = build(query = "RS500")
        assertTrue(view.searching)
        assertEquals(listOf(shutter.key), view.results.map { it.product.key })
    }

    @Test
    fun `search reports every hit but renders at most three hundred`() {
        val many = (1..400).map { product("gate", "SIE$it", name = "Gate motor") }
        val view = build(products = many, query = "gate motor")
        assertEquals(400, view.matchCount)
        assertEquals(Catalogue.MAX_RESULTS, view.results.size)
    }

    @Test
    fun `a search that matches nothing reports an empty view`() {
        val view = build(query = "nothing here")
        assertTrue(view.searching)
        assertEquals(0, view.matchCount)
        assertTrue(view.isEmpty)
    }

    @Test
    fun `the load filter keeps only motors that carry the weight`() {
        val view = build(minimumKg = 1000.0)
        val keys = view.shelves.flatMap { it.entries }.map { it.product.key }
        assertTrue(keys.contains(gate.key))
        assertFalse(keys.contains(gateSmall.key))
        // The shutter motor has no load rating, so a load filter hides it too.
        assertFalse(keys.contains(shutter.key))
    }

    @Test
    fun `the load filter hides shelves it empties`() {
        val view = build(minimumKg = 1000.0)
        assertEquals(listOf("cat-sliding"), view.shelves.map { it.id })
    }

    @Test
    fun `no products at all reads as empty even though the shelves exist`() {
        val view = build(products = emptyList())
        assertEquals(12, view.shelves.size)
        assertTrue(view.isEmpty)
    }
}
