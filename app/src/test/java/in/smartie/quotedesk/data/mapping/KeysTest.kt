package `in`.smartie.quotedesk.data.mapping

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    /** All thirteen live in the fixture, so none can be quietly dropped. */
    @Test
    fun `every real catalogue model needing canonicalisation is covered`() {
        val real = docIdFixture().filter { it.source == "real" && it.seedModel != it.documentId.substringAfter("__") }
        assertEquals("The V8C4 extraction found thirteen", 13, real.size)
    }

    // --- stock ids -------------------------------------------------------

    /**
     * Stock is addressed by a **different scheme** from products: the logical
     * key with only `/` replaced, against the product id's `/ . # $ [ ]`. The
     * fixture carries both for every row, so using one where the other belongs
     * fails here rather than in Firestore.
     */
    @Test
    fun `a stock document id replaces only the path separator`() {
        val cases = stockIdFixture()
        assertTrue("The fixture must carry real catalogue keys", cases.size >= 10)
        for (case in cases) {
            assertEquals(case.key, case.stockDocId, Keys.stockDocId(case.key))
        }
    }

    @Test
    fun `the stock and product id schemes are never interchangeable`() {
        for (case in stockIdFixture()) {
            val stock = Keys.stockDocId(case.key)
            val product = Keys.productDocId(case.group, case.model)
            assertEquals(case.key, case.productDocId, product)
            assertNotEquals("${case.key} must not share an id between schemes", product, stock)
        }
    }

    @Test
    fun `a dot survives in a stock id and never in a product id`() {
        assertEquals("gate|SIE2.5MSMALL", Keys.stockDocId("gate|SIE2.5MSMALL"))
        assertEquals("gate__SIE2_5MSMALL", Keys.productDocId("gate", "SIE2.5MSMALL"))
        assertEquals("hwWheel|SIEBAL58H_V", Keys.stockDocId("hwWheel|SIEBAL58H/V"))
    }

    @Test
    fun `a movement document is addressed by its own id`() {
        val id = Keys.generateId("mv_")
        assertEquals(id, Keys.stockMoveDocId(id))
        assertTrue(id.startsWith("mv_"))
    }

    // --- fixtures --------------------------------------------------------

    private data class DocIdCase(
        val group: String,
        val seedModel: String,
        val documentId: String,
        val character: String,
        val source: String
    )

    private data class StockIdCase(
        val group: String,
        val model: String,
        val key: String,
        val stockDocId: String,
        val productDocId: String
    )

    private fun stockIdFixture(): List<StockIdCase> =
        fixtureCases("fixtures/stock_doc_ids.json").map { case ->
            StockIdCase(
                group = case.get("group").asString,
                model = case.get("model").asString,
                key = case.get("key").asString,
                stockDocId = case.get("stockDocId").asString,
                productDocId = case.get("productDocId").asString
            )
        }

    private fun docIdFixture(): List<DocIdCase> =
        fixtureCases("fixtures/product_doc_ids.json").map { case ->
            DocIdCase(
                group = case.get("group").asString,
                seedModel = case.get("seedModel").asString,
                documentId = case.get("documentId").asString,
                character = case.get("character").asString,
                source = case.get("source").asString
            )
        }

    private fun fixtureCases(path: String): List<JsonObject> {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream(path)) {
            "Missing fixture file: $path"
        }
        val root = stream.bufferedReader().use { JsonParser.parseReader(it) }.asJsonObject
        return root.getAsJsonArray("cases").map { it.asJsonObject }
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
