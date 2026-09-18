package `in`.smartie.quotedesk.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where a dragged pinned card lands.
 *
 * The gesture itself is a few lines of `pointerInput`; the arithmetic that
 * decides the drop is all here, so it is tested without a device.
 */
class PinDragTest {

    /** Cards are not all the same height: a wrapped name makes one taller. */
    private val heights = listOf(100, 160, 100, 100)

    @Test
    fun `a card that has barely moved stays where it is`() {
        assertEquals(0, PinDrag.targetIndex(heights, from = 0, offsetY = 0f))
        assertEquals(0, PinDrag.targetIndex(heights, from = 0, offsetY = 79f))
        assertEquals(2, PinDrag.targetIndex(heights, from = 2, offsetY = -49f))
    }

    @Test
    fun `it takes a neighbour's place once past half of it`() {
        // The neighbour below is 160 tall, so half of it is 80.
        assertEquals(1, PinDrag.targetIndex(heights, from = 0, offsetY = 80f))
        assertEquals(1, PinDrag.targetIndex(heights, from = 0, offsetY = 209f))
        // 160 + 50 clears half of the 100-tall card after it.
        assertEquals(2, PinDrag.targetIndex(heights, from = 0, offsetY = 210f))
    }

    @Test
    fun `dragging upwards walks the heights the other way`() {
        // Half of the 100-tall card above.
        assertEquals(2, PinDrag.targetIndex(heights, from = 3, offsetY = -50f))
        // That card in full, then half of the 160-tall one: 100 + 80.
        assertEquals(2, PinDrag.targetIndex(heights, from = 3, offsetY = -179f))
        assertEquals(1, PinDrag.targetIndex(heights, from = 3, offsetY = -180f))
        // And 100 + 160 + 50 to reach the top.
        assertEquals(1, PinDrag.targetIndex(heights, from = 3, offsetY = -309f))
        assertEquals(0, PinDrag.targetIndex(heights, from = 3, offsetY = -310f))
    }

    @Test
    fun `it never walks off either end`() {
        assertEquals(3, PinDrag.targetIndex(heights, from = 0, offsetY = 10_000f))
        assertEquals(0, PinDrag.targetIndex(heights, from = 3, offsetY = -10_000f))
    }

    @Test
    fun `an index that is not there is returned untouched`() {
        assertEquals(7, PinDrag.targetIndex(heights, from = 7, offsetY = 500f))
        assertEquals(0, PinDrag.targetIndex(emptyList(), from = 0, offsetY = 500f))
    }
}
