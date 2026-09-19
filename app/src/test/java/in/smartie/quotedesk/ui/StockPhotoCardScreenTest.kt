package `in`.smartie.quotedesk.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import `in`.smartie.quotedesk.data.model.StockRecord
import `in`.smartie.quotedesk.ui.stock.PHOTO_BUTTON
import `in`.smartie.quotedesk.ui.stock.StockActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The photo on a stock card.
 *
 * The assertion that carries the most weight here is the negative one: a row
 * whose `hasPhoto` is false must not ask for a photo at all. That flag exists
 * on the stock document precisely so the board does not spend 403 reads
 * finding out which rows have pictures, and a card that asked anyway would
 * quietly undo it.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w412dp-h915dp")
class StockPhotoCardScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val photographed =
        stockRecord("SIE1000", "Sliding gate motor", hasPhoto = true, photoRev = 3.0)
    private val plain = stockRecord("SIE2000", "Swing gate motor")

    /**
     * Records which rows were asked for, and hands back nothing.
     *
     * Returning null matters: it means the loader finishes without a decode,
     * so the assertion is about who was asked rather than about Robolectric's
     * graphics.
     */
    private fun loader(asked: MutableList<String>): suspend (StockRecord) -> ByteArray? =
        { record ->
            asked += record.key
            null
        }

    @Test
    fun `a photographed row shows a picture slot`() {
        compose.showStock(stock = listOf(photographed), capabilities = staffCaps)
        // Nothing decodes under Robolectric, so what shows is the placeholder
        // — which is the point: it is a slot, not a spinner.
        compose.onNodeWithContentDescription("No photo showing for Sliding gate motor")
            .assertExists()
    }

    @Test
    fun `a row without a photo shows no slot at all`() {
        compose.showStock(stock = listOf(plain), capabilities = staffCaps)
        assertTrue(
            "an empty frame where there was never a picture is worse than nothing",
            compose.onAllNodesWithContentDescription("No photo showing for Swing gate motor")
                .fetchSemanticsNodes().isEmpty()
        )
    }

    @Test
    fun `only a row that says it has a photo is asked for one`() {
        val asked = mutableListOf<String>()
        compose.showStock(
            stock = listOf(photographed, plain),
            capabilities = staffCaps,
            actions = StockActions(loadPhoto = loader(asked))
        )
        compose.waitForIdle()

        assertEquals(listOf(photographed.key), asked)
    }

    @Test
    fun `a Worker is asked for the photo too — looking is not changing`() {
        val asked = mutableListOf<String>()
        compose.showStock(
            stock = listOf(photographed),
            capabilities = workerCaps,
            actions = StockActions(loadPhoto = loader(asked))
        )
        compose.waitForIdle()

        assertEquals(listOf(photographed.key), asked)
    }

    @Test
    fun `tapping the picture opens it, and writes nothing`() {
        val opened = mutableListOf<String>()
        compose.showStock(
            stock = listOf(photographed),
            capabilities = staffCaps,
            actions = StockActions(onOpenPhoto = { opened += it.key })
        )

        compose.onNodeWithContentDescription("No photo showing for Sliding gate motor")
            .performClick()

        assertEquals(listOf(photographed.key), opened)
    }

    @Test
    fun `a row without a photo offers Staff a way to add one`() {
        val opened = mutableListOf<String>()
        compose.showStock(
            stock = listOf(plain),
            capabilities = staffCaps,
            actions = StockActions(onOpenPhoto = { opened += it.key })
        )

        compose.onNodeWithContentDescription("Add a photo of Swing gate motor").performClick()
        assertEquals(listOf(plain.key), opened)
    }

    @Test
    fun `a row that already has one is reached by its picture, not a button`() {
        compose.showStock(stock = listOf(photographed), capabilities = staffCaps)
        assertTrue(
            "two ways in would be one too many",
            compose.onAllNodesWithText(PHOTO_BUTTON).fetchSemanticsNodes().isEmpty()
        )
    }

    @Test
    fun `a Worker is offered no way to add a photo`() {
        compose.showStock(stock = listOf(plain), capabilities = workerCaps)
        assertTrue(
            "no control at all, not a disabled one",
            compose.onAllNodesWithText(PHOTO_BUTTON).fetchSemanticsNodes().isEmpty()
        )
    }
}
