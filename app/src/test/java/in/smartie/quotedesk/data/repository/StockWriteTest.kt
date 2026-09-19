package `in`.smartie.quotedesk.data.repository

import `in`.smartie.quotedesk.data.mapping.Keys
import `in`.smartie.quotedesk.data.model.StockRecord
import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.domain.Permissions
import `in`.smartie.quotedesk.domain.Role
import `in`.smartie.quotedesk.domain.ServerTimestamp
import `in`.smartie.quotedesk.domain.StockEntry
import `in`.smartie.quotedesk.domain.StockWrite
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The transaction contract, driven through a fake store.
 *
 * The fake can **replay** the body, which is the only way to prove that the
 * movement id and `at` are generated once and reused — a real emulator will
 * not produce a retry on demand.
 */
class StockWriteTest {

    // --- fake Firestore ---------------------------------------------------

    private class Recorded(
        val docId: String,
        val data: Map<String, Any?>,
        val merge: Boolean = true
    )

    private class FakeStore(
        private val stored: Map<String, Map<String, Any?>> = emptyMap(),
        private val attempts: Int = 1
    ) : StockStore {
        /** One entry per attempt, so a replay can be inspected attempt by attempt. */
        val stockAttempts = mutableListOf<List<Recorded>>()
        val movementAttempts = mutableListOf<List<Recorded>>()
        var bodyRuns = 0

        @Suppress("UNCHECKED_CAST")
        override suspend fun <T> transaction(body: (StockTransaction) -> T): T {
            var last: T? = null
            repeat(attempts) {
                val stockWrites = mutableListOf<Recorded>()
                val movementWrites = mutableListOf<Recorded>()
                bodyRuns++
                last = body(object : StockTransaction {
                    override fun readStock(docId: String) = stored[docId]
                    override fun writeStock(docId: String, data: Map<String, Any?>, merge: Boolean) {
                        stockWrites += Recorded(docId, data, merge)
                    }
                    override fun writeMovement(docId: String, data: Map<String, Any?>) {
                        movementWrites += Recorded(docId, data)
                    }
                    // Photos are this fake's business only in so far as a
                    // quantity write must never reach them; StockPhotoWriteTest
                    // drives them properly.
                    override fun writePhoto(docId: String, data: Map<String, Any?>) =
                        throw AssertionError("a quantity write must not touch a photo")
                    override fun deletePhoto(docId: String) =
                        throw AssertionError("a quantity write must not delete a photo")

                    // A quantity change is not a removal, in either direction.
                    override fun deleteStock(docId: String) =
                        throw AssertionError("a quantity write must not delete the row")
                    override fun writeStopped(docId: String, data: Map<String, Any?>) =
                        throw AssertionError("a quantity write must not write history")
                    override fun stoppedExists(docId: String) = false
                })
                stockAttempts += stockWrites
                movementAttempts += movementWrites
            }
            return last as T
        }

        val stock: List<Recorded> get() = stockAttempts.lastOrNull().orEmpty()
        val movements: List<Recorded> get() = movementAttempts.lastOrNull().orEmpty()
    }

    // --- fixtures ---------------------------------------------------------

    private val admin = Member(uid = "uid_admin", name = "Asha", email = "a@x.invalid", role = Role.ADMIN)
    private val staff = Member(uid = "uid_staff", name = "Sam", email = "s@x.invalid", role = Role.STAFF)
    private val worker = Member(uid = "uid_worker", name = "Wes", email = "w@x.invalid", role = Role.WORKER)

    private val key = Keys.productKey("gateMotors", "SIE1000")
    private val record = StockRecord(
        documentId = Keys.stockDocId(key),
        key = key,
        quantity = 7.0,
        reorderLevel = 2.0,
        name = "Sliding gate motor",
        model = "SIE1000",
        group = "gateMotors",
        unit = "each"
    )

    /** Every call returns a different value, so a second call is detectable. */
    private class Counter(private val prefix: String) {
        var calls = 0
        fun next(): String { calls++; return "$prefix$calls" }
    }

    private fun repository(
        store: StockStore,
        clock: () -> Long = { 1_700_000_000_000L },
        ids: () -> String = Counter("mv_")::next
    ) = StockWriteRepository(store = store, now = clock, newMovementId = ids)

    private fun storedWith(quantity: Any) = mapOf(record.documentId to mapOf<String, Any?>("q" to quantity))

    // --- contract: the stored quantity is what counts ---------------------

    @Test
    fun `the delta is applied to the stored quantity, not the caller's`() = runTest {
        // The screen thinks 7; Firestore says 3 because somebody else took four.
        val store = FakeStore(storedWith(3.0))
        repository(store).adjust(admin, record, delta = 2.0)

        val movement = store.movements.single().data
        assertEquals(3.0, movement["prev"])
        assertEquals(5.0, movement["next"])
        assertEquals(2.0, movement["delta"])
        assertEquals(5.0, store.stock.single().data["q"])
    }

    @Test
    fun `a stored quantity held as a string is still read as a number`() = runTest {
        val store = FakeStore(storedWith("3"))
        repository(store).adjust(admin, record, delta = 1.0)
        assertEquals(3.0, store.movements.single().data["prev"])
        assertEquals(4.0, store.stock.single().data["q"])
    }

    @Test
    fun `a missing document falls back to the record's own quantity`() = runTest {
        val store = FakeStore()
        repository(store).adjust(admin, record, delta = 1.0)
        assertEquals(7.0, store.movements.single().data["prev"])
    }

    // --- contract: one id and one `at`, across retries ---------------------

    @Test
    fun `the movement id and at are generated once and reused on a replay`() = runTest {
        val store = FakeStore(storedWith(7.0), attempts = 3)
        val idCounter = Counter("mv_")
        var clockCalls = 0
        repository(store, clock = { clockCalls++; 1_700_000_000_000L + clockCalls }, ids = idCounter::next)
            .adjust(admin, record, delta = 1.0)

        assertEquals("the body must have replayed", 3, store.bodyRuns)
        assertEquals("one id for one action", 1, idCounter.calls)
        assertEquals("one clock reading for one action", 1, clockCalls)

        val movementIds = store.movementAttempts.map { it.single().data["id"] }
        val timestamps = store.movementAttempts.map { it.single().data["at"] }
        assertEquals("every attempt writes the same movement id", 1, movementIds.toSet().size)
        assertEquals("every attempt writes the same at", 1, timestamps.toSet().size)
        assertEquals(listOf("mv_1"), movementIds.toSet().toList())
    }

    @Test
    fun `a replay writes one movement document, not one per attempt`() = runTest {
        val store = FakeStore(storedWith(7.0), attempts = 4)
        repository(store).adjust(admin, record, delta = 1.0)
        store.movementAttempts.forEach { assertEquals(1, it.size) }
        assertEquals(1, store.movements.size)
    }

    // --- contract: below zero is rejected, not clamped ---------------------

    @Test
    fun `a delta that would go below zero is rejected and writes nothing`() = runTest {
        val store = FakeStore(storedWith(3.0))
        val failure = runCatching { repository(store).adjust(admin, record, delta = -4.0) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().startsWith(StockWrite.BELOW_ZERO_PREFIX))
        assertTrue("nothing may be written", store.stock.isEmpty() && store.movements.isEmpty())
    }

    @Test
    fun `reaching exactly zero is allowed`() = runTest {
        val store = FakeStore(storedWith(3.0))
        repository(store).adjust(admin, record, delta = -3.0)
        assertEquals(0.0, store.stock.single().data["q"])
        assertEquals(-3.0, store.movements.single().data["delta"])
    }

    @Test
    fun `a zero delta writes nothing at all`() = runTest {
        val store = FakeStore(storedWith(7.0))
        assertEquals(StockWriteResult.NO_CHANGE, repository(store).adjust(admin, record, delta = 0.0))
        assertTrue(store.stock.isEmpty() && store.movements.isEmpty())
    }

    // --- contract: the exact V8C4 field map --------------------------------

    @Test
    fun `a movement carries the V8C4 fields, a signed delta and no qty`() = runTest {
        val store = FakeStore(storedWith(7.0))
        repository(store).adjust(admin, record, delta = -2.0, note = "Sent to site")

        val movement = store.movements.single()
        assertEquals("mv_1", movement.docId)
        assertEquals(
            setOf(
                "id", "key", "group", "model", "name", "action",
                "prev", "delta", "next", "min", "note", "by", "byUid", "at", "serverAt"
            ),
            movement.data.keys
        )
        assertFalse("there is no qty field", movement.data.containsKey("qty"))
        assertEquals("out", movement.data["action"])
        assertEquals(-2.0, movement.data["delta"])
        assertEquals(7.0, movement.data["prev"])
        assertEquals(5.0, movement.data["next"])
        assertEquals("Sent to site", movement.data["note"])
        assertEquals("Asha", movement.data["by"])
        assertEquals("uid_admin", movement.data["byUid"])
        assertTrue(movement.data["at"] is Long)
        assertTrue(movement.data["serverAt"] === ServerTimestamp)
    }

    @Test
    fun `a stock write carries no price or tax field`() = runTest {
        val store = FakeStore(storedWith(7.0))
        repository(store).adjust(admin, record, delta = 1.0)
        for (forbidden in listOf("dealer", "contractor", "client", "gst", "spec")) {
            assertFalse(forbidden, store.stock.single().data.containsKey(forbidden))
        }
    }

    @Test
    fun `the descriptive name rides along so a Worker sees more than a model`() = runTest {
        val store = FakeStore(storedWith(7.0))
        repository(store).adjust(admin, record, delta = 1.0)
        assertEquals("Sliding gate motor", store.stock.single().data["name"])
        assertEquals("Sliding gate motor", store.movements.single().data["name"])
    }

    @Test
    fun `a record with no name never blanks a name already stored`() = runTest {
        val store = FakeStore(storedWith(7.0))
        repository(store).adjust(admin, record.copy(name = ""), delta = 1.0)
        assertFalse(store.stock.single().data.containsKey("name"))
    }

    @Test
    fun `a blank note leaves the stored note alone but labels the movement`() = runTest {
        val store = FakeStore(storedWith(7.0))
        repository(store).adjust(admin, record, delta = 1.0)
        assertFalse(store.stock.single().data.containsKey("stockNote"))
        assertEquals(Permissions.DEFAULT_STOCK_NOTE, store.movements.single().data["note"])
    }

    @Test
    fun `a stock write is a merge and carries q, min and the audit metadata`() = runTest {
        val store = FakeStore(storedWith(7.0))
        repository(store).adjust(admin, record, delta = 1.0)
        val stock = store.stock.single()
        assertTrue(stock.merge)
        assertEquals(record.documentId, stock.docId)
        assertEquals(8.0, stock.data["q"])
        assertEquals(2.0, stock.data["min"])
        assertEquals("in", stock.data["lastAction"])
        assertEquals("uid_admin", stock.data["byUid"])
        assertTrue(stock.data["t"] is Long)
        assertTrue(stock.data["serverAt"] === ServerTimestamp)
    }

    // --- edit: set, min, note-only, no-op ----------------------------------

    @Test
    fun `changing the quantity writes a set movement`() = runTest {
        val store = FakeStore(storedWith(7.0))
        repository(store).edit(admin, record, quantity = 20.0, reorderLevel = 2.0, note = "Recount")
        val movement = store.movements.single().data
        assertEquals("set", movement["action"])
        assertEquals(7.0, movement["prev"])
        assertEquals(20.0, movement["next"])
        assertEquals(13.0, movement["delta"])
        assertEquals("set", store.stock.single().data["lastAction"])
    }

    @Test
    fun `changing only the minimum writes a min movement with no movement in quantity`() = runTest {
        val store = FakeStore(storedWith(7.0))
        repository(store).edit(admin, record, quantity = 7.0, reorderLevel = 5.0)
        val movement = store.movements.single().data
        assertEquals("min", movement["action"])
        assertEquals(7.0, movement["prev"])
        assertEquals(7.0, movement["next"])
        assertEquals(0.0, movement["delta"])
        assertEquals(5.0, movement["min"])
        assertEquals(5.0, store.stock.single().data["min"])
    }

    /** Decision 2: the note is shared state, but nothing moved. */
    @Test
    fun `a note-only edit saves the note and audit metadata and writes no movement`() = runTest {
        val store = FakeStore(storedWith(7.0))
        val result = repository(store).edit(admin, record, quantity = 7.0, reorderLevel = 2.0, note = "Top shelf")

        assertEquals(StockWriteResult.WRITTEN, result)
        assertTrue("a note-only edit logs nothing", store.movements.isEmpty())
        val stock = store.stock.single().data
        assertEquals("Top shelf", stock["stockNote"])
        assertEquals("Asha", stock["by"])
        assertEquals("uid_admin", stock["byUid"])
        assertTrue(stock["t"] is Long)
        assertTrue(stock["serverAt"] === ServerTimestamp)
    }

    @Test
    fun `a note-only edit is never disguised as a minimum change`() = runTest {
        val store = FakeStore(storedWith(7.0))
        repository(store).edit(admin, record, quantity = 7.0, reorderLevel = 2.0, note = "Top shelf")
        assertEquals(StockWrite.LAST_ACTION_NOTE, store.stock.single().data["lastAction"])
        assertTrue(StockWrite.LAST_ACTION_NOTE != StockWrite.ACTION_MIN)
    }

    @Test
    fun `an edit that changes nothing and has no note writes nothing`() = runTest {
        val store = FakeStore(storedWith(7.0))
        val result = repository(store).edit(admin, record, quantity = 7.0, reorderLevel = 2.0, note = "  ")
        assertEquals(StockWriteResult.NO_CHANGE, result)
        assertTrue(store.stock.isEmpty() && store.movements.isEmpty())
    }

    @Test
    fun `re-typing the note already stored is also a no-op`() = runTest {
        val store = FakeStore(storedWith(7.0))
        val result = repository(store)
            .edit(admin, record.copy(note = "Top shelf"), quantity = 7.0, reorderLevel = 2.0, note = "Top shelf")
        assertEquals(StockWriteResult.NO_CHANGE, result)
        assertTrue(store.stock.isEmpty())
    }

    @Test
    fun `a negative exact quantity is refused`() = runTest {
        val store = FakeStore(storedWith(7.0))
        val failure = runCatching {
            repository(store).edit(admin, record, quantity = -1.0, reorderLevel = 2.0)
        }.exceptionOrNull()
        assertEquals(StockEntry.NEGATIVE_QUANTITY, failure?.message)
        assertTrue(store.stock.isEmpty())
    }

    // --- permissions ------------------------------------------------------

    @Test
    fun `a Worker cannot adjust, edit or create`() = runTest {
        val store = FakeStore(storedWith(7.0))
        val repo = repository(store)
        assertTrue(runCatching { repo.adjust(worker, record, 1.0) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { repo.edit(worker, record, 7.0, 3.0) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals("no transaction may even open", 0, store.bodyRuns)
    }

    @Test
    fun `Staff change the reorder level but never the exact quantity`() = runTest {
        val allowed = FakeStore(storedWith(7.0))
        repository(allowed).edit(staff, record, quantity = 7.0, reorderLevel = 6.0)
        assertEquals("min", allowed.movements.single().data["action"])

        val refused = FakeStore(storedWith(7.0))
        val failure = runCatching {
            repository(refused).edit(staff, record, quantity = 99.0, reorderLevel = 2.0)
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(refused.stock.isEmpty() && refused.movements.isEmpty())
    }

    // --- pin and stop tracking --------------------------------------------

    @Test
    fun `pinning is a merge write and never a movement`() = runTest {
        val store = FakeStore(storedWith(7.0))
        repository(store).togglePin(staff, record)

        assertTrue("a pin moves nothing, so it logs nothing", store.movements.isEmpty())
        val stock = store.stock.single()
        assertTrue(stock.merge)
        assertEquals(true, stock.data["pinned"])
        assertEquals(StockWrite.LAST_ACTION_PIN, stock.data["lastAction"])
        // pinOrder is the moment of pinning, so pins sort oldest-first.
        assertEquals(1_700_000_000_000.0, stock.data["pinOrder"])
        // q and min are re-asserted as numbers so the rules accept the write.
        assertEquals(7.0, stock.data["q"])
        assertEquals(2.0, stock.data["min"])
    }

    @Test
    fun `unpinning clears the order rather than leaving a stale one`() = runTest {
        val store = FakeStore(storedWith(7.0))
        repository(store).togglePin(staff, record.copy(pinned = true, pinOrder = 99.0))
        val stock = store.stock.single()
        assertEquals(false, stock.data["pinned"])
        assertEquals(0.0, stock.data["pinOrder"])
    }

    @Test
    fun `a Worker cannot pin`() = runTest {
        val store = FakeStore(storedWith(7.0))
        val failure = runCatching { repository(store).togglePin(worker, record) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals(0, store.bodyRuns)
    }

    @Test
    fun `stopping tracking switches the row off and logs an archive movement`() = runTest {
        val store = FakeStore(storedWith(7.0))
        repository(store).stopTracking(admin, record, note = "Discontinued")

        val stock = store.stock.single()
        assertEquals(true, stock.data["off"])
        assertEquals(StockWrite.ACTION_ARCHIVE, stock.data["lastAction"])

        val movement = store.movements.single().data
        assertEquals(StockWrite.ACTION_ARCHIVE, movement["action"])
        assertEquals(7.0, movement["prev"])
        assertEquals(7.0, movement["next"])
        assertEquals(0.0, movement["delta"])
        assertEquals("Discontinued", movement["note"])
    }

    @Test
    fun `stopping tracking is an Administrator action`() = runTest {
        val store = FakeStore(storedWith(7.0))
        val failure = runCatching { repository(store).stopTracking(staff, record) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals(0, store.bodyRuns)
    }

    @Test
    fun `stopping tracking a row already off writes nothing`() = runTest {
        val store = FakeStore(storedWith(7.0))
        val result = repository(store).stopTracking(admin, record.copy(archived = true))
        assertEquals(StockWriteResult.NO_CHANGE, result)
        assertTrue(store.stock.isEmpty() && store.movements.isEmpty())
    }

    // --- create -----------------------------------------------------------

    @Test
    fun `creating writes the whole V8C4 shape plus the name, and an add movement`() = runTest {
        val store = FakeStore()
        val entry = StockEntry.manual(model = "Shed padlock", name = "Brass padlock")
        repository(store).create(admin, entry, quantity = 4.0, reorderLevel = 1.0, note = "Bought locally")

        val stock = store.stock.single()
        assertFalse("a new document is written whole, not merged", stock.merge)
        assertEquals("manualstock|shed_padlock", stock.docId)
        assertEquals(
            setOf(
                "key", "group", "model", "name", "q", "min", "off", "t", "lastAction",
                "pinned", "pinOrder", "manual", "manualName", "manualModel",
                "categoryId", "unit", "linkedKey", "stockNote", "by", "byUid", "serverAt"
            ),
            stock.data.keys
        )
        assertEquals(4.0, stock.data["q"])
        assertEquals(false, stock.data["off"])
        assertEquals(true, stock.data["manual"])
        assertEquals("add", stock.data["lastAction"])

        val movement = store.movements.single().data
        assertEquals("add", movement["action"])
        assertEquals(0.0, movement["prev"])
        assertEquals(4.0, movement["delta"])
        assertEquals(4.0, movement["next"])
    }

    @Test
    fun `creating something already tracked is refused rather than merged onto`() = runTest {
        val entry = StockEntry.manual(model = "Shed padlock", name = "")
        val store = FakeStore(mapOf(entry.documentId to mapOf<String, Any?>("q" to 9.0)))
        val failure = runCatching {
            repository(store).create(admin, entry, quantity = 1.0, reorderLevel = 0.0)
        }.exceptionOrNull()
        assertEquals(StockWrite.ALREADY_EXISTS, failure?.message)
        assertTrue(store.stock.isEmpty())
    }

    @Test
    fun `creating a blank item is refused`() = runTest {
        val store = FakeStore()
        val failure = runCatching {
            repository(store).create(admin, StockEntry.manual(model = "", name = ""), 1.0, 0.0)
        }.exceptionOrNull()
        assertEquals(StockEntry.BLANK, failure?.message)
    }

    @Test
    fun `a created catalogue row is addressed by the stock scheme`() = runTest {
        val store = FakeStore()
        val entry = StockEntry(key = "gate|SIE2.5MSMALL", group = "gate", model = "SIE2.5MSMALL", name = "Small gate")
        repository(store).create(admin, entry, quantity = 1.0, reorderLevel = 0.0)
        assertEquals("gate|SIE2.5MSMALL", store.stock.single().docId)
        assertNull("the product scheme must not appear", store.stock.single().data["documentId"])
    }
}
