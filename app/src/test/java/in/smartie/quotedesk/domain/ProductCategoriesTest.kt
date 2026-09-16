package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.model.ProductCategoryRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductCategoriesTest {

    @Test
    fun `the twelve default shelves are present in the PWA's order`() {
        val shelves = ProductCategories.merge(emptyList())
        assertEquals(12, shelves.size)
        assertEquals(
            listOf(
                "Sliding Gate Motors",
                "Swing Gate Motors",
                "Shutter Motors",
                "High-Speed Door Motors",
                "Boom Barriers",
                "Garage Door Motors",
                "Automatic/Glass Door Systems",
                "Gate Motor Accessories",
                "Sensors & Safety Devices",
                "Control Boards, Receivers & Remotes",
                "Gate Hardware",
                "Other Products",
            ),
            shelves.map { it.name },
        )
    }

    @Test
    fun `an override renames and reorders a default shelf`() {
        val shelves = ProductCategories.merge(
            listOf(ProductCategoryRecord("cat-hardware", "Gate & fence hardware", 15.0))
        )
        val hardware = shelves.first { it.id == "cat-hardware" }
        assertEquals("Gate & fence hardware", hardware.name)
        assertEquals(15.0, hardware.order, 0.0)
        // 15 places it between Sliding (10) and Swing (20).
        assertEquals(1, shelves.indexOf(hardware))
    }

    @Test
    fun `an override that supplies neither name nor order keeps the default's`() {
        // toProductCategories reads an absent name back as the id and an
        // absent order as zero; neither should drag the shelf to the front.
        val shelves = ProductCategories.merge(
            listOf(ProductCategoryRecord("cat-shutter", "cat-shutter", 0.0))
        )
        val shutter = shelves.first { it.id == "cat-shutter" }
        assertEquals("Shutter Motors", shutter.name)
        assertEquals(30.0, shutter.order, 0.0)
    }

    @Test
    fun `a shelf nobody knows is appended at five hundred`() {
        val shelves = ProductCategories.merge(
            listOf(ProductCategoryRecord("cat-fire", "Fire-rated shutters", 0.0))
        )
        val fire = shelves.first { it.id == "cat-fire" }
        assertEquals(500.0, fire.order, 0.0)
        // After Gate Hardware (110), before Other Products (900).
        assertEquals(shelves.size - 2, shelves.indexOf(fire))
    }

    @Test
    fun `an archived shelf is dropped, default or not`() {
        val shelves = ProductCategories.merge(
            listOf(
                ProductCategoryRecord("cat-boom", "Boom Barriers", 50.0, archived = true),
                ProductCategoryRecord("cat-fire", "Fire-rated shutters", 200.0, archived = true),
            )
        )
        assertTrue(shelves.none { it.id == "cat-boom" })
        assertTrue(shelves.none { it.id == "cat-fire" })
        assertEquals(11, shelves.size)
    }

    @Test
    fun `the three retired shelf ids map onto their survivors`() {
        assertEquals("cat-hardware", ProductCategories.alias("cat-kits"))
        assertEquals("cat-hardware", ProductCategories.alias("cat-profile"))
        assertEquals("cat-other", ProductCategories.alias("cat-service"))
        assertEquals("cat-shutter", ProductCategories.alias("cat-shutter"))
        assertEquals("", ProductCategories.alias(null))
    }

    @Test
    fun `an override filed under a retired id lands on the surviving shelf`() {
        val shelves = ProductCategories.merge(
            listOf(ProductCategoryRecord("cat-kits", "Kits and hardware", 12.0))
        )
        assertEquals("Kits and hardware", shelves.first { it.id == "cat-hardware" }.name)
        assertTrue(shelves.none { it.id == "cat-kits" })
        assertEquals(12, shelves.size)
    }

    @Test
    fun `a product on an unknown or archived shelf falls to Other Products`() {
        val live = ProductCategories.merge(
            listOf(ProductCategoryRecord("cat-boom", "Boom Barriers", 50.0, archived = true))
        )
        assertEquals("cat-shutter", ProductCategories.shelfFor("cat-shutter", live))
        assertEquals("cat-hardware", ProductCategories.shelfFor("cat-kits", live))
        assertEquals("cat-other", ProductCategories.shelfFor("cat-boom", live))
        assertEquals("cat-other", ProductCategories.shelfFor("cat-nonsense", live))
        assertEquals("cat-other", ProductCategories.shelfFor("", live))
        assertEquals("cat-other", ProductCategories.shelfFor(null, live))
    }

    @Test
    fun `a shelf never displays as a raw id`() {
        val live = ProductCategories.merge(emptyList())
        assertEquals("Shutter Motors", ProductCategories.nameFor("cat-shutter", live))
        assertEquals("Gate Hardware", ProductCategories.nameFor("cat-kits", live))
        assertEquals(
            "Other Products / Needs categorisation",
            ProductCategories.nameFor("cat-nonsense", live),
        )
    }
}
