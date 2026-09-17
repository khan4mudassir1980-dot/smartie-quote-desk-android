package `in`.smartie.quotedesk.data.mapping

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeysTest {

    @Test
    fun `a slash in a model never reaches a document path`() {
        assertEquals("hwWheel|SIEBAL58H_V", Keys.sanitiseDocId("hwWheel|SIEBAL58H/V"))
        assertEquals("_", Keys.sanitiseDocId(""))
        assertEquals("_", Keys.sanitiseDocId("."))
        assertEquals("__", Keys.sanitiseDocId(".."))
    }

    @Test
    fun `the canonical product id is one document per product`() {
        assertEquals("gateMotors__SIE1000", Keys.productDocId("gateMotors", "SIE1000"))
        assertEquals("hwWheel__SIEBAL58H_V", Keys.productDocId("hwWheel", "SIEBAL58H/V"))
        assertEquals("gateMotors|SIE1000", Keys.productKey("gateMotors", "SIE1000"))
    }

    /**
     * The table in `fixtures/product_doc_ids.json` is the one the staging
     * importer's own test reads, so a divergence between Kotlin and
     * `tools/catalogue-import/lib/keys.mjs` fails on both sides rather than
     * going unnoticed until a native write lands on a second document.
     *
     * This is the regression itself: `productDocId` replaced only `/`, while
     * the PWA's rule — the one staging's document ids were written with — also
     * replaces `.`, `#`, `$`, `[` and `]`.
     */
    @Test
    fun `every canonical id matches the one the staging importer wrote`() {
        val cases = docIdFixture()
        assertTrue("The fixture must cover every replaced character", cases.size >= 7)
        for (case in cases) {
            assertEquals(
                "${case.group}|${case.seedModel} (${case.character}, ${case.source})",
                case.documentId,
                Keys.productDocId(case.group, case.seedModel)
            )
        }
    }

    @Test
    fun `each replaced character is covered by at least one case`() {
        val covered = docIdFixture().map { it.seedModel }.joinToString("")
        for (character in listOf('/', '.', '#', '$', '[', ']')) {
            assertTrue("No fixture case contains $character", character in covered)
        }
    }

    private data class DocIdCase(
        val group: String,
        val seedModel: String,
        val documentId: String,
        val character: String,
        val source: String
    )

    private fun docIdFixture(): List<DocIdCase> {
        val stream = requireNotNull(
            javaClass.classLoader?.getResourceAsStream("fixtures/product_doc_ids.json")
        ) { "Missing fixture file: fixtures/product_doc_ids.json" }
        val root = stream.bufferedReader().use { JsonParser.parseReader(it) }.asJsonObject
        return root.getAsJsonArray("cases").map { element ->
            val case = element.asJsonObject
            DocIdCase(
                group = case.get("group").asString,
                seedModel = case.get("seedModel").asString,
                documentId = case.get("documentId").asString,
                character = case.get("character").asString,
                source = case.get("source").asString
            )
        }
    }

    @Test
    fun `both legacy id schemes split back into group and model`() {
        assertEquals("gateMotors" to "SIE1000", Keys.splitProductKey("gateMotors|SIE1000"))
        assertEquals("gateMotors" to "SIE1000", Keys.splitProductKey("gateMotors__SIE1000"))
        assertNull(Keys.splitProductKey("nothing"))
        assertTrue(Keys.isLegacyProductDocId("gateMotors|SIE1000"))
        assertFalse(Keys.isLegacyProductDocId("gateMotors__SIE1000"))
    }

    @Test
    fun `emails are folded before they are compared`() {
        assertEquals("asha@example.invalid", Keys.normaliseEmail(" ASHA@Example.Invalid "))
        assertEquals("", Keys.normaliseEmail(null))
    }

    @Test
    fun `generated ids carry the PWA prefix and are unique`() {
        val first = Keys.generateId("ta_")
        val second = Keys.generateId("ta_")
        assertTrue(first.startsWith("ta_"))
        assertFalse(first == second)
    }
}
