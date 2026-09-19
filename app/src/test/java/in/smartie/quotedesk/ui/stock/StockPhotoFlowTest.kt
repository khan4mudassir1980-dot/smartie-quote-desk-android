package `in`.smartie.quotedesk.ui.stock

import `in`.smartie.quotedesk.data.model.StockRecord
import `in`.smartie.quotedesk.domain.StockPhoto
import `in`.smartie.quotedesk.domain.StockPhotoImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The photo flow, off device.
 *
 * Every assertion here is about a *stage*, because the defects the first
 * staging pass found were all of the two-flags-disagreeing kind. One value
 * moves between stages and this is where that value is proved.
 */
class StockPhotoFlowTest {

    private fun row(hasPhoto: Boolean = false, photoRev: Double = 0.0) = StockRecord(
        documentId = "gateMotors|SIE1000",
        key = "gateMotors|SIE1000",
        name = "Sliding gate motor",
        hasPhoto = hasPhoto,
        photoRev = photoRev
    )

    private fun webp(payload: Int = 256): ByteArray =
        "RIFF".toByteArray() + ByteArray(4) + "WEBP".toByteArray() + ByteArray(payload)

    private fun image(payload: Int = 256) = StockPhotoImage(webp(payload), 800, 600)

    // --- opening ------------------------------------------------------------

    @Test
    fun `a photographed row opens at its picture`() {
        val state = StockPhotoFlow.open(row(hasPhoto = true, photoRev = 1.0), canManage = true)
        assertEquals(PhotoStage.VIEW, state.stage)
        assertTrue(state.isOpen)
    }

    @Test
    fun `a row without a photo opens at the source sheet, for someone who may add one`() {
        val state = StockPhotoFlow.open(row(), canManage = true)
        assertEquals(PhotoStage.SOURCE, state.stage)
    }

    @Test
    fun `a Worker opening a row without a photo gets nothing at all`() {
        // Not an empty sheet offering options they do not have.
        val state = StockPhotoFlow.open(row(), canManage = false)
        assertEquals(PhotoStage.CLOSED, state.stage)
        assertFalse(state.isOpen)
        assertNull(state.record)
    }

    @Test
    fun `a Worker opening a photographed row sees the picture`() {
        val state = StockPhotoFlow.open(row(hasPhoto = true, photoRev = 2.0), canManage = false)
        assertEquals(PhotoStage.VIEW, state.stage)
    }

    @Test
    fun `nothing is prepared or refused on opening`() {
        val state = StockPhotoFlow.open(row(hasPhoto = true, photoRev = 1.0), canManage = true)
        assertNull(state.prepared)
        assertNull(state.refusal)
        assertFalse(state.saving)
    }

    // --- taking a picture ---------------------------------------------------

    @Test
    fun `replacing goes back to choosing a source, keeping the row`() {
        val viewing = StockPhotoFlow.open(row(hasPhoto = true, photoRev = 1.0), canManage = true)
        val state = StockPhotoFlow.replace(viewing)

        assertEquals(PhotoStage.SOURCE, state.stage)
        assertSame(viewing.record, state.record)
    }

    @Test
    fun `the preview opens empty while the camera is still out`() {
        val state = StockPhotoFlow.awaiting(StockPhotoFlow.open(row(), canManage = true))

        assertEquals(PhotoStage.PREVIEW, state.stage)
        assertTrue("the person should see the sheet they are waiting on", state.preparing)
        assertNull("and nothing is confirmable yet", state.prepared)
    }

    @Test
    fun `a prepared picture can be confirmed`() {
        val waiting = StockPhotoFlow.awaiting(StockPhotoFlow.open(row(), canManage = true))
        val state = StockPhotoFlow.prepared(waiting, image())

        assertEquals(PhotoStage.PREVIEW, state.stage)
        assertNotNull(state.prepared)
        assertNull(state.refusal)
        assertFalse(state.preparing)
    }

    @Test
    fun `a picture that could not be made to fit is refused, in words`() {
        val waiting = StockPhotoFlow.awaiting(StockPhotoFlow.open(row(), canManage = true))
        val state = StockPhotoFlow.prepared(waiting, null)

        assertEquals(StockPhoto.WILL_NOT_FIT, state.refusal)
        assertNull("and nothing can be written", state.prepared)
    }

    @Test
    fun `bytes that are somehow not a photo are refused rather than prepared`() {
        val waiting = StockPhotoFlow.awaiting(StockPhotoFlow.open(row(), canManage = true))
        val notAPicture = StockPhotoImage("nope".toByteArray(), 10, 10)

        val state = StockPhotoFlow.prepared(waiting, notAPicture)

        assertEquals(StockPhoto.NOT_AN_IMAGE, state.refusal)
        assertNull(state.prepared)
    }

    @Test
    fun `bytes over the ceiling are refused rather than prepared`() {
        val waiting = StockPhotoFlow.awaiting(StockPhotoFlow.open(row(), canManage = true))
        val state = StockPhotoFlow.prepared(waiting, image(payload = StockPhoto.MAX_BYTES))

        assertEquals(StockPhoto.WILL_NOT_FIT, state.refusal)
        assertNull(state.prepared)
    }

    @Test
    fun `preparing a second picture clears the first refusal`() {
        val waiting = StockPhotoFlow.awaiting(StockPhotoFlow.open(row(), canManage = true))
        val refused = StockPhotoFlow.prepared(waiting, null)
        val state = StockPhotoFlow.prepared(StockPhotoFlow.awaiting(refused), image())

        assertNull("a stale refusal beside a good picture would be nonsense", state.refusal)
        assertNotNull(state.prepared)
    }

    // --- backing out --------------------------------------------------------

    @Test
    fun `backing out of the camera returns to the source sheet, not out of the flow`() {
        val waiting = StockPhotoFlow.awaiting(StockPhotoFlow.open(row(), canManage = true))
        val state = StockPhotoFlow.abandoned(waiting)

        assertEquals(PhotoStage.SOURCE, state.stage)
        assertTrue(state.isOpen)
    }

    @Test
    fun `backing out while replacing returns to the picture that is still there`() {
        val photographed = row(hasPhoto = true, photoRev = 1.0)
        val waiting = StockPhotoFlow.awaiting(
            StockPhotoFlow.replace(StockPhotoFlow.open(photographed, canManage = true))
        )

        assertEquals(PhotoStage.VIEW, StockPhotoFlow.abandoned(waiting).stage)
    }

    @Test
    fun `backing out prepares nothing and refuses nothing`() {
        val waiting = StockPhotoFlow.awaiting(StockPhotoFlow.open(row(), canManage = true))
        val state = StockPhotoFlow.abandoned(StockPhotoFlow.prepared(waiting, image()))

        assertNull("the unconfirmed picture is dropped", state.prepared)
        assertNull(state.refusal)
    }

    // --- saving -------------------------------------------------------------

    @Test
    fun `saving keeps the sheet open, so nothing looks as though it vanished`() {
        val ready = StockPhotoFlow.prepared(
            StockPhotoFlow.awaiting(StockPhotoFlow.open(row(), canManage = true)),
            image()
        )
        val state = StockPhotoFlow.saving(ready)

        assertTrue(state.saving)
        assertEquals(PhotoStage.PREVIEW, state.stage)
        assertNotNull("the picture is still there to look at", state.prepared)
    }

    @Test
    fun `a failed save leaves the picture exactly where it was`() {
        val ready = StockPhotoFlow.prepared(
            StockPhotoFlow.awaiting(StockPhotoFlow.open(row(), canManage = true)),
            image()
        )
        val state = StockPhotoFlow.failed(StockPhotoFlow.saving(ready))

        assertFalse(state.saving)
        assertEquals("so it can be tried again", PhotoStage.PREVIEW, state.stage)
        assertNotNull(state.prepared)
    }

    @Test
    fun `closing clears everything`() {
        val state = StockPhotoFlow.closed()
        assertEquals(PhotoStage.CLOSED, state.stage)
        assertNull(state.record)
        assertNull(state.prepared)
        assertFalse(state.isOpen)
    }

    // --- nothing happens without a row --------------------------------------

    @Test
    fun `no transition invents a row out of nothing`() {
        val closed = StockPhotoUi()
        assertFalse(StockPhotoFlow.replace(closed).isOpen)
        assertFalse(StockPhotoFlow.awaiting(closed).isOpen)
        assertFalse(StockPhotoFlow.prepared(closed, image()).isOpen)
        assertFalse(StockPhotoFlow.abandoned(closed).isOpen)
    }

}
