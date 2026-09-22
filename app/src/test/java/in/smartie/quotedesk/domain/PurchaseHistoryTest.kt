package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.mapping.DocData
import `in`.smartie.quotedesk.data.mapping.toPurchaseRecord
import `in`.smartie.quotedesk.data.model.PurchaseRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who sees whose history, and what counts as removed.
 *
 * The second half matters more than it looks. "Removed" is a field the PWA
 * writes as the number `1` and this app writes as `true`, and the reader
 * coerces both — so these tests pin each stored value for each role rather
 * than re-implementing a coercion that already exists.
 *
 * Titles: stored `staff` is displayed **Manager**, stored `worker` is
 * displayed **Staff**.
 */
class PurchaseHistoryTest {

    private val owner = Member(uid = "uid_owner", name = "Omar", role = Role.OWNER)
    private val admin = Member(uid = "uid_admin", name = "Asha", role = Role.ADMIN)
    private val manager = Member(uid = "uid_staff", name = "Sam", role = Role.STAFF)
    private val staff = Member(uid = "uid_worker", name = "Ravi", role = Role.WORKER)
    private val otherStaff = Member(uid = "uid_worker_2", name = "Ravi", role = Role.WORKER)

    private val everyone = listOf(owner, admin, manager, staff)

    /** Built through the reader, so the coercion under test is the real one. */
    private fun stored(
        id: String,
        byUid: String? = "uid_worker",
        del: Any? = null,
        received: Any? = null,
        status: String = "Needed",
        delBy: String? = null,
        delAt: Long? = null
    ): PurchaseRecord = DocData(
        id = id,
        fields = buildMap {
            put("id", id)
            put("name", id)
            put("qty", 4.0)
            put("status", status)
            put("updated", 1_712_000_000_000L)
            if (byUid != null) put("byUid", byUid)
            if (del != null) put("del", del)
            if (received != null) put("received", received)
            if (delBy != null) put("delBy", delBy)
            if (delAt != null) put("delAt", delAt)
        }
    ).toPurchaseRecord()

    private fun receivedRow(id: String, byUid: String? = "uid_worker") =
        stored(id, byUid = byUid, received = true, status = "Received")

    private fun removedRow(id: String, byUid: String? = "uid_worker", del: Any = true) =
        stored(id, byUid = byUid, del = del)

    // --- what counts as removed ------------------------------------------------

    @Test
    fun `a truthy del is removed, whatever shape the PWA wrote it in`() {
        for (value in listOf(true, 1, 1L, "1", "true", "yes")) {
            val row = removedRow("pr_$value", del = value)
            assertTrue("del = $value should read as removed", row.deleted)
            assertEquals(
                "and belong to the removed list",
                listOf("pr_$value"),
                PurchaseHistory.removed(listOf(row), admin).map { it.id }
            )
        }
    }

    @Test
    fun `a missing, zero or false del is not removed`() {
        for (value in listOf(null, 0, 0L, false, "0", "false")) {
            val row = stored("pr_live", del = value)
            assertFalse("del = $value should read as active", row.deleted)
            assertTrue(PurchaseHistory.removed(listOf(row), admin).isEmpty())
        }
    }

    @Test
    fun `an active requirement is in neither list, whoever is looking`() {
        val open = stored("pr_open")
        for (viewer in everyone) {
            assertTrue(PurchaseHistory.received(listOf(open), viewer).isEmpty())
            assertTrue(PurchaseHistory.removed(listOf(open), viewer).isEmpty())
        }
    }

    // --- who sees whose --------------------------------------------------------

    @Test
    fun `Owner, Administrator and Manager see everyone's, of both kinds`() {
        val rows = listOf(
            receivedRow("pr_theirs", byUid = "uid_someone"),
            removedRow("pr_gone_theirs", byUid = "uid_someone"),
            receivedRow("pr_mine"),
            removedRow("pr_gone_mine")
        )

        for (viewer in listOf(owner, admin, manager)) {
            assertEquals(
                "$viewer should see both received rows",
                setOf("pr_theirs", "pr_mine"),
                PurchaseHistory.received(rows, viewer).map { it.id }.toSet()
            )
            assertEquals(
                setOf("pr_gone_theirs", "pr_gone_mine"),
                PurchaseHistory.removed(rows, viewer).map { it.id }.toSet()
            )
        }
    }

    @Test
    fun `Staff see only what they raised, of both kinds`() {
        val rows = listOf(
            receivedRow("pr_theirs", byUid = "uid_someone"),
            removedRow("pr_gone_theirs", byUid = "uid_someone"),
            receivedRow("pr_mine"),
            removedRow("pr_gone_mine")
        )

        assertEquals(listOf("pr_mine"), PurchaseHistory.received(rows, staff).map { it.id })
        assertEquals(listOf("pr_gone_mine"), PurchaseHistory.removed(rows, staff).map { it.id })
    }

    @Test
    fun `a row with no recorded creator is never in a Staff account's history`() {
        val rows = listOf(receivedRow("pr_orphan", byUid = null), removedRow("pr_gone", byUid = null))

        assertTrue(PurchaseHistory.received(rows, staff).isEmpty())
        assertTrue(PurchaseHistory.removed(rows, staff).isEmpty())
        // And is ordinary history for everybody else.
        assertEquals(listOf("pr_orphan"), PurchaseHistory.received(rows, admin).map { it.id })
        assertEquals(listOf("pr_gone"), PurchaseHistory.removed(rows, admin).map { it.id })
    }

    @Test
    fun `two Staff accounts with the same display name keep separate histories`() {
        val rows = listOf(receivedRow("pr_mine"), removedRow("pr_gone_mine"))

        assertEquals(staff.name, otherStaff.name)
        assertTrue(PurchaseHistory.received(rows, otherStaff).isEmpty())
        assertTrue(PurchaseHistory.removed(rows, otherStaff).isEmpty())
    }

    @Test
    fun `a switched-off account sees nothing at all`() {
        val rows = listOf(receivedRow("pr_mine"), removedRow("pr_gone_mine"))
        for (viewer in everyone) {
            val off = viewer.copy(active = false)
            assertTrue(PurchaseHistory.received(rows, off).isEmpty())
            assertTrue(PurchaseHistory.removed(rows, off).isEmpty())
        }
    }

    // --- order and the removal stamp ---------------------------------------------

    @Test
    fun `removed rows are newest first, by when they were removed`() {
        val rows = listOf(
            removedRow("pr_old").copy(removedAt = 1_000),
            removedRow("pr_new").copy(removedAt = 9_000),
            removedRow("pr_mid").copy(removedAt = 5_000)
        )
        assertEquals(
            listOf("pr_new", "pr_mid", "pr_old"),
            PurchaseHistory.removed(rows, admin).map { it.id }
        )
    }

    @Test
    fun `a legacy removal with no stamp still appears, ordered by what it has`() {
        // A PWA removal wrote neither delBy nor delAt. Dropping such a row is
        // the one thing that must never happen.
        val legacy = removedRow("pr_legacy", del = 1)
        assertEquals("", legacy.removedBy)
        assertEquals(0L, legacy.removedAt)

        assertEquals(listOf("pr_legacy"), PurchaseHistory.removed(listOf(legacy), admin).map { it.id })
        assertEquals(
            "it falls back to when the document was last touched",
            1_712_000_000_000L,
            PurchaseHistory.removedAt(legacy)
        )
    }

    @Test
    fun `a removal this app made carries who and when`() {
        val row = removedRow("pr_gone", delBy = "Asha", delAt = 1_712_600_000_000L)
        assertEquals("Asha", row.removedBy)
        assertEquals(1_712_600_000_000L, row.removedAt)
        assertEquals("uid_worker", row.removedByUid)
    }
}
