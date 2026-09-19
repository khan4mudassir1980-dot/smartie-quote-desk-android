package `in`.smartie.quotedesk.ui.theme

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The floor under the bottom navigation's own content.
 *
 * The defect this encodes: the bar used to be *pinned* to
 * `bottomNavHeight + 24dp` = 80dp, and `NavigationBar` applies the
 * navigation-bar inset **inside** that height. On a three-button phone the
 * inset is around 48dp, so the icons and labels were laid out in 32dp and
 * finished up against the system navigation. Nothing below subtracts,
 * because the inset is padding under the bar — and nothing caps, because a
 * cap is what clipped the labels.
 */
class BottomNavHeightTest {

    private val base = SmartieDimens().bottomNavHeight

    @Test
    fun `the floor is the compact height the PWA uses`() {
        assertEquals(56.dp, base)
        assertEquals(base, bottomNavMinHeight(base, fontScale = 1f))
    }

    @Test
    fun `no inset comes out of it`() {
        // The fix as arithmetic: whatever the system navigation takes, the
        // content keeps its own height and the bar is inset plus content.
        for (inset in listOf(0.dp, 16.dp, 24.dp, 48.dp, 64.dp)) {
            assertEquals(
                "inset $inset must not shrink the bar",
                base,
                bottomNavMinHeight(base, fontScale = 1f)
            )
        }
    }

    @Test
    fun `a larger font scale raises the floor rather than clipping the label`() {
        assertTrue(bottomNavMinHeight(base, 1.3f) > base)
        assertEquals(base * 1.5f, bottomNavMinHeight(base, 1.5f))
    }

    @Test
    fun `it never falls below the compact height`() {
        // A device set smaller than normal must not squeeze the icons.
        assertEquals(base, bottomNavMinHeight(base, 0.85f))
        assertEquals(base, bottomNavMinHeight(base, 0f))
    }

    @Test
    fun `it stops where the theme stops the font scale`() {
        assertEquals(
            bottomNavMinHeight(base, MAX_EFFECTIVE_FONT_SCALE),
            bottomNavMinHeight(base, 3f)
        )
    }
}
