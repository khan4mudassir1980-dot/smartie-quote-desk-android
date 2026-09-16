package `in`.smartie.quotedesk.data.repository

import `in`.smartie.quotedesk.data.mapping.Fixtures
import `in`.smartie.quotedesk.data.mapping.toProductRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which of a logical key's two documents the catalogue shows (audit P3).
 *
 * The mapper and this rule together are what stop the duplicate-key crash: one
 * row per logical key, keyed by document id.
 */
class CanonicalProductTest {

    private fun product(id: String) = Fixtures.loadOne("products.json", id).toProductRecord()

    @Test
    fun `a migrated document wins even when the legacy one was touched later`() {
        val legacy = product("boom|BB6")
        val migrated = product("boom__BB6")
        assertEquals(legacy.key, migrated.key)
        assertTrue(legacy.updatedAt > migrated.updatedAt)
        assertEquals(2, migrated.schemaVersion)

        val chosen = canonicalProduct(listOf(legacy, migrated))
        assertEquals("boom__BB6", chosen.documentId)
        assertEquals("Boom barrier 6 m", chosen.name)
    }

    @Test
    fun `before migration the most recently updated document still wins`() {
        val seeded = product("gateMotors|SIE1000")
        val edited = product("gateMotors__SIE1000")
        assertEquals(0, seeded.schemaVersion)
        assertEquals(0, edited.schemaVersion)

        val chosen = canonicalProduct(listOf(seeded, edited))
        assertEquals("gateMotors__SIE1000", chosen.documentId)
        assertEquals("Sliding gate motor 1000 kg (edited)", chosen.name)
    }

    @Test
    fun `a single document is returned as it is`() {
        val glass = product("glass__TG12")
        assertEquals(glass, canonicalProduct(listOf(glass)))
    }
}
