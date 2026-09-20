package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.model.PurchaseRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The two lists the Purchase tab draws, and the order they are drawn in. */
class PurchaseBoardTest {

    private fun record(
        id: String,
        createdAt: Long = 0L,
        updatedAt: Long = 0L,
        status: String = "Needed",
        received: Boolean = false,
        receivedAt: Long = 0L,
        deleted: Boolean = false
    ) = PurchaseRecord(
        id = id,
        name = id,
        quantity = 1.0,
        status = status,
        createdAt = createdAt,
        updatedAt = updatedAt,
        received = received,
        receivedAt = receivedAt,
        deleted = deleted
    )

    @Test
    fun `the newest requirement is at the top of the active list`() {
        val board = PurchaseBoard.active(
            listOf(
                record("pr_old", createdAt = 1_000),
                record("pr_new", createdAt = 3_000),
                record("pr_mid", createdAt = 2_000)
            )
        )
        assertEquals(listOf("pr_new", "pr_mid", "pr_old"), board.map { it.id })
    }

    @Test
    fun `a legacy row with no created time is sorted last and never dropped`() {
        // `pr_critical_no_created` has no `t`. The reader falls back to
        // `updated`; a row carrying neither still has to appear.
        val board = PurchaseBoard.active(
            listOf(record("pr_nothing"), record("pr_dated", createdAt = 5_000))
        )
        assertEquals(listOf("pr_dated", "pr_nothing"), board.map { it.id })
    }

    @Test
    fun `two requirements added in the same millisecond keep a stable order`() {
        val same = listOf(
            record("pr_a", createdAt = 7_000),
            record("pr_b", createdAt = 7_000),
            record("pr_c", createdAt = 7_000)
        )
        // Same input in any order must give the same output, or a LazyColumn
        // reorders itself under the thumb between recompositions.
        assertEquals(
            PurchaseBoard.active(same).map { it.id },
            PurchaseBoard.active(same.reversed()).map { it.id }
        )
        assertEquals(listOf("pr_c", "pr_b", "pr_a"), PurchaseBoard.active(same).map { it.id })
    }

    @Test
    fun `a received requirement leaves the active list`() {
        val records = listOf(
            record("pr_open", createdAt = 1_000),
            record("pr_done", createdAt = 2_000, status = "Received", received = true)
        )
        assertEquals(listOf("pr_open"), PurchaseBoard.active(records).map { it.id })
        assertEquals(listOf("pr_done"), PurchaseBoard.closed(records).map { it.id })
    }

    @Test
    fun `a legacy Cancelled row is closed, and nothing here ever writes one`() {
        // The PWA writes `Cancelled`; N4 has no Cancel action. Such a row must
        // still read and display correctly.
        val records = listOf(record("pr_cancelled", createdAt = 1_000, status = "Cancelled"))
        assertTrue(PurchaseBoard.active(records).isEmpty())
        assertEquals(listOf("pr_cancelled"), PurchaseBoard.closed(records).map { it.id })
    }

    @Test
    fun `a removed requirement leaves both lists`() {
        // The defect this guards: `isOpen` is `!deleted && !isClosed`, so a
        // removed row that was never received is neither open nor closed.
        // Filtering the second list on `!isOpen` would put it back on screen.
        val removed = record("pr_gone", createdAt = 9_000, deleted = true)
        val records = listOf(removed, record("pr_here", createdAt = 1_000))

        assertEquals(listOf("pr_here"), PurchaseBoard.active(records).map { it.id })
        assertTrue(
            "a removed requirement must not reappear as history",
            PurchaseBoard.closed(records).isEmpty()
        )
    }

    @Test
    fun `a removed requirement that had been received also leaves both lists`() {
        val records = listOf(
            record(
                "pr_gone",
                createdAt = 9_000,
                status = "Received",
                received = true,
                deleted = true
            )
        )
        assertTrue(PurchaseBoard.active(records).isEmpty())
        assertTrue(PurchaseBoard.closed(records).isEmpty())
    }

    @Test
    fun `history is ordered by when something was received, not when it was raised`() {
        val records = listOf(
            // Raised first, received last.
            record(
                "pr_slow",
                createdAt = 1_000,
                status = "Received",
                received = true,
                receivedAt = 9_000
            ),
            record(
                "pr_quick",
                createdAt = 5_000,
                status = "Received",
                received = true,
                receivedAt = 6_000
            )
        )
        assertEquals(listOf("pr_slow", "pr_quick"), PurchaseBoard.closed(records).map { it.id })
    }

    @Test
    fun `a closed row with no received time falls back to when it was touched`() {
        val record = record(
            "pr_legacy",
            createdAt = 1_000,
            updatedAt = 4_000,
            status = "Received",
            received = true
        )
        assertEquals(4_000L, PurchaseBoard.closedAt(record))
    }

    @Test
    fun `an empty board is empty rather than an error`() {
        assertTrue(PurchaseBoard.active(emptyList()).isEmpty())
        assertTrue(PurchaseBoard.closed(emptyList()).isEmpty())
    }
}
