package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.mapping.Keys
import `in`.smartie.quotedesk.data.model.StockRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The photo decisions that need no Android: identity, scaling, orientation,
 * what may be stored, and what the cache should do.
 */
class StockPhotoTest {

    private val author = StockAuthor(name = "Asha", uid = "uid_staff")

    private fun webp(payload: Int): ByteArray =
        "RIFF".toByteArray() + ByteArray(4) + "WEBP".toByteArray() + ByteArray(payload)

    private fun record(
        model: String = "SIE1000",
        group: String = "gateMotors",
        quantity: Double = 7.0,
        reorder: Double = 2.0,
        hasPhoto: Boolean = false,
        photoRev: Double = 0.0
    ): StockRecord {
        val key = Keys.productKey(group, model)
        return StockRecord(
            documentId = Keys.stockDocId(key),
            key = key,
            quantity = quantity,
            reorderLevel = reorder,
            model = model,
            group = group,
            name = "Sliding gate motor",
            unit = "each",
            hasPhoto = hasPhoto,
            photoRev = photoRev
        )
    }

    // --- identity ----------------------------------------------------------

    @Test
    fun `the photo document is the stock document, for catalogue and manual rows`() {
        assertEquals("gateMotors|SIE1000", StockPhoto.documentId("gateMotors|SIE1000"))
        assertEquals("manualstock|anchor_bolt", StockPhoto.documentId("manualstock|anchor_bolt"))
        // The stock scheme replaces only `/`, and the photo follows it exactly.
        assertEquals("gate_Motors|SIE1000", StockPhoto.documentId("gate/Motors|SIE1000"))
    }

    @Test
    fun `renaming what a product is called never moves its photo`() {
        val before = record(model = "SIE1000")
        val after = before.copy(model = "SIE-1000 PRO", name = "Renamed entirely")
        assertEquals(StockPhoto.documentId(before.key), StockPhoto.documentId(after.key))
    }

    // --- scaling -----------------------------------------------------------

    @Test
    fun `the sample size never decodes below the target edge`() {
        assertEquals(4, StockPhoto.sampleSize(4000, 3000))
        assertEquals(1, StockPhoto.sampleSize(800, 600))
        assertEquals(1, StockPhoto.sampleSize(1599, 1200))
        assertEquals(2, StockPhoto.sampleSize(1600, 1200))
    }

    @Test
    fun `a sample size is always a usable power of two`() {
        for (width in listOf(0, 1, 37, 799, 801, 5000, 12000)) {
            val sample = StockPhoto.sampleSize(width, width)
            assertTrue("$width -> $sample", sample >= 1)
            assertEquals("$width -> $sample", 0, sample and (sample - 1))
        }
    }

    @Test
    fun `scaling caps the long edge and keeps the shape`() {
        assertEquals(800 to 600, StockPhoto.scaledSize(4000, 3000))
        assertEquals(600 to 800, StockPhoto.scaledSize(3000, 4000))
        assertEquals(800 to 800, StockPhoto.scaledSize(2000, 2000))
    }

    @Test
    fun `a small photograph is never blown up to fill the ceiling`() {
        assertEquals(320 to 240, StockPhoto.scaledSize(320, 240))
        assertEquals(800 to 450, StockPhoto.scaledSize(800, 450))
    }

    @Test
    fun `a degenerate size is returned rather than divided by zero`() {
        assertEquals(0 to 0, StockPhoto.scaledSize(0, 0))
        assertEquals(1, StockPhoto.sampleSize(0, 0))
    }

    // --- orientation -------------------------------------------------------

    @Test
    fun `every EXIF orientation maps to a rotation`() {
        assertEquals(0, StockPhoto.rotationFor(1))
        assertEquals(0, StockPhoto.rotationFor(2))
        assertEquals(180, StockPhoto.rotationFor(3))
        assertEquals(180, StockPhoto.rotationFor(4))
        assertEquals(90, StockPhoto.rotationFor(5))
        assertEquals(90, StockPhoto.rotationFor(6))
        assertEquals(270, StockPhoto.rotationFor(7))
        assertEquals(270, StockPhoto.rotationFor(8))
        // Unknown or absent: leave it alone rather than guess.
        assertEquals(0, StockPhoto.rotationFor(0))
        assertEquals(0, StockPhoto.rotationFor(99))
    }

    @Test
    fun `only the mirrored orientations are mirrored`() {
        assertFalse(StockPhoto.mirrorFor(1))
        assertTrue(StockPhoto.mirrorFor(2))
        assertFalse(StockPhoto.mirrorFor(3))
        assertTrue(StockPhoto.mirrorFor(4))
        assertTrue(StockPhoto.mirrorFor(5))
        assertFalse(StockPhoto.mirrorFor(6))
        assertTrue(StockPhoto.mirrorFor(7))
        assertFalse(StockPhoto.mirrorFor(8))
    }

    // --- what may be stored ------------------------------------------------

    @Test
    fun `the ceiling is the figure the rules enforce`() {
        assertEquals(81_920, StockPhoto.MAX_BYTES)
        assertTrue(StockPhoto.fits(StockPhoto.MAX_BYTES))
        assertFalse(StockPhoto.fits(StockPhoto.MAX_BYTES + 1))
        assertFalse(StockPhoto.fits(0))
    }

    @Test
    fun `only a WebP is recognised`() {
        assertTrue(StockPhoto.isWebP(webp(64)))
        assertFalse(StockPhoto.isWebP(ByteArray(64)))
        assertFalse(StockPhoto.isWebP("RIFFxxxxJPEG".toByteArray()))
        assertFalse(StockPhoto.isWebP("RIFF".toByteArray()))
    }

    @Test
    fun `a refusal is a sentence, and a storable photo has none`() {
        assertNull(StockPhoto.refusal(webp(1_024)))
        assertEquals(StockPhoto.NOT_AN_IMAGE, StockPhoto.refusal(null))
        assertEquals(StockPhoto.NOT_AN_IMAGE, StockPhoto.refusal(ByteArray(0)))
        assertEquals(StockPhoto.NOT_AN_IMAGE, StockPhoto.refusal(ByteArray(2_048)))
        assertEquals(StockPhoto.WILL_NOT_FIT, StockPhoto.refusal(webp(StockPhoto.MAX_BYTES)))
    }

    @Test
    fun `the quality ladder descends and stops before the picture stops meaning anything`() {
        assertEquals(StockPhoto.QUALITY_LADDER.sortedDescending(), StockPhoto.QUALITY_LADDER)
        assertTrue(StockPhoto.QUALITY_LADDER.last() >= 30)
        assertTrue(StockPhoto.QUALITY_LADDER.first() <= 90)
    }

    // --- the cache decision ------------------------------------------------

    @Test
    fun `a matching revision is served from the cache and spends no read`() {
        assertEquals(PhotoAction.USE_CACHE, StockPhoto.action(true, cachedRev = 4.0, rowRev = 4.0))
    }

    @Test
    fun `a superseded revision is fetched, never served`() {
        assertEquals(PhotoAction.FETCH, StockPhoto.action(true, cachedRev = 3.0, rowRev = 4.0))
    }

    @Test
    fun `nothing cached means fetch`() {
        assertEquals(PhotoAction.FETCH, StockPhoto.action(true, cachedRev = null, rowRev = 1.0))
    }

    @Test
    fun `a row with no photo shows nothing and fetches nothing`() {
        assertEquals(PhotoAction.NONE, StockPhoto.action(false, cachedRev = 9.0, rowRev = 9.0))
        assertEquals(PhotoAction.NONE, StockPhoto.action(false, cachedRev = null, rowRev = 0.0))
    }

    @Test
    fun `a cached revision ahead of the row is this device's own write, not a stale one`() {
        // The revision is monotonic, so the only way to hold a higher one is
        // to have written it before the listener caught up.
        assertEquals(PhotoAction.USE_CACHE, StockPhoto.action(true, cachedRev = 5.0, rowRev = 4.0))
    }

    // --- the write plan ----------------------------------------------------

    @Test
    fun `setting a photo writes both documents, one revision higher, with no movement`() {
        val plan = StockWrite.setPhoto(
            record = record(hasPhoto = false, photoRev = 2.0),
            storedQuantity = 9.0,
            storedReorderLevel = 3.0,
            storedPhotoRev = 2.0,
            image = StockPhotoImage(webp(1_000), width = 800, height = 600),
            author = author,
            at = 1_700_000_000_000L
        ) as StockPhotoPlan.Write

        assertEquals(3.0, plan.rev, 0.0)
        assertEquals("gateMotors|SIE1000", plan.stockDocId)
        assertEquals("gateMotors|SIE1000", plan.photoDocId)

        assertEquals(true, plan.stock["hasPhoto"])
        assertEquals(3.0, plan.stock["photoRev"])
        assertEquals(StockWrite.LAST_ACTION_PHOTO, plan.stock["lastAction"])

        // The stored numbers, re-asserted: a photo never disturbs a count.
        assertEquals(9.0, plan.stock["q"])
        assertEquals(3.0, plan.stock["min"])

        val photo = plan.photo!!
        assertEquals("gateMotors|SIE1000", photo["key"])
        assertEquals(3.0, photo["rev"])
        assertEquals(800.0, photo["w"])
        assertEquals(600.0, photo["h"])
        assertEquals(author.uid, photo["byUid"])
        assertEquals(ServerTimestamp, photo["serverAt"])
        assertTrue(photo["bytes"] is ByteArray)
    }

    @Test
    fun `a photo write carries no price field into a Worker-readable document`() {
        val plan = StockWrite.setPhoto(
            record = record(), storedQuantity = 7.0, storedReorderLevel = 2.0,
            storedPhotoRev = 0.0,
            image = StockPhotoImage(webp(512), 400, 300), author = author, at = 1L
        ) as StockPhotoPlan.Write

        for (field in listOf("dealer", "contractor", "client", "gst")) {
            assertFalse(field, plan.stock.containsKey(field))
            assertFalse(field, plan.photo!!.containsKey(field))
        }
    }

    @Test
    fun `an image that cannot be stored is refused before anything is written`() {
        val refused = StockWrite.setPhoto(
            record = record(), storedQuantity = 7.0, storedReorderLevel = 2.0,
            storedPhotoRev = 0.0,
            image = StockPhotoImage(webp(StockPhoto.MAX_BYTES), 800, 800),
            author = author, at = 1L
        )
        assertEquals(StockPhotoPlan.Refused(StockPhoto.WILL_NOT_FIT), refused)

        val notAnImage = StockWrite.setPhoto(
            record = record(), storedQuantity = 7.0, storedReorderLevel = 2.0,
            storedPhotoRev = 0.0,
            image = StockPhotoImage(ByteArray(64), 10, 10), author = author, at = 1L
        )
        assertEquals(StockPhotoPlan.Refused(StockPhoto.NOT_AN_IMAGE), notAnImage)
    }

    @Test
    fun `removing a photo deletes the document and advances the revision anyway`() {
        val plan = StockWrite.removePhoto(
            record = record(hasPhoto = true, photoRev = 4.0),
            storedQuantity = 5.0,
            storedReorderLevel = 1.0,
            storedPhotoRev = 4.0,
            hasStoredPhoto = true,
            author = author,
            at = 2L
        ) as StockPhotoPlan.Write

        assertNull("the photo document is deleted, not emptied", plan.photo)
        assertEquals(false, plan.stock["hasPhoto"])
        // Still advances, so a device holding the old copy knows it is stale.
        assertEquals(5.0, plan.stock["photoRev"])
        assertEquals(5.0, plan.rev, 0.0)
        assertEquals(5.0, plan.stock["q"])
        assertEquals(1.0, plan.stock["min"])
    }

    @Test
    fun `removing a photo from a row that has none writes nothing`() {
        assertEquals(
            StockPhotoPlan.NoChange,
            StockWrite.removePhoto(
                record = record(hasPhoto = false),
                storedQuantity = 7.0, storedReorderLevel = 2.0, storedPhotoRev = 0.0,
                hasStoredPhoto = false, author = author, at = 1L
            )
        )
    }

    @Test
    fun `a photo change never touches off or pinned`() {
        val plan = StockWrite.setPhoto(
            record = record().copy(pinned = true, archived = true),
            storedQuantity = 7.0, storedReorderLevel = 2.0, storedPhotoRev = 0.0,
            image = StockPhotoImage(webp(256), 100, 100), author = author, at = 1L
        ) as StockPhotoPlan.Write

        assertFalse("archiving is not a photo's business", plan.stock.containsKey("off"))
        assertFalse("pinning is not a photo's business", plan.stock.containsKey("pinned"))
        assertFalse(plan.stock.containsKey("pinOrder"))
    }
}
