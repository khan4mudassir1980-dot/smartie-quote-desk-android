package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.model.PurchaseRecord
import `in`.smartie.quotedesk.data.model.UrgencyV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The two lists the Purchase tab draws, and the order they are drawn in. */
class PurchaseBoardTest {

    private fun record(
        id: String,
        createdAt: Long = 0L,
        updatedAt: Long = 0L,
        status: String = "Needed",
        urgency: UrgencyV2 = UrgencyV2.NORMAL,
        received: Boolean = false,
        receivedQuantity: Double? = null,
        receivedAt: Long = 0L,
        deleted: Boolean = false,
        byUid: String = ""
    ) = PurchaseRecord(
        id = id,
        name = id,
        quantity = 1.0,
        urgency = urgency,
        status = status,
        byUid = byUid,
        createdAt = createdAt,
        updatedAt = updatedAt,
        received = received,
        receivedQuantity = receivedQuantity,
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

    // --- urgency ------------------------------------------------------------

    @Test
    fun `red is above yellow is above green`() {
        val board = PurchaseBoard.active(
            listOf(
                record("pr_green", urgency = UrgencyV2.NORMAL, createdAt = 9_000),
                record("pr_red", urgency = UrgencyV2.CRITICAL, createdAt = 1_000),
                record("pr_yellow", urgency = UrgencyV2.URGENT, createdAt = 5_000)
            )
        )
        // The newest is green and the oldest is red, so this fails on any
        // comparator that still puts the clock first.
        assertEquals(listOf("pr_red", "pr_yellow", "pr_green"), board.map { it.id })
    }

    @Test
    fun `inside one urgency the newest is still first`() {
        val board = PurchaseBoard.active(
            listOf(
                record("pr_r_old", urgency = UrgencyV2.CRITICAL, createdAt = 1_000),
                record("pr_g_new", urgency = UrgencyV2.NORMAL, createdAt = 8_000),
                record("pr_r_new", urgency = UrgencyV2.CRITICAL, createdAt = 3_000),
                record("pr_y_old", urgency = UrgencyV2.URGENT, createdAt = 2_000),
                record("pr_g_old", urgency = UrgencyV2.NORMAL, createdAt = 4_000),
                record("pr_y_new", urgency = UrgencyV2.URGENT, createdAt = 6_000)
            )
        )
        assertEquals(
            listOf("pr_r_new", "pr_r_old", "pr_y_new", "pr_y_old", "pr_g_new", "pr_g_old"),
            board.map { it.id }
        )
    }

    @Test
    fun `a partly received requirement keeps its place in its own colour`() {
        // Five of ten arrived: still open, still urgent, and it must not drop
        // below a green one just because something came.
        val board = PurchaseBoard.active(
            listOf(
                record("pr_green", urgency = UrgencyV2.NORMAL, createdAt = 9_000),
                record(
                    "pr_part",
                    urgency = UrgencyV2.CRITICAL,
                    createdAt = 1_000,
                    receivedQuantity = 5.0
                )
            )
        )
        assertEquals(listOf("pr_part", "pr_green"), board.map { it.id })
    }

    @Test
    fun `the rank is explicit, so reordering the enum cannot reorder the board`() {
        assertEquals(0, UrgencyV2.CRITICAL.rank)
        assertEquals(1, UrgencyV2.URGENT.rank)
        assertEquals(2, UrgencyV2.NORMAL.rank)
        // The wire values and the labels are untouched by any of this.
        assertEquals("critical", UrgencyV2.CRITICAL.wireValue)
        assertEquals("urgent", UrgencyV2.URGENT.wireValue)
        assertEquals("normal", UrgencyV2.NORMAL.wireValue)
        assertEquals("Needed, but not now", UrgencyV2.NORMAL.label)
    }

    @Test
    fun `history stays chronological, not urgent`() {
        // Nothing closed is waiting for anybody, so the colour it once had
        // must not reorder the record of what happened.
        val board = PurchaseBoard.closed(
            listOf(
                record(
                    "pr_green_late",
                    urgency = UrgencyV2.NORMAL,
                    status = "Received",
                    received = true,
                    receivedAt = 9_000
                ),
                record(
                    "pr_red_early",
                    urgency = UrgencyV2.CRITICAL,
                    status = "Received",
                    received = true,
                    receivedAt = 2_000
                )
            )
        )
        assertEquals(listOf("pr_green_late", "pr_red_early"), board.map { it.id })
    }

    @Test
    fun `an empty board is empty rather than an error`() {
        assertTrue(PurchaseBoard.active(emptyList()).isEmpty())
        assertTrue(PurchaseBoard.closed(emptyList()).isEmpty())
    }

    // --- mine first --------------------------------------------------------

    private val me = "uid_me"
    private val them = "uid_them"

    @Test
    fun `my own requirements come before everybody else's`() {
        // Six people add to this list. Somebody opening the tab is usually
        // looking for the thing they raised, and theirs was scrolling away.
        val board = PurchaseBoard.active(
            listOf(
                record("pr_theirs_red", urgency = UrgencyV2.CRITICAL, byUid = them),
                record("pr_mine_green", urgency = UrgencyV2.NORMAL, byUid = me),
                record("pr_theirs_green", urgency = UrgencyV2.NORMAL, byUid = them),
                record("pr_mine_red", urgency = UrgencyV2.CRITICAL, byUid = me)
            ),
            viewerUid = me
        )

        assertEquals(
            listOf("pr_mine_red", "pr_mine_green", "pr_theirs_red", "pr_theirs_green"),
            board.map { it.id }
        )
    }

    @Test
    fun `my green sits above somebody else's red`() {
        // The blunt statement of the rule, because it is the one that will
        // look wrong to somebody who expects colour to win outright.
        val board = PurchaseBoard.active(
            listOf(
                record("pr_theirs", urgency = UrgencyV2.CRITICAL, byUid = them),
                record("pr_mine", urgency = UrgencyV2.NORMAL, byUid = me)
            ),
            viewerUid = me
        )
        assertEquals(listOf("pr_mine", "pr_theirs"), board.map { it.id })
    }

    @Test
    fun `urgency still orders each group, red then yellow then green`() {
        val board = PurchaseBoard.active(
            listOf(
                record("pr_mine_green", urgency = UrgencyV2.NORMAL, byUid = me),
                record("pr_mine_red", urgency = UrgencyV2.CRITICAL, byUid = me),
                record("pr_mine_yellow", urgency = UrgencyV2.URGENT, byUid = me),
                record("pr_theirs_green", urgency = UrgencyV2.NORMAL, byUid = them),
                record("pr_theirs_red", urgency = UrgencyV2.CRITICAL, byUid = them),
                record("pr_theirs_yellow", urgency = UrgencyV2.URGENT, byUid = them)
            ),
            viewerUid = me
        )

        assertEquals(
            listOf(
                "pr_mine_red", "pr_mine_yellow", "pr_mine_green",
                "pr_theirs_red", "pr_theirs_yellow", "pr_theirs_green"
            ),
            board.map { it.id }
        )
    }

    @Test
    fun `a row with no recorded creator is somebody else's`() {
        // The PWA wrote requirements without a byUid. `"" == ""` would make
        // every one of them everybody's.
        val board = PurchaseBoard.active(
            listOf(
                record("pr_orphan", urgency = UrgencyV2.CRITICAL),
                record("pr_mine", urgency = UrgencyV2.NORMAL, byUid = me)
            ),
            viewerUid = me
        )
        assertEquals(listOf("pr_mine", "pr_orphan"), board.map { it.id })
        assertFalse(PurchaseBoard.isMine(record("pr_orphan"), me))
    }

    @Test
    fun `a viewer with no uid gets the order the board had before`() {
        // A session that has not resolved must not quietly claim every
        // authorless row.
        val records = listOf(
            record("pr_orphan", urgency = UrgencyV2.NORMAL),
            record("pr_red", urgency = UrgencyV2.CRITICAL, byUid = them)
        )
        assertEquals(
            PurchaseBoard.active(records).map { it.id },
            PurchaseBoard.active(records, viewerUid = "").map { it.id }
        )
        assertEquals(listOf("pr_red", "pr_orphan"), PurchaseBoard.active(records).map { it.id })
    }

    @Test
    fun `the id still breaks a tie inside my own group`() {
        // A PWA import writes many rows with one timestamp. A comparator that
        // called them equal would let a keyed LazyColumn jump under the thumb.
        val board = PurchaseBoard.active(
            listOf(
                record("pr_a", createdAt = 7_000, byUid = me),
                record("pr_c", createdAt = 7_000, byUid = me),
                record("pr_b", createdAt = 7_000, byUid = me)
            ),
            viewerUid = me
        )
        assertEquals(listOf("pr_c", "pr_b", "pr_a"), board.map { it.id })
    }

    @Test
    fun `mine first never resurrects a removed or closed requirement`() {
        val board = PurchaseBoard.active(
            listOf(
                record("pr_gone", byUid = me, deleted = true),
                record("pr_done", byUid = me, received = true, status = "Received"),
                record("pr_open", byUid = me)
            ),
            viewerUid = me
        )
        assertEquals(listOf("pr_open"), board.map { it.id })
    }

    @Test
    fun `the closed list is chronological, not mine-first`() {
        // Nothing in History is waiting for anybody, so whose it is does not
        // change where it sits.
        val board = PurchaseBoard.closed(
            listOf(
                record("pr_theirs", received = true, status = "Received", receivedAt = 9_000, byUid = them),
                record("pr_mine", received = true, status = "Received", receivedAt = 1_000, byUid = me)
            )
        )
        assertEquals(listOf("pr_theirs", "pr_mine"), board.map { it.id })
    }
}

