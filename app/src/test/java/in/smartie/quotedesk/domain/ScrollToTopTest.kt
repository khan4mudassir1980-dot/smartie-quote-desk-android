package `in`.smartie.quotedesk.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the catalogue offers a way back to the top, and when it does not. */
class ScrollToTopTest {

    @Test
    fun `at the very top there is nowhere to go`() {
        assertFalse(ScrollToTop.visible(firstVisibleItemIndex = 0, firstVisibleItemScrollOffset = 0))
    }

    @Test
    fun `a nudge is not a scroll`() {
        // The search box and the filters are the first items; moving past a
        // few pixels of them must not summon a floating control.
        assertFalse(ScrollToTop.visible(0, 40))
        assertFalse(ScrollToTop.visible(1, 0))
        assertFalse(ScrollToTop.visible(2, 900))
    }

    @Test
    fun `a screenful of cards does summon it`() {
        assertTrue(ScrollToTop.visible(ScrollToTop.APPEAR_AFTER_ITEMS, 1))
        assertTrue(ScrollToTop.visible(ScrollToTop.APPEAR_AFTER_ITEMS + 1, 0))
        assertTrue(ScrollToTop.visible(40, 0))
    }

    @Test
    fun `exactly on the threshold, unscrolled, it is still hidden`() {
        // One condition either way, so it cannot flicker under a resting
        // thumb: the same number decides both.
        assertFalse(ScrollToTop.visible(ScrollToTop.APPEAR_AFTER_ITEMS, 0))
    }

    @Test
    fun `scrolling back to the top hides it again`() {
        assertTrue(ScrollToTop.visible(12, 0))
        assertFalse(ScrollToTop.visible(0, 0))
    }

    @Test
    fun `the label is the one a screen reader should read`() {
        org.junit.Assert.assertEquals("Back to top", ScrollToTop.LABEL)
    }
}
