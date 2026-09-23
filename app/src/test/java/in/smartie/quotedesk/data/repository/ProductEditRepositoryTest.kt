package `in`.smartie.quotedesk.data.repository

import `in`.smartie.quotedesk.data.mapping.DocData
import `in`.smartie.quotedesk.data.model.ProductRecord
import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.domain.ProductWrite
import `in`.smartie.quotedesk.domain.Role
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two reads, the merge between them, and the write.
 *
 * **The reads are the point.** The save sends every field the rule names, so
 * any field nobody edited is one this code writes back. Taking those from the
 * screen would mean a sheet left open while somebody else corrected a rate
 * would quietly undo them — and that is exactly the failure the catalogue
 * importer already causes at scale, which is why no import is being run to
 * fix the units.
 *
 * **Two documents, not one.** Seeding once wrote products at the legacy
 * `group|model` id and the migration left them there, while V8C4 reads only
 * `group__model`. The write goes to the canonical one; the legacy one is read
 * so that a canonical document created from it arrives complete.
 */
class ProductEditRepositoryTest {

    private val owner = Member(uid = "uid_owner", name = "Mudassir", role = Role.OWNER)
    private val manager = Member(uid = "uid_staff", name = "Manager", role = Role.STAFF)

    private val record = ProductRecord(
        documentId = "gateMotors__SIE1000",
        key = "gateMotors|SIE1000",
        group = "gateMotors",
        seedModel = "SIE1000",
        model = "SIE1000",
        name = "Sliding gate motor",
        unit = "each",
        gst = 18.0,
        dealer = 18500.0,
        client = 25900.0
    )

    private val canonical = mapOf<String, Any?>(
        "id" to "gateMotors|SIE1000", "key" to "gateMotors|SIE1000",
        "group" to "gateMotors", "seedModel" to "SIE1000", "model" to "SIE1000",
        "name" to "Sliding gate motor", "unit" to "each", "gst" to 18.0,
        "dealer" to 18500.0, "contractor" to null, "client" to 25900.0,
        "categoryId" to "cat-sliding", "active" to true
    )

    /** Firestore, reduced to the two things a product save does to it. */
    private class FakeStore(val documents: MutableMap<String, Map<String, Any?>>) : ProductStore {
        val reads = mutableListOf<String>()
        val writes = mutableListOf<Pair<String, Map<String, Any?>>>()

        override suspend fun <T> transaction(body: (ProductTransaction) -> T): T =
            body(object : ProductTransaction {
                override fun read(docId: String): DocData? {
                    reads += docId
                    return documents[docId]?.let { DocData(docId, it) }
                }

                override fun write(docId: String, data: Map<String, Any?>) {
                    writes += docId to data
                }
            })
    }

    private fun storeOf(vararg documents: Pair<String, Map<String, Any?>>) =
        FakeStore(mutableMapOf(*documents))

    @Test
    fun `an unedited field is written from the fresh read, not from the open sheet`() = runTest {
        // The sheet opened when the dealer rate was 18500 and somebody has
        // since corrected it to 21000. Changing only the name must carry the
        // 21000.
        val store = storeOf("gateMotors__SIE1000" to canonical + ("dealer" to 21000.0))
        val repository = ProductEditRepository(store) { 1_758_600_000_000L }

        val written = repository.save(
            member = owner,
            record = record,
            draft = ProductWrite.draftOf(record).copy(name = "Sliding gate motor 1000 kg")
        )

        assertTrue(written)
        assertEquals(21000.0, store.writes.single().second["dealer"])
        assertEquals("Sliding gate motor 1000 kg", store.writes.single().second["name"])
    }

    @Test
    fun `a null contractor reaches the write, because nothing filters it out`() = runTest {
        // `PartyStore` drops nulls before writing, and copying that here would
        // take the key off the document and hand V8C4 back its seed fallback.
        val store = storeOf("gateMotors__SIE1000" to canonical)
        val repository = ProductEditRepository(store) { 1L }

        repository.save(owner, record, ProductWrite.draftOf(record).copy(dealer = "19000"))

        val (_, data) = store.writes.single()
        assertTrue("the key must be present", data.containsKey("contractor"))
        assertNull(data["contractor"])
    }

    @Test
    fun `a product still at the legacy id is written to the canonical one`() = runTest {
        // V8C4 reads only `group__model`, so writing back to the pipe document
        // would leave the PWA on a stale rate.
        val legacy = record.copy(documentId = "gateMotors|SIE1000")
        val store = storeOf("gateMotors|SIE1000" to canonical)
        val repository = ProductEditRepository(store) { 1L }

        repository.save(owner, legacy, ProductWrite.draftOf(legacy).copy(dealer = "19000"))

        assertEquals("gateMotors__SIE1000", store.writes.single().first)
        assertTrue("both documents are read", store.reads.containsAll(
            listOf("gateMotors|SIE1000", "gateMotors__SIE1000")
        ))
    }

    @Test
    fun `and it carries what the legacy document held into the new one`() = runTest {
        // The canonical document does not exist yet, so a merge preserves
        // nothing: losing `categoryId` would move the product to the Other
        // shelf.
        val legacy = record.copy(documentId = "gateMotors|SIE1000")
        val store = storeOf(
            "gateMotors|SIE1000" to canonical + mapOf("spec" to "1000 kg, 230 V", "kg" to 1000.0)
        )
        val repository = ProductEditRepository(store) { 1L }

        repository.save(owner, legacy, ProductWrite.draftOf(legacy).copy(dealer = "19000"))

        val (_, data) = store.writes.single()
        assertEquals("cat-sliding", data["categoryId"])
        assertEquals("1000 kg, 230 V", data["spec"])
        assertEquals(1000.0, data["kg"])
    }

    @Test
    fun `where the canonical document exists, its values win over the legacy one`() = runTest {
        val legacy = record.copy(documentId = "gateMotors|SIE1000")
        val store = storeOf(
            "gateMotors|SIE1000" to canonical + ("dealer" to 18500.0),
            "gateMotors__SIE1000" to canonical + ("dealer" to 21000.0)
        )
        val repository = ProductEditRepository(store) { 1L }

        repository.save(owner, legacy, ProductWrite.draftOf(legacy).copy(name = "Motor"))

        assertEquals(21000.0, store.writes.single().second["dealer"])
    }

    @Test
    fun `an unchanged sheet writes nothing`() = runTest {
        val store = storeOf("gateMotors__SIE1000" to canonical)
        val repository = ProductEditRepository(store) { 1L }

        assertFalse(repository.save(owner, record, ProductWrite.draftOf(record)))
        assertTrue(store.writes.isEmpty())
    }

    @Test
    fun `a Manager's save is refused in the person's own words, not as a permission error`() =
        runTest {
            val store = storeOf("gateMotors__SIE1000" to canonical)
            val repository = ProductEditRepository(store) { 1L }

            val failure = runCatching {
                repository.save(manager, record, ProductWrite.draftOf(record).copy(dealer = "1"))
            }.exceptionOrNull()

            assertEquals(ProductWrite.CANNOT_EDIT, failure?.message)
            assertTrue(store.writes.isEmpty())
        }

    @Test
    fun `the author and the time are stamped from the member and the clock`() = runTest {
        val store = storeOf("gateMotors__SIE1000" to canonical)
        val repository = ProductEditRepository(store) { 1_758_600_000_000L }

        repository.save(owner, record, ProductWrite.draftOf(record).copy(dealer = "19000"))

        val (_, data) = store.writes.single()
        assertEquals("Mudassir", data["by"])
        assertEquals("uid_owner", data["byUid"])
        assertEquals(1_758_600_000_000L, data["updated"])
    }
}
