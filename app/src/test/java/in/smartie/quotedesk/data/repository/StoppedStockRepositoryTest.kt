package `in`.smartie.quotedesk.data.repository

import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.domain.Role
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Clearing stopped-item history.
 *
 * Two things are being held to account: that a list longer than one batch is
 * split rather than rejected, and that a run touches **only** the ids it was
 * handed. The fake records every id it is asked to delete, so anything this
 * reached that it should not have would show up.
 */
class StoppedStockRepositoryTest {

    private class FakeStore(private val failAfter: Int = Int.MAX_VALUE) : StoppedStockStore {
        val batches = mutableListOf<List<String>>()
        val deleted get() = batches.flatten()

        override suspend fun deleteBatch(ids: List<String>) {
            if (batches.size >= failAfter) throw IllegalStateException("Network unavailable")
            batches += ids
        }
    }

    private val owner = Member(uid = "uid_owner", name = "Omar", role = Role.OWNER)
    private val admin = Member(uid = "uid_admin", name = "Asha", role = Role.ADMIN)
    private val staff = Member(uid = "uid_staff", name = "Sam", role = Role.STAFF)
    private val worker = Member(uid = "uid_worker", name = "Wes", role = Role.WORKER)

    private fun ids(count: Int) = (1..count).map { "sr_$it" }

    // --- who may -------------------------------------------------------------

    @Test
    fun `an Owner and an Administrator may clear`() = runTest {
        for (member in listOf(owner, admin)) {
            val store = FakeStore()
            assertEquals(2, StoppedStockRepository(store).clear(member, ids(2)))
            assertEquals(ids(2), store.deleted)
        }
    }

    @Test
    fun `the displayed Manager and Staff may not, and nothing is attempted`() = runTest {
        for (member in listOf(staff, worker)) {
            val store = FakeStore()
            val failure = runCatching {
                StoppedStockRepository(store).clear(member, ids(2))
            }.exceptionOrNull()

            assertTrue(failure is IllegalArgumentException)
            assertTrue("a refused clear never reaches Firestore", store.batches.isEmpty())
        }
    }

    // --- batching ------------------------------------------------------------

    @Test
    fun `a list inside one batch is one commit`() = runTest {
        val store = FakeStore()
        StoppedStockRepository(store).clear(admin, ids(500))

        assertEquals(1, store.batches.size)
        assertEquals(500, store.batches.single().size)
    }

    @Test
    fun `a list over the cap is split rather than refused`() = runTest {
        // Firestore takes 500 writes in a batch and rejects the 501st.
        val store = FakeStore()
        val count = StoppedStockRepository(store).clear(admin, ids(1_201))

        assertEquals(1_201, count)
        assertEquals(listOf(500, 500, 201), store.batches.map { it.size })
        assertTrue(
            "no batch may exceed the cap",
            store.batches.all { it.size <= StoppedStockRepository.BATCH_LIMIT }
        )
        assertEquals("and every id goes exactly once", ids(1_201), store.deleted)
    }

    @Test
    fun `a failed batch leaves the rest in place and says so`() = runTest {
        val store = FakeStore(failAfter = 1)

        val failure = runCatching {
            StoppedStockRepository(store).clear(admin, ids(1_100))
        }.exceptionOrNull()

        assertTrue("the caller is told", failure is IllegalStateException)
        // The first batch is gone, the rest is untouched, and clearing again
        // finishes the job — every entry is independent of every other.
        assertEquals(500, store.deleted.size)
    }

    // --- what it touches -----------------------------------------------------

    @Test
    fun `nothing is clear from an empty list`() = runTest {
        val store = FakeStore()
        assertEquals(0, StoppedStockRepository(store).clear(admin, emptyList()))
        assertTrue("an empty clear spends no write", store.batches.isEmpty())
    }

    @Test
    fun `a blank or repeated id is not deleted twice`() = runTest {
        val store = FakeStore()
        val count = StoppedStockRepository(store)
            .clear(admin, listOf("sr_1", "sr_1", "", "  ", "sr_2"))

        assertEquals(2, count)
        assertEquals(listOf("sr_1", "sr_2"), store.deleted)
    }

    @Test
    fun `only the ids it was handed are deleted`() = runTest {
        // There is no query in this repository and no collection sweep, so a
        // stock row, a product or a movement cannot be reached from here.
        val store = FakeStore()
        StoppedStockRepository(store).clear(admin, listOf("sr_one", "sr_two"))

        assertEquals(listOf("sr_one", "sr_two"), store.deleted)
    }
}
