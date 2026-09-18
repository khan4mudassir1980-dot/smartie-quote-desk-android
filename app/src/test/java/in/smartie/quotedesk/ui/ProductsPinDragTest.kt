package `in`.smartie.quotedesk.ui

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.data.mapping.Keys
import `in`.smartie.quotedesk.data.model.ProductRecord
import `in`.smartie.quotedesk.domain.Catalogue
import `in`.smartie.quotedesk.domain.QuoteDraft
import `in`.smartie.quotedesk.ui.products.ProductsActions
import `in`.smartie.quotedesk.ui.products.ProductsCatalogue
import `in`.smartie.quotedesk.ui.theme.SmartieTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Reordering pinned products by dragging, and never by an arrow button again.
 *
 * The drop arithmetic is `PinDragTest`'s and the write is `ProductPinsTest`'s;
 * what is checked here is the handle itself — that only pinned cards have one,
 * that the arrows are gone, and that both the gesture and the named
 * accessibility actions reach the reorder.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w412dp-h915dp")
class ProductsPinDragTest {

    @get:Rule
    val compose = createComposeRule()

    private fun product(seedModel: String) = ProductRecord(
        documentId = Keys.productDocId("gate", seedModel),
        key = Keys.productKey("gate", seedModel),
        group = "gate",
        seedModel = seedModel,
        model = seedModel,
        name = "Sliding gate motor $seedModel",
        categoryId = "cat-sliding",
        client = 25_900.0,
        seeded = true,
    )

    private val first = product("SIE1000")
    private val second = product("SIE2000")
    private val third = product("SIE3000")

    private var reordered: Pair<String, String>? = null
    private var moved: Pair<String, Int>? = null
    private var added: String? = null
    private var unpinned: String? = null

    private fun render(
        pins: List<String> = listOf(first.key, second.key, third.key),
        canPin: Boolean = true,
    ) {
        val products = listOf(first, second, third)
        val view = Catalogue.build(products, emptyList(), pins, "")
        compose.setContent {
            SmartieTheme {
                ProductsCatalogue(
                    canViewProducts = true,
                    view = view,
                    draft = QuoteDraft(),
                    pinnedKeys = pins,
                    canPin = canPin,
                    actions = ProductsActions(
                        onAdd = { added = it.key },
                        onTogglePin = { unpinned = it },
                        onMovePin = { key, delta -> moved = key to delta },
                        onReorderPin = { movedKey, target -> reordered = movedKey to target },
                    ),
                )
            }
        }
    }

    @Test
    fun `the up and down arrow buttons are gone`() {
        render()
        for (arrow in listOf("↑", "↓")) {
            assertTrue(
                "the pinned shelf must not offer $arrow",
                compose.onAllNodesWithText(arrow).fetchSemanticsNodes().isEmpty()
            )
        }
    }

    @Test
    fun `every pinned card carries a drag handle`() {
        render()
        compose.onNodeWithContentDescription("Reorder SIE1000").assertExists()
        compose.onNodeWithContentDescription("Reorder SIE2000").assertExists()
    }

    @Test
    fun `a shelf of one has nothing to reorder`() {
        render(pins = listOf(first.key))
        assertTrue(
            compose.onAllNodesWithContentDescription("Reorder SIE1000")
                .fetchSemanticsNodes().isEmpty()
        )
    }

    @Test
    fun `nobody who cannot manage pins gets a handle`() {
        render(canPin = false)
        assertTrue(
            compose.onAllNodesWithContentDescription("Reorder SIE1000")
                .fetchSemanticsNodes().isEmpty()
        )
    }

    @Test
    fun `the handle's named actions move a pin without restoring the arrows`() {
        render()
        val actions = compose.onNodeWithContentDescription("Reorder SIE2000")
            .fetchSemanticsNode()
            .config[SemanticsActions.CustomActions]

        actions.first { it.label == "Move up" }.action()
        assertEquals(second.key to -1, moved)

        actions.first { it.label == "Move down" }.action()
        assertEquals(second.key to 1, moved)
    }

    /** One row's pitch, measured from the handles themselves. */
    private fun rowPitch(): Float {
        val top = compose.onNodeWithContentDescription("Reorder SIE1000")
            .fetchSemanticsNode().positionInRoot.y
        val next = compose.onNodeWithContentDescription("Reorder SIE2000")
            .fetchSemanticsNode().positionInRoot.y
        return next - top
    }

    /**
     * The long press has to be given time to fire between the touch going
     * down and the first movement, which is why the clock is advanced between
     * two separate injections rather than inside one.
     */
    private fun dragFirstHandle(by: Float) {
        val handle = compose.onNodeWithContentDescription("Reorder SIE1000")
        handle.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_000L)
        handle.performTouchInput { moveBy(Offset(0f, by)) }
        handle.performTouchInput { up() }
        compose.waitForIdle()
    }

    @Test
    fun `a long press and drag reorders, and never adds or unpins`() {
        render()
        dragFirstHandle(by = rowPitch() * 1.2f)
        assertEquals(first.key to second.key, reordered)
        assertNull("the drag must not add to the quotation", added)
        assertNull("the drag must not unpin", unpinned)
    }

    @Test
    fun `a press that goes nowhere reorders nothing`() {
        render()
        dragFirstHandle(by = 4f)
        assertNull(reordered)
    }
}
