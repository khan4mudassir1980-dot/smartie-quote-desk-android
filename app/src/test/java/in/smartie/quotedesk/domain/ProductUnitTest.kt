package `in`.smartie.quotedesk.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The unit a product is priced in.
 *
 * **This is a free string, and the catalogue depends on it staying one.** The
 * price book uses `per m`, `per pc`, `per kg`, `per rft`, `per ft` and
 * `per rm` besides `per sq ft`, and far more products carry one of those than
 * carry the area unit. A closed list, or a toggle that wrote `each` whenever
 * it was off, would convert every one of them the first time somebody
 * corrected a rate — under a complete write, on the very products this batch
 * exists to get right.
 *
 * **[ProductUnit.isArea] folds case and does nothing else.** Values arrive
 * already trimmed (`asStringOrNull`), and folding case is what every other
 * wire reader here does. `sqft` and `sq ft` are different *strings*, not
 * different cases: they are not area-priced, the product is priced per piece,
 * and that shows on its list row instead of being silent.
 */
class ProductUnitTest {

    @Test
    fun `V8C4's own spelling is what turns area pricing on`() {
        assertTrue(ProductUnit.isArea("per sq ft"))
        assertEquals("per sq ft", ProductUnit.AREA)
    }

    @Test
    fun `and case is folded, because every other wire reader folds it`() {
        assertTrue(ProductUnit.isArea("Per sq ft"))
        assertTrue(ProductUnit.isArea("PER SQ FT"))
        assertTrue(ProductUnit.isArea("  per sq ft  "))
    }

    @Test
    fun `a different spelling is not area priced, and that refusal is deliberate`() {
        // The refusal is visible rather than silent: the product's list row
        // shows its unit, so `sqft` is readable there and one edit fixes it.
        // Guessing here would be the silent option.
        listOf("sqft", "sq ft", "persqft", "per sqft", "sq. ft.", "sft")
            .forEach { assertFalse("must not be area priced: $it", ProductUnit.isArea(it)) }
    }

    @Test
    fun `the units most of the catalogue actually uses are not area priced either`() {
        // The ones a toggle would have destroyed.
        listOf("each", "per m", "per pc", "per kg", "per rft", "per ft", "per rm", "no")
            .forEach { assertFalse("must not be area priced: $it", ProductUnit.isArea(it)) }
    }

    @Test
    fun `a blank box falls back to each, exactly as the PWA does`() {
        // V8C4's `#fU` saves `value.trim() || "each"`, so a cleared box means
        // `each` in both apps rather than an empty string in one of them.
        assertEquals("each", ProductUnit.normalise(""))
        assertEquals("each", ProductUnit.normalise("   "))
        assertEquals("each", ProductUnit.EACH)
    }

    @Test
    fun `and anything typed is stored trimmed and otherwise untouched`() {
        // Not normalised to a known list: an unknown unit is the person's
        // business, and rewriting it is how the catalogue loses `per rft`.
        assertEquals("per sq ft", ProductUnit.normalise("  per sq ft "))
        assertEquals("per m", ProductUnit.normalise("per m"))
        assertEquals("PER SQ FT", ProductUnit.normalise("PER SQ FT"))
        assertEquals("per running foot", ProductUnit.normalise("per running foot"))
    }
}
