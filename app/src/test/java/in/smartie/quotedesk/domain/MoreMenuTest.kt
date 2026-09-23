package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.ui.more.MoreMenu
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoreMenuTest {

    private val owner = Member(uid = "o", role = Role.OWNER, ownerRank = OwnerRank.PRIMARY)
    private val admin = Member(uid = "a", role = Role.ADMIN)
    private val staff = Member(uid = "s", role = Role.STAFF)
    private val worker = Member(uid = "w", role = Role.WORKER)

    @Test
    fun `the menu keeps the PWA order`() {
        assertEquals(
            listOf(
                "Parties",
                "Products & Categories",
                "Quotation history",
                "Purchase history",
                "Stock movement history",
                "Team",
                "Settings",
                "About & legal",
            ),
            MoreMenu.destinations.map { it.label },
        )
    }

    @Test
    fun `an owner sees every entry`() {
        assertEquals(MoreMenu.destinations.size, MoreMenu.visibleTo(owner).size)
        assertEquals(MoreMenu.destinations.size, MoreMenu.visibleTo(admin).size)
    }

    @Test
    fun `the displayed Staff sees About and their own Purchase history`() {
        // Changed by N4.3: every role gets Purchase history now. What a Staff
        // account sees *inside* it is its own rows only, which is
        // `PurchaseHistory`'s job and has its own tests.
        assertEquals(
            listOf("Purchase history", "About & legal"),
            MoreMenu.visibleTo(worker).map { it.label },
        )
    }

    @Test
    fun `a switched-off account is offered nothing at all`() {
        val off = worker.copy(active = false)
        assertTrue(!MoreMenu.visibleTo(off).map { it.label }.contains("Purchase history"))
    }

    @Test
    fun `staff see what they may use and nothing they may not`() {
        val labels = MoreMenu.visibleTo(staff).map { it.label }
        assertTrue(labels.contains("Parties"))
        assertTrue(labels.contains("Quotation history"))
        assertTrue(labels.contains("Stock movement history"))
        assertTrue(labels.contains("Settings"))
        assertTrue(!labels.contains("Products & Categories"))
        assertTrue(!labels.contains("Team"))
    }

    /**
     * Team is offered to the roles that can act on it and to nobody else. A
     * Worker used to be offered the card and met the screen's refusal; the
     * refusal stays, but the card is no longer there to tap.
     */
    @Test
    fun `only an owner or administrator is offered Team`() {
        assertTrue(MoreMenu.visibleTo(owner).map { it.label }.contains("Team"))
        assertTrue(MoreMenu.visibleTo(admin).map { it.label }.contains("Team"))
        assertTrue(!MoreMenu.visibleTo(staff).map { it.label }.contains("Team"))
        assertTrue(!MoreMenu.visibleTo(worker).map { it.label }.contains("Team"))
    }

    @Test
    fun `an additional owner is offered Team`() {
        val additional = Member(uid = "ao", role = Role.OWNER, ownerRank = OwnerRank.ADDITIONAL)
        assertTrue(MoreMenu.visibleTo(additional).map { it.label }.contains("Team"))
    }

    @Test
    fun `a switched-off administrator is offered nothing`() {
        val suspended = Member(uid = "x", role = Role.ADMIN, active = false)
        assertEquals(emptyList<String>(), MoreMenu.visibleTo(suspended).map { it.label })
    }

    @Test
    fun `the built destinations are named, and the rest name their phase`() {
        // Purchase history joined them in N4.3, Parties and Quotation history
        // in N5.3 and N5.4, and Settings in N5.6. They are screens now, not
        // promises of one.
        //
        // Settings is deliberately here while it is still **partial**: it
        // holds the quotation numbering and the discount limit, and not the
        // company details, bank details or terms. The screen says that for
        // itself, which is the right place to say it — a menu entry claiming
        // "In development" would tell somebody the numbering cannot be
        // changed, when it can.
        val built = MoreMenu.destinations.filter { it.phase == null }.map { it.label }
        assertEquals(
            listOf("Parties", "Quotation history", "Purchase history", "Team", "Settings", "About & legal"),
            built
        )
        MoreMenu.destinations.filter { it.phase != null }.forEach {
            assertTrue(it.label, it.phase!!.matches(Regex("N[0-9]")))
        }
    }
}
