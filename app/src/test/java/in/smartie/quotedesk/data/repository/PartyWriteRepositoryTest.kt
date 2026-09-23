package `in`.smartie.quotedesk.data.repository

import `in`.smartie.quotedesk.data.mapping.DocData
import `in`.smartie.quotedesk.data.model.PartyRecord
import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.domain.PartyDraft
import `in`.smartie.quotedesk.domain.PartyWrite
import `in`.smartie.quotedesk.domain.Role
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The party writer, through the transaction.
 *
 * The one that matters most is the one an emulator cannot stage on demand:
 * **a retry after an ambiguous failure must land on the same document.** The
 * commit went through, the acknowledgement did not, and the person presses
 * Save again. With an id minted per attempt that writes a second customer —
 * which is precisely N4.4's B2, and a customer is worse than a requirement
 * because every quotation ever issued points at one of the two.
 *
 * Stored `staff` is displayed **Manager**; stored `worker` is displayed
 * **Staff** and may not write a party at all.
 */
class PartyWriteRepositoryTest {

    private class FakeStore(
        private val stored: MutableMap<String, Map<String, Any?>> = mutableMapOf(),
        private val attempts: Int = 1
    ) : PartyStore {
        val writes = mutableListOf<Written>()
        var bodyRuns = 0

        class Written(val docId: String, val data: Map<String, Any?>, val merge: Boolean)

        /** Makes a committed write visible to the next call, as Firestore does. */
        fun commit() {
            writes.forEach { stored[it.docId] = it.data }
        }

        override suspend fun <T> transaction(body: (PartyTransaction) -> T): T {
            var last: T? = null
            repeat(attempts) {
                bodyRuns++
                // Firestore discards what a replayed attempt recorded.
                writes.clear()
                last = body(object : PartyTransaction {
                    override fun read(docId: String): DocData? =
                        stored[docId]?.let { DocData(docId, it) }

                    override fun write(docId: String, data: Map<String, Any?>, merge: Boolean) {
                        writes += Written(docId, data, merge)
                    }
                })
            }
            @Suppress("UNCHECKED_CAST")
            return last as T
        }
    }

    private val manager = Member(uid = "uid_staff", name = "Sam", role = Role.STAFF)
    private val owner = Member(uid = "uid_owner", name = "Mudassir", role = Role.OWNER)

    /** Stored `worker`, displayed Staff. */
    private val staff = Member(uid = "uid_worker", name = "Ravi", role = Role.WORKER)

    private val draft = PartyDraft(name = "Metro Glass", city = "Mumbai")

    private val sunriseDoc = mapOf(
        "id" to "c_1", "name" to "Sunrise Constructions", "city" to "Mumbai", "type" to "contractor"
    )
    private val sunrise = PartyRecord(id = "c_1", name = "Sunrise Constructions", city = "Mumbai")

    /** The throwable a write came back with, or null when it succeeded. */
    private suspend fun failureOf(body: suspend () -> Unit): Throwable? =
        runCatching { body() }.exceptionOrNull()

    // --- the retry that must not write twice --------------------------------------

    @Test
    fun `a retry with the same id lands on the same document, and is refused`() = runTest {
        val store = FakeStore()
        val writes = PartyWriteRepository(store, now = { 1_000L })

        // The first attempt commits, and its acknowledgement is lost.
        writes.create(manager, draft, id = "c_fixed")
        assertEquals("c_fixed", store.writes.single().docId)
        store.commit()

        // The person presses Save again with the same id, so the writer finds
        // the document and says so rather than writing a twin.
        val refusal = failureOf { writes.create(manager, draft, id = "c_fixed") }
        assertEquals(PartyWrite.ALREADY_EXISTS, refusal?.message)
        assertTrue("nothing was written", store.writes.isEmpty())
    }

    @Test
    fun `a fresh id each time is how two customers appear, which is why one is kept`() = runTest {
        // The behaviour the screen must not have, asserted so the reason the
        // id is minted once per form is visible rather than implied.
        val store = FakeStore()
        var next = 0
        val writes = PartyWriteRepository(store, now = { 1_000L }, newId = { "c_${next++}" })

        writes.create(manager, draft)
        store.commit()
        val first = store.writes.single().docId
        writes.create(manager, draft)
        val second = store.writes.single().docId

        assertEquals("c_0", first)
        assertEquals("c_1", second)
    }

    @Test
    fun `a replayed body reuses the timestamp it generated the first time`() = runTest {
        // Firestore may run the body more than once. `t` and `updated` are
        // taken outside it, so a replay cannot make one attempt disagree with
        // another about when this happened.
        var ticks = 0L
        val store = FakeStore(attempts = 3)
        val writes = PartyWriteRepository(store, now = { ticks++ })

        writes.create(manager, draft, id = "c_fixed")

        assertEquals(3, store.bodyRuns)
        assertEquals("now() is called once, outside the body", 1L, ticks)
        assertEquals(0L, store.writes.single().data["t"])
        assertEquals(0L, store.writes.single().data["updated"])
    }

    // --- what each role may write ---------------------------------------------------

    @Test
    fun `a Staff account cannot write a party at all`() = runTest {
        val writes = PartyWriteRepository(FakeStore())

        assertTrue(failureOf { writes.create(staff, draft) } is IllegalArgumentException)
        assertTrue(
            failureOf { writes.edit(staff, sunrise, draft) } is IllegalArgumentException
        )
    }

    @Test
    fun `a Manager corrects a party, sending no name and no archived key`() = runTest {
        val store = FakeStore(mutableMapOf("c_1" to sunriseDoc))
        val writes = PartyWriteRepository(store, now = { 2_000L })

        writes.edit(manager, sunrise, PartyDraft(name = "Sunrise Constructions", city = "Pune"))

        val data = store.writes.single().data
        assertEquals("Pune", data["city"])
        assertEquals("c_1", data["id"])
        assertEquals("Sam", data["upBy"])
        assertTrue("no name key", !data.containsKey("name"))
        assertTrue("no archived key", !data.containsKey("archived"))
    }

    @Test
    fun `and cannot archive one`() = runTest {
        val store = FakeStore(mutableMapOf("c_1" to sunriseDoc))
        val writes = PartyWriteRepository(store, now = { 2_000L })

        assertTrue(
            failureOf { writes.setArchived(manager, sunrise, true) } is IllegalArgumentException
        )
        assertTrue(store.writes.isEmpty())
    }

    @Test
    fun `an Owner archives one, and the stamp is theirs`() = runTest {
        val store = FakeStore(mutableMapOf("c_1" to sunriseDoc))
        val writes = PartyWriteRepository(store, now = { 2_000L })

        writes.setArchived(owner, sunrise, archived = true)

        val data = store.writes.single().data
        assertEquals(true, data["archived"])
        // Not whoever last edited the record — the person doing it now.
        assertEquals("Mudassir", data["upBy"])
        assertEquals("uid_owner", data["upUid"])
    }

    @Test
    fun `the plan is built from the document, not from the copy the screen held`() = runTest {
        // The screen is showing a party that has since been archived. A
        // Manager's correction must be refused on what is stored, not waved
        // through on a stale record.
        val store = FakeStore(mutableMapOf("c_1" to sunriseDoc + mapOf("archived" to true)))
        val writes = PartyWriteRepository(store, now = { 2_000L })

        val refusal = failureOf {
            writes.edit(manager, sunrise, PartyDraft(name = "Sunrise Constructions", city = "Pune"))
        }

        assertEquals(PartyWrite.ARCHIVED_IS_READ_ONLY, refusal?.message)
    }

    @Test
    fun `a party that vanished under the screen is not an error`() = runTest {
        val store = FakeStore()
        val writes = PartyWriteRepository(store, now = { 2_000L })

        val result = writes.edit(owner, sunrise, PartyDraft(name = "Gone", city = "Pune"))

        assertEquals(PartyWriteResult.NO_CHANGE, result)
        assertTrue(store.writes.isEmpty())
        assertNull(failureOf { writes.edit(owner, sunrise, PartyDraft(name = "Gone")) })
    }
}
