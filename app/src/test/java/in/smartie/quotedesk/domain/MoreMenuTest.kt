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
    fun `a worker sees only About`() {
        assertEquals(
            listOf("About & legal"),
            MoreMenu.visibleTo(worker).map { it.label },
        )
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
    fun `Team and About are built, the rest name their phase`() {
        val built = MoreMenu.destinations.filter { it.phase == null }.map { it.label }
        assertEquals(listOf("Team", "About & legal"), built)
        MoreMenu.destinations.filter { it.phase != null }.forEach {
            assertTrue(it.label, it.phase!!.matches(Regex("N[0-9]")))
        }
    }
}
