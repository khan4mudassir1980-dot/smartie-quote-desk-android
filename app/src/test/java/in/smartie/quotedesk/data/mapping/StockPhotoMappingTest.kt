package `in`.smartie.quotedesk.data.mapping

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading the photo metadata off a stock row, and the photo document itself.
 *
 * Built from [DocData] directly rather than from a JSON fixture: image bytes
 * are not a thing JSON holds, and the point of these is the byte field.
 */
class StockPhotoMappingTest {

    private val image = "RIFF".toByteArray() + ByteArray(4) + "WEBP".toByteArray() + ByteArray(40)

    @Test
    fun `a stock row carries its photo metadata and nothing else about it`() {
        val row = DocData(
            id = "gateMotors|SIE1000",
            fields = mapOf(
                "key" to "gateMotors|SIE1000", "q" to 7, "min" to 2,
                "hasPhoto" to true, "photoRev" to 3
            )
        ).toStockRecord()

        assertTrue(row.hasPhoto)
        assertEquals(3.0, row.photoRev, 0.0)
    }

    @Test
    fun `a row the PWA wrote has no photo, and says so without guessing`() {
        val row = DocData(
            id = "gateMotors|SIE2000",
            fields = mapOf("key" to "gateMotors|SIE2000", "q" to 4, "min" to 0)
        ).toStockRecord()

        assertFalse(row.hasPhoto)
        assertEquals(0.0, row.photoRev, 0.0)
    }

    @Test
    fun `legacy tolerance applies to the photo fields as much as to the rest`() {
        // Imported rows have stored numbers as strings and booleans as 0/1
        // for years; the photo fields go through the same tolerant readers.
        val row = DocData(
            id = "gateMotors|SIE3000",
            fields = mapOf("key" to "gateMotors|SIE3000", "hasPhoto" to 1, "photoRev" to "6")
        ).toStockRecord()

        assertTrue(row.hasPhoto)
        assertEquals(6.0, row.photoRev, 0.0)
    }

    @Test
    fun `the photo document maps to its record`() {
        val photo = DocData(
            id = "gateMotors|SIE1000",
            fields = mapOf(
                "key" to "gateMotors|SIE1000", "bytes" to image,
                "w" to 800, "h" to 600, "rev" to 3,
                "by" to "Asha", "byUid" to "uid_staff", "at" to 1_700_000_000_000L
            )
        ).toStockPhotoRecord()!!

        assertEquals("gateMotors|SIE1000", photo.documentId)
        assertEquals("gateMotors|SIE1000", photo.key)
        assertEquals(800, photo.width)
        assertEquals(600, photo.height)
        assertEquals(3.0, photo.rev, 0.0)
        assertEquals("uid_staff", photo.byUid)
        assertTrue(photo.bytes.contentEquals(image))
    }

    @Test
    fun `a photo document with no usable bytes is not a photo`() {
        assertNull(DocData(id = "x", fields = mapOf("key" to "x", "rev" to 1)).toStockPhotoRecord())
        assertNull(
            DocData(id = "x", fields = mapOf("bytes" to ByteArray(0), "rev" to 1))
                .toStockPhotoRecord()
        )
        // A string where bytes belong is not coerced into an image.
        assertNull(
            DocData(id = "x", fields = mapOf("bytes" to "not an image")).toStockPhotoRecord()
        )
    }

    @Test
    fun `two photo records are equal by their content, not their identity`() {
        val fields = mapOf(
            "key" to "k", "bytes" to image, "w" to 8, "h" to 6, "rev" to 1,
            "by" to "Asha", "byUid" to "u", "at" to 5L
        )
        val first = DocData(id = "k", fields = fields).toStockPhotoRecord()!!
        val second = DocData(id = "k", fields = fields + ("bytes" to image.copyOf()))
            .toStockPhotoRecord()!!

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }
}
