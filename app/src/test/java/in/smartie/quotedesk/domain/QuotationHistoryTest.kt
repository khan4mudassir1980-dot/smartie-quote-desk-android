package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.model.QuotationRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which quotations each person may look back at.
 *
 * A quotation carries a price somebody negotiated, so it is not the same kind
 * of fact as a purchase requirement: a Manager who could read every quotation
 * in the business could read every discount anybody has ever given. Owner and
 * Administrator see all; a Manager sees the ones they issued.
 *
 * The trap this file exists for is the blank uid. A quotation the PWA wrote
 * without recording an author has `byUid == ""`, and `"" == ""` would hand
 * every one of those to whoever happened to be signed in. **A row with no
 * author belongs to nobody.**
 *
 * Titles: stored `staff` is displayed **Manager**, stored `worker` is
 * displayed **Staff**.
 */
class QuotationHistoryTest {

    private val owner = Member(uid = "uid_owner", name = "Mudassir", role = Role.OWNER)
    private val admin = Member(uid = "uid_admin", name = "Asha", role = Role.ADMIN)

    /** Stored `staff`, displayed **Manager**. */
    private val manager = Member(uid = "uid_staff", name = "Sam", role = Role.STAFF)
    private val otherManager = Member(uid = "uid_staff2", name = "Priya", role = Role.STAFF)

    /** Stored `worker`, displayed **Staff**. No quotations at all. */
    private val staff = Member(uid = "uid_worker", name = "Ravi", role = Role.WORKER)

    private fun quotation(id: String, byUid: String, at: Long = 1_000L) =
        QuotationRecord(id = id, number = id.uppercase(), at = at, byUid = byUid)

    private val mine = quotation("q_mine", manager.uid, at = 3_000L)
    private val theirs = quotation("q_theirs", otherManager.uid, at = 2_000L)
    private val authorless = quotation("q_pwa", byUid = "", at = 1_000L)
    private val all = listOf(mine, theirs, authorless)

    // --- who sees whose --------------------------------------------------------

    @Test
    fun `an owner and an administrator see every quotation`() {
        listOf(owner, admin).forEach { viewer ->
            assertEquals(
                viewer.uid,
                listOf("q_mine", "q_theirs", "q_pwa"),
                QuotationHistory.visibleTo(all, viewer).map { it.id }
            )
        }
    }

    @Test
    fun `a manager sees the ones they issued, and no others`() {
        assertEquals(listOf("q_mine"), QuotationHistory.visibleTo(all, manager).map { it.id })
        assertEquals(listOf("q_theirs"), QuotationHistory.visibleTo(all, otherManager).map { it.id })
    }

    @Test
    fun `a row with no author belongs to nobody`() {
        // The trap: `"" == ""`. A Manager with a real uid must not inherit
        // every quotation the PWA wrote without recording who issued it.
        assertFalse(QuotationHistory.visibleTo(authorless, manager))
        assertFalse(QuotationHistory.isCreator(manager, authorless))

        // And a viewer with no uid inherits nothing either.
        val nameless = manager.copy(uid = "")
        assertFalse(QuotationHistory.visibleTo(authorless, nameless))
        assertFalse(QuotationHistory.visibleTo(mine, nameless))
    }

    @Test
    fun `a Staff account sees no quotation at all`() {
        assertTrue(QuotationHistory.visibleTo(all, staff).isEmpty())
        assertFalse(QuotationHistory.visibleTo(mine, staff))
    }

    @Test
    fun `a switched-off account sees nothing, whatever its role`() {
        listOf(owner, admin, manager).forEach { viewer ->
            assertTrue(
                viewer.uid,
                QuotationHistory.visibleTo(all, viewer.copy(active = false)).isEmpty()
            )
        }
    }

    // --- ordering ----------------------------------------------------------------

    @Test
    fun `the newest quotation is first`() {
        val shuffled = listOf(authorless, mine, theirs)
        assertEquals(
            listOf("q_mine", "q_theirs", "q_pwa"),
            QuotationHistory.visibleTo(shuffled, owner).map { it.id }
        )
    }

    @Test
    fun `a quotation with no date sorts last and is still shown`() {
        // A bad timestamp is not a reason to hide a quotation somebody sent a
        // customer. Dropping it is the one thing that must never happen.
        val undated = quotation("q_undated", owner.uid, at = 0L)
        val visible = QuotationHistory.visibleTo(all + undated, owner)

        assertEquals(4, visible.size)
        assertEquals("q_undated", visible.last().id)
    }

    @Test
    fun `two quotations issued in the same millisecond still order predictably`() {
        val a = quotation("q_a", owner.uid, at = 5_000L)
        val b = quotation("q_b", owner.uid, at = 5_000L)

        assertEquals(
            listOf("q_b", "q_a"),
            QuotationHistory.visibleTo(listOf(a, b), owner).map { it.id }
        )
    }
}
