package `in`.smartie.quotedesk.data.repository

import `in`.smartie.quotedesk.data.mapping.Keys
import `in`.smartie.quotedesk.data.model.StockRecord
import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.domain.Role
import `in`.smartie.quotedesk.domain.StockPhoto
import `in`.smartie.quotedesk.domain.StockRemoval
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Removing an item, through the transaction.
 *
 * The three writes — history, photo, row — are one commit, and the tests that
 * matter most are the negative ones: a failure leaves everything as it was,
 * and no removal ever writes a movement.
 */
class StockRemovalWriteTest {

    private class Written(val docId: String, val data: Map<String, Any?>?)

    /**
     * A store that can be made to fail at the last step, so atomicity is
     * asserted rather than assumed: the fake discards everything the body
     * recorded when the commit throws, exactly as Firestore would.
     */
    private class FakeStore(
        private val stored: Map<String, Any?>?,
        private val alreadyStopped: Set<String> = emptySet(),
        private val failOnDelete: Boolean = false
    ) : StockStore {
        val stopped = mutableListOf<Written>()
        val stockDeletes = mutableListOf<String>()
        val photoDeletes = mutableListOf<String>()
        val movements = mutableListOf<String>()
        val stockWrites = mutableListOf<String>()
        var stoppedChecks = 0

        override suspend fun <T> transaction(body: (StockTransaction) -> T): T {
            val outcome = runCatching {
                body(object : StockTransaction {
                    override fun readStock(docId: String) = stored
                    override fun writeStock(docId: String, data: Map<String, Any?>, merge: Boolean) {
                        stockWrites += docId
                    }
                    override fun writeMovement(docId: String, data: Map<String, Any?>) {
                        movements += docId
                    }
                    override fun writePhoto(docId: String, data: Map<String, Any?>) =
                        throw AssertionError("a removal must not write a photo")
                    override fun deletePhoto(docId: String) { photoDeletes += docId }
                    override fun deleteStock(docId: String) {
                        if (failOnDelete) throw IllegalStateException("Network unavailable")
                        stockDeletes += docId
                    }
                    override fun writeStopped(docId: String, data: Map<String, Any?>) {
                        stopped += Written(docId, data)
                    }
                    override fun stoppedExists(docId: String): Boolean {
                        stoppedChecks++
                        return docId in alreadyStopped
                    }
                })
            }
            if (outcome.isFailure) {
                // Nothing a rolled-back transaction did is visible afterwards.
                stopped.clear(); photoDeletes.clear(); stockDeletes.clear()
                throw outcome.exceptionOrNull()!!
            }
            return outcome.getOrThrow()
        }
    }

    private val owner = Member(uid = "uid_owner", name = "Omar", role = Role.OWNER)
    private val admin = Member(uid = "uid_admin", name = "Asha", role = Role.ADMIN)
    private val staff = Member(uid = "uid_staff", name = "Sam", role = Role.STAFF)
    private val worker = Member(uid = "uid_worker", name = "Wes", role = Role.WORKER)

    private val key = Keys.productKey("gateMotors", "SIE1000")

    private fun record(
        hasPhoto: Boolean = false,
        manual: Boolean = false,
        archived: Boolean = false
    ) = StockRecord(
        documentId = Keys.stockDocId(key),
        key = key,
        quantity = 7.0,
        reorderLevel = 2.0,
        name = "Sliding gate motor",
        model = "SIE1000",
        group = "gateMotors",
        unit = "each",
        manual = manual,
        archived = archived,
        hasPhoto = hasPhoto,
        photoRev = if (hasPhoto) 3.0 else 0.0
    )

    private fun row(q: Double = 7.0, hasPhoto: Boolean = false): Map<String, Any?> =
        mapOf("key" to key, "q" to q, "min" to 2.0, "hasPhoto" to hasPhoto)

    private fun repository(store: StockStore) =
        StockWriteRepository(store, now = { 1_700_000_000_000L }, newMovementId = { "mv_1" })

    // --- the happy path ------------------------------------------------------

    @Test
    fun `removing writes history and deletes the row, in one commit`() = runTest {
        val store = FakeStore(stored = row())

        val result = repository(store).removeFromStock(admin, record())

        assertEquals(StockWriteResult.WRITTEN, result)
        assertEquals(1, store.stopped.size)
        assertEquals(listOf(Keys.stockDocId(key)), store.stockDeletes)
    }

    @Test
    fun `removing is not a movement`() = runTest {
        val store = FakeStore(stored = row())
        repository(store).removeFromStock(admin, record())

        assertTrue("nothing moved; the row ceased to exist", store.movements.isEmpty())
        assertTrue("and the row is deleted, not written", store.stockWrites.isEmpty())
    }

    @Test
    fun `a photographed row loses its photo document too`() = runTest {
        val store = FakeStore(stored = row(hasPhoto = true))

        repository(store).removeFromStock(admin, record(hasPhoto = true))

        assertEquals(listOf(StockPhoto.documentId(key)), store.photoDeletes)
    }

    @Test
    fun `a row without a photo spends no delete on one`() = runTest {
        val store = FakeStore(stored = row())
        repository(store).removeFromStock(admin, record())
        assertTrue(store.photoDeletes.isEmpty())
    }

    @Test
    fun `a manual item is removed the same way`() = runTest {
        val store = FakeStore(stored = row())

        repository(store).removeFromStock(admin, record(manual = true))

        assertEquals(true, store.stopped.single().data!!["manual"])
        assertEquals(1, store.stockDeletes.size)
    }

    @Test
    fun `the quantity recorded is the one read inside the transaction`() = runTest {
        // The screen may be showing something older.
        val store = FakeStore(stored = row(q = 11.0))
        repository(store).removeFromStock(admin, record())
        assertEquals(11.0, store.stopped.single().data!!["q"])
    }

    @Test
    fun `an item already gone writes nothing at all`() = runTest {
        val store = FakeStore(stored = null)

        val result = repository(store).removeFromStock(admin, record())

        assertEquals(StockWriteResult.NO_CHANGE, result)
        assertTrue(store.stopped.isEmpty())
        assertTrue(store.stockDeletes.isEmpty())
    }

    // --- atomicity -----------------------------------------------------------

    @Test
    fun `a failure leaves the item and its photo exactly as they were`() = runTest {
        val store = FakeStore(stored = row(hasPhoto = true), failOnDelete = true)

        val outcome = runCatching {
            repository(store).removeFromStock(admin, record(hasPhoto = true))
        }

        assertTrue("the caller is told", outcome.isFailure)
        assertTrue("no history without a removal", store.stopped.isEmpty())
        assertTrue("the photo survives", store.photoDeletes.isEmpty())
        assertTrue("and so does the row", store.stockDeletes.isEmpty())
    }

    // --- who may ------------------------------------------------------------

    @Test
    fun `an Owner and an Administrator may remove`() = runTest {
        for (member in listOf(owner, admin)) {
            val store = FakeStore(stored = row())
            assertEquals(
                StockWriteResult.WRITTEN,
                repository(store).removeFromStock(member, record())
            )
        }
    }

    @Test
    fun `the displayed Manager and Staff may not`() = runTest {
        // The permission is unchanged: it is the one that always governed
        // stopping tracking, which was Owner and Administrator.
        for (member in listOf(staff, worker)) {
            val store = FakeStore(stored = row())
            assertTrue(
                runCatching { repository(store).removeFromStock(member, record()) }.isFailure
            )
            assertTrue(store.stockDeletes.isEmpty())
        }
    }

    // --- legacy conversion ---------------------------------------------------

    @Test
    fun `a legacy stopped row converts to history and goes`() = runTest {
        val store = FakeStore(stored = row())

        val result = repository(store).convertLegacyStopped(admin, record(archived = true))

        assertEquals(StockWriteResult.WRITTEN, result)
        assertEquals(1, store.stopped.size)
        assertEquals(listOf(Keys.stockDocId(key)), store.stockDeletes)
    }

    @Test
    fun `it converts under a deterministic id`() = runTest {
        val store = FakeStore(stored = row())
        repository(store).convertLegacyStopped(admin, record(archived = true))

        assertEquals(
            StockRemoval.legacyEventId(Keys.stockDocId(key)),
            store.stopped.single().docId
        )
    }

    @Test
    fun `a retry after a partial failure finishes rather than doubling up`() = runTest {
        val eventId = StockRemoval.legacyEventId(Keys.stockDocId(key))
        val store = FakeStore(stored = row(), alreadyStopped = setOf(eventId))

        val result = repository(store).convertLegacyStopped(admin, record(archived = true))

        assertEquals(StockWriteResult.WRITTEN, result)
        assertTrue("the history entry is already there", store.stopped.isEmpty())
        assertEquals("but the row still has to go", 1, store.stockDeletes.size)
    }

    @Test
    fun `an ordinary active row is never converted`() = runTest {
        val store = FakeStore(stored = row())

        val result = repository(store).convertLegacyStopped(admin, record(archived = false))

        assertEquals(StockWriteResult.NO_CHANGE, result)
        assertTrue("an item nobody stopped must not be deleted", store.stockDeletes.isEmpty())
        assertTrue(store.stopped.isEmpty())
    }

    @Test
    fun `a failed conversion leaves the legacy row recoverable`() = runTest {
        val store = FakeStore(stored = row(), failOnDelete = true)

        val outcome = runCatching {
            repository(store).convertLegacyStopped(admin, record(archived = true))
        }

        assertTrue(outcome.isFailure)
        assertTrue("still there to try again", store.stockDeletes.isEmpty())
        assertFalse("and no orphan history entry", store.stopped.isNotEmpty())
    }

    @Test
    fun `a fresh removal never reuses a legacy id`() = runTest {
        val store = FakeStore(stored = row())
        repository(store).removeFromStock(admin, record())

        val id = store.stopped.single().docId
        assertTrue("a fresh removal is its own event", id.startsWith(StockRemoval.EVENT_PREFIX))
        assertFalse(id.startsWith(StockRemoval.LEGACY_PREFIX))
    }
}
