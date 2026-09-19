package `in`.smartie.quotedesk.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** How a cached photo is named, which is the whole of the cache's safety. */
class StockPhotoDiskTest {

    private val key = "gateMotors|SIE1000"
    private val other = "boomBarriers|BB200"

    @Test
    fun `a name carries the row and the revision`() {
        val name = StockPhotoDisk.nameFor(key, 3.0)!!
        assertTrue(StockPhotoDisk.belongsTo(name, key))
        assertEquals(3.0, StockPhotoDisk.revOf(name)!!, 0.0)
    }

    @Test
    fun `two rows never share a name`() {
        assertNotEquals(StockPhotoDisk.nameFor(key, 1.0), StockPhotoDisk.nameFor(other, 1.0))
        assertFalse(StockPhotoDisk.belongsTo(StockPhotoDisk.nameFor(other, 1.0)!!, key))
    }

    @Test
    fun `two revisions of one row never share a name`() {
        assertNotEquals(StockPhotoDisk.nameFor(key, 1.0), StockPhotoDisk.nameFor(key, 2.0))
    }

    @Test
    fun `the same row and revision always produce the same name`() {
        assertEquals(StockPhotoDisk.nameFor(key, 7.0), StockPhotoDisk.nameFor(key, 7.0))
    }

    @Test
    fun `a name survives an id full of the characters a file name dislikes`() {
        val awkward = "gate motors #1|SIE/1000 \"special\" *?<>:"
        val name = StockPhotoDisk.nameFor(awkward, 2.0)!!
        assertTrue("no separator may reach the file name", name.none { it == '/' || it == '\\' })
        assertTrue(StockPhotoDisk.belongsTo(name, awkward))
    }

    @Test
    fun `a very long id still produces a short name`() {
        val name = StockPhotoDisk.nameFor("x".repeat(4_000), 1.0)!!
        assertTrue("a file name has a length limit", name.length < 64)
    }

    @Test
    fun `a revision that cannot be named is refused rather than rounded`() {
        // Rounding two revisions to one name is exactly how a replaced photo
        // would come back, so these are not cached on disk at all.
        assertNull(StockPhotoDisk.nameFor(key, 1.5))
        assertNull(StockPhotoDisk.nameFor(key, -1.0))
        assertNull(StockPhotoDisk.nameFor(key, Double.NaN))
        assertNull(StockPhotoDisk.nameFor(key, Double.POSITIVE_INFINITY))
    }

    @Test
    fun `revision zero is nameable`() {
        assertEquals(0.0, StockPhotoDisk.revOf(StockPhotoDisk.nameFor(key, 0.0)!!)!!, 0.0)
    }

    @Test
    fun `something that is not a cache file has no revision`() {
        assertNull(StockPhotoDisk.revOf("notes.txt"))
        assertNull(StockPhotoDisk.revOf("abc.webp"))
        assertNull(StockPhotoDisk.revOf("abc_.webp"))
        assertNull(StockPhotoDisk.revOf("abc_notanumber.webp"))
        assertNull(StockPhotoDisk.revOf("_3.webp"))
    }

    @Test
    fun `a partial write is never mistaken for a cache file`() {
        val partial = StockPhotoDisk.nameFor(key, 1.0)!! + StockPhotoDisk.PARTIAL
        assertNull(StockPhotoDisk.revOf(partial))
        assertFalse(StockPhotoDisk.belongsTo(partial, key))
    }

    @Test
    fun `the bound is forty mebibytes`() {
        assertEquals(41_943_040L, StockPhotoDisk.MAX_BYTES)
    }
}
