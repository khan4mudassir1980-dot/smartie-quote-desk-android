package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.mapping.Keys
import `in`.smartie.quotedesk.data.model.StockMove
import `in`.smartie.quotedesk.data.model.StockRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StockBoardTest {

    private fun record(
        model: String,
        quantity: Double = 5.0,
        reorderLevel: Double = 0.0,
        name: String = "",
        pinned: Boolean = false,
        pinOrder: Double = 0.0,
        note: String = "",
        manualName: String = "",
        manualModel: String = "",
        group: String = "gateMotors"
    ): StockRecord {
        val key = Keys.productKey(group, model)
        return StockRecord(
            documentId = Keys.stockDocId(key),
            key = key,
            quantity = quantity,
            reorderLevel = reorderLevel,
            name = name,
            model = model,
            group = group,
            pinned = pinned,
            pinOrder = pinOrder,
            note = note,
            manualName = manualName,
            manualModel = manualModel
        )
    }

    // --- status ----------------------------------------------------------

    @Test
    fun `out of stock only at zero`() {
        assertEquals(StockStatus.OUT, StockStatus.of(0.0, 2.0))
        assertEquals(StockStatus.OUT, StockStatus.of(-1.0, 2.0))
        assertEquals(StockStatus.LOW, StockStatus.of(0.5, 2.0))
        assertEquals(StockStatus.IN, StockStatus.of(0.5, 0.0))
    }

    @Test
    fun `low stock at or below the reorder level`() {
        assertEquals(StockStatus.LOW, StockStatus.of(5.0, 5.0))
        assertEquals(StockStatus.LOW, StockStatus.of(4.0, 5.0))
        assertEquals(StockStatus.IN, StockStatus.of(6.0, 5.0))
    }

    @Test
    fun `no reorder level never makes a row low`() {
        assertEquals(StockStatus.IN, StockStatus.of(1.0, 0.0))
        assertEquals(StockStatus.OUT, StockStatus.of(0.0, 0.0))
    }

    // --- pending never moves the numbers ---------------------------------

    @Test
    fun `a pending delta never changes the quantity or the status`() {
        val view = StockBoard.build(
            listOf(record("SIE1000", quantity = 5.0, reorderLevel = 2.0)),
            pending = mapOf("gateMotors|SIE1000" to -5.0)
        )
        val row = view.rows.single()
        assertEquals(5.0, row.quantity, 0.0)
        assertEquals(0.0, row.projected, 0.0)
        assertEquals(StockStatus.IN, row.status)
        assertTrue(row.hasPending)
    }

    @Test
    fun `a pending delta is reported separately so the screen can show both`() {
        val view = StockBoard.build(
            listOf(record("SIE1000", quantity = 12.0)),
            pending = mapOf("gateMotors|SIE1000" to 5.0)
        )
        val row = view.rows.single()
        assertEquals(12.0, row.quantity, 0.0)
        assertEquals(5.0, row.pending, 0.0)
        assertEquals(17.0, row.projected, 0.0)
    }

    @Test
    fun `a row with no pending delta reports none`() {
        val row = StockBoard.build(listOf(record("SIE1000"))).rows.single()
        assertFalse(row.hasPending)
        assertEquals(0.0, row.pending, 0.0)
    }

    // --- counts and filters ----------------------------------------------

    private val shelf = listOf(
        record("SIE1000", quantity = 9.0, reorderLevel = 2.0),
        record("SIE2000", quantity = 2.0, reorderLevel = 2.0),
        record("SIE3000", quantity = 0.0, reorderLevel = 2.0),
        record("SIE4000", quantity = 0.0, reorderLevel = 0.0),
        record("SIE5000", quantity = 4.0, reorderLevel = 0.0, pinned = true, pinOrder = 10.0)
    )

    @Test
    fun `the tiles count everything tracked`() {
        val view = StockBoard.build(shelf)
        assertEquals(5, view.tracked)
        assertEquals(1, view.low)
        assertEquals(2, view.out)
        assertEquals(1, view.pinnedCount)
    }

    @Test
    fun `the tiles hold still while somebody searches`() {
        val view = StockBoard.build(shelf, query = "SIE1000")
        assertEquals(1, view.rows.size)
        assertEquals(5, view.tracked)
        assertEquals(1, view.low)
        assertEquals(2, view.out)
    }

    @Test
    fun `the low filter shows only low rows`() {
        val view = StockBoard.build(shelf, filter = StockFilter.LOW)
        assertEquals(listOf("gateMotors|SIE2000"), view.rows.map { it.key })
    }

    @Test
    fun `the out filter shows both kinds of empty row`() {
        val view = StockBoard.build(shelf, filter = StockFilter.OUT)
        assertEquals(setOf("gateMotors|SIE3000", "gateMotors|SIE4000"), view.rows.map { it.key }.toSet())
    }

    @Test
    fun `the pinned filter shows only pinned rows`() {
        val view = StockBoard.build(shelf, filter = StockFilter.PINNED)
        assertEquals(listOf("gateMotors|SIE5000"), view.rows.map { it.key })
    }

    @Test
    fun `a filter that matches nothing leaves an empty view with its counts intact`() {
        val view = StockBoard.build(listOf(record("SIE1000", quantity = 9.0)), filter = StockFilter.OUT)
        assertTrue(view.isEmpty)
        assertEquals(1, view.tracked)
    }

    // --- ordering ---------------------------------------------------------

    @Test
    fun `pinned rows lead, oldest pin first`() {
        val view = StockBoard.build(
            listOf(
                record("ZZZ", name = "Zinc bracket"),
                record("BBB", name = "Bee bracket", pinned = true, pinOrder = 200.0),
                record("AAA", name = "Ant bracket", pinned = true, pinOrder = 100.0)
            )
        )
        assertEquals(
            listOf("Ant bracket", "Bee bracket", "Zinc bracket"),
            view.rows.map { it.name }
        )
    }

    @Test
    fun `unpinned rows sort by name regardless of their stale pin order`() {
        val view = StockBoard.build(
            listOf(
                record("ZZZ", name = "Zebra", pinOrder = 1.0),
                record("AAA", name = "Anvil", pinOrder = 999.0)
            )
        )
        assertEquals(listOf("Anvil", "Zebra"), view.rows.map { it.name })
    }

    // --- search -----------------------------------------------------------

    @Test
    fun `search covers the name, the model, the key and the note`() {
        val shelf = listOf(
            record("SIE1000", name = "Sliding gate motor"),
            record("GD-3.0-A", name = "Garage door", group = "garage", note = "kept in the loft")
        )
        assertEquals(1, StockBoard.build(shelf, query = "sliding").rows.size)
        assertEquals(1, StockBoard.build(shelf, query = "gd-3").rows.size)
        assertEquals(1, StockBoard.build(shelf, query = "garage|").rows.size)
        assertEquals(1, StockBoard.build(shelf, query = "loft").rows.size)
        assertEquals(0, StockBoard.build(shelf, query = "nothing here").rows.size)
    }

    @Test
    fun `search ignores case and surrounding space`() {
        val shelf = listOf(record("SIE1000", name = "Sliding gate motor"))
        assertEquals(1, StockBoard.build(shelf, query = "  SLIDING  ").rows.size)
    }

    // --- display name -----------------------------------------------------

    @Test
    fun `the descriptive name wins when the native app has written one`() {
        assertEquals("Sliding gate motor", StockBoard.displayName(record("SIE1000", name = "Sliding gate motor")))
    }

    @Test
    fun `a manual item falls back to its own name`() {
        assertEquals(
            "Shed padlock",
            StockBoard.displayName(record("shed_padlock", manualName = "Shed padlock", group = "manualstock"))
        )
    }

    @Test
    fun `a PWA row with no name shows its model rather than nothing`() {
        assertEquals("SIE1000", StockBoard.displayName(record("SIE1000")))
    }

    @Test
    fun `a row with neither name nor model falls back to the key's tail`() {
        val bare = StockRecord(documentId = "gateMotors|SIE9000", key = "gateMotors|SIE9000")
        assertEquals("SIE9000", StockBoard.displayName(bare))
        assertEquals("SIE9000", StockBoard.displayModel(bare))
    }

    @Test
    fun `a key with no separator still displays`() {
        val odd = StockRecord(documentId = "legacyrow", key = "legacyrow")
        assertEquals("legacyrow", StockBoard.displayName(odd))
    }

    // --- history -----------------------------------------------------------

    private fun move(id: String, key: String, at: Long) =
        StockMove(id = id, key = key, at = at)

    @Test
    fun `history is this row's movements, newest first`() {
        val movements = listOf(
            move("m1", "gateMotors|SIE1000", at = 100L),
            move("m2", "gateMotors|SIE2000", at = 200L),
            move("m3", "gateMotors|SIE1000", at = 300L),
            move("m4", "gateMotors|SIE1000", at = 200L)
        )
        assertEquals(
            listOf("m3", "m4", "m1"),
            StockBoard.historyFor(movements, "gateMotors|SIE1000").map { it.id }
        )
    }

    @Test
    fun `two movements in the same millisecond still order the same way twice`() {
        val same = listOf(
            move("a", "gateMotors|SIE1000", at = 500L),
            move("b", "gateMotors|SIE1000", at = 500L)
        )
        assertEquals(
            StockBoard.historyFor(same, "gateMotors|SIE1000").map { it.id },
            StockBoard.historyFor(same.reversed(), "gateMotors|SIE1000").map { it.id }
        )
    }

    @Test
    fun `a row nothing has happened to has an empty history`() {
        assertTrue(StockBoard.historyFor(emptyList(), "gateMotors|SIE1000").isEmpty())
        assertTrue(
            StockBoard.historyFor(
                listOf(move("m1", "gateMotors|SIE2000", at = 1L)),
                "gateMotors|SIE1000"
            ).isEmpty()
        )
    }
}
