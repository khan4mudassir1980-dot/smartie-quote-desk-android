package `in`.smartie.quotedesk.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductPinsTest {

    private val pins = listOf("gate|A", "gate|B", "gate|C")

    private fun keysAfter(change: PinChange): List<String> =
        (change as PinChange.Updated).keys

    @Test
    fun `a new pin is appended, not inserted at the top`() {
        // The native beta inserted at the top, so pin order differed from the
        // PWA's on the same data (audit P4).
        assertEquals(
            listOf("gate|A", "gate|B", "gate|C", "gate|D"),
            keysAfter(ProductPins.pin(pins, "gate|D")),
        )
    }

    @Test
    fun `pinning something already pinned changes nothing`() {
        assertEquals(PinChange.Unchanged, ProductPins.pin(pins, "gate|B"))
        assertEquals(PinChange.Unchanged, ProductPins.pin(pins, ""))
    }

    @Test
    fun `the sixteenth pin is refused`() {
        val full = (1..15).map { "gate|M$it" }
        assertEquals(ProductPins.MAX, full.size)
        assertEquals(PinChange.Full, ProductPins.pin(full, "gate|M16"))
        // Unpinning frees the slot again.
        val freed = keysAfter(ProductPins.unpin(full, "gate|M1"))
        val refilled = keysAfter(ProductPins.pin(freed, "gate|M16"))
        assertEquals(ProductPins.MAX, refilled.size)
        assertEquals("gate|M2", refilled.first())
        assertEquals("gate|M16", refilled.last())
    }

    @Test
    fun `unpinning removes only that key`() {
        assertEquals(listOf("gate|A", "gate|C"), keysAfter(ProductPins.unpin(pins, "gate|B")))
        assertEquals(PinChange.Unchanged, ProductPins.unpin(pins, "gate|Z"))
    }

    @Test
    fun `toggle pins what is not pinned and unpins what is`() {
        assertEquals(listOf("gate|A", "gate|C"), keysAfter(ProductPins.toggle(pins, "gate|B")))
        assertEquals(
            listOf("gate|A", "gate|B", "gate|C", "gate|D"),
            keysAfter(ProductPins.toggle(pins, "gate|D")),
        )
        assertTrue(ProductPins.isPinned(pins, "gate|A"))
        assertFalse(ProductPins.isPinned(pins, "gate|D"))
    }

    @Test
    fun `a pin moves one place at a time`() {
        assertEquals(listOf("gate|B", "gate|A", "gate|C"), keysAfter(ProductPins.move(pins, "gate|B", -1)))
        assertEquals(listOf("gate|A", "gate|C", "gate|B"), keysAfter(ProductPins.move(pins, "gate|B", 1)))
    }

    @Test
    fun `the ends of the list do not wrap`() {
        assertEquals(PinChange.Unchanged, ProductPins.move(pins, "gate|A", -1))
        assertEquals(PinChange.Unchanged, ProductPins.move(pins, "gate|C", 1))
        assertEquals(PinChange.Unchanged, ProductPins.move(pins, "gate|Z", -1))
        assertEquals(PinChange.Unchanged, ProductPins.move(pins, "gate|B", 0))
    }

    @Test
    fun `a drop moves a pin to the index it was dropped on`() {
        assertEquals(
            listOf("gate|B", "gate|C", "gate|A"),
            keysAfter(ProductPins.reorder(pins, from = 0, to = 2)),
        )
        assertEquals(
            listOf("gate|C", "gate|A", "gate|B"),
            keysAfter(ProductPins.reorder(pins, from = 2, to = 0)),
        )
    }

    @Test
    fun `a drop neither adds nor loses a pin, so the cap cannot be breached`() {
        val full = (1..ProductPins.MAX).map { "gate|$it" }
        val dropped = keysAfter(ProductPins.reorder(full, from = 14, to = 0))
        assertEquals(ProductPins.MAX, dropped.size)
        assertEquals(full.toSet(), dropped.toSet())
        assertEquals("gate|15", dropped.first())
    }

    @Test
    fun `a drop that lands where it started, or outside the list, changes nothing`() {
        assertEquals(PinChange.Unchanged, ProductPins.reorder(pins, from = 1, to = 1))
        assertEquals(PinChange.Unchanged, ProductPins.reorder(pins, from = 1, to = 3))
        assertEquals(PinChange.Unchanged, ProductPins.reorder(pins, from = -1, to = 1))
    }

    @Test
    fun `a drop works in keys, so a pin whose product has gone keeps its place`() {
        // The shelf drops a pin whose product is missing, so the cards the
        // finger moves are a shorter list than the one being written.
        val stored = listOf("gate|A", "gate|GONE", "gate|B", "gate|C")
        assertEquals(
            listOf("gate|A", "gate|GONE", "gate|C", "gate|B"),
            keysAfter(ProductPins.reorderTo(stored, "gate|B", "gate|C")),
        )
        assertEquals(
            PinChange.Unchanged,
            ProductPins.reorderTo(stored, "gate|B", "gate|NOT-PINNED"),
        )
    }
}
