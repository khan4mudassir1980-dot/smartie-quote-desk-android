package `in`.smartie.quotedesk.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Naming a person, and saying what they are when that can be known.
 *
 * The case this exists for: two accounts carrying the same display name. On
 * staging the Owner's own account and a Manager test account carry the same
 * one, so a name says which words were typed and nothing about who typed
 * them. Here both are the persona, "Owner Person".
 * Every answer here is decided by uid.
 *
 * Titles: stored `staff` is displayed **Manager**, stored `worker` is
 * displayed **Staff**.
 */
class PurchasePeopleTest {

    private val owner = Member(uid = "uid_owner", name = "Owner Person", role = Role.OWNER)
    private val manager = Member(uid = "uid_staff", name = "Owner Person", role = Role.STAFF)
    private val staff = Member(uid = "uid_worker", name = "Ravi", role = Role.WORKER)
    private val admin = Member(uid = "uid_admin", name = "Asha", role = Role.ADMIN)

    private val team = PurchasePeople.byUid(listOf(owner, manager, staff, admin))

    @Test
    fun `two accounts with one name are told apart by uid`() {
        assertEquals("Owner Person · Owner", PurchasePeople.describe(owner.name, owner.uid, team))
        assertEquals(
            "Owner Person · Manager",
            PurchasePeople.describe(manager.name, manager.uid, team)
        )
    }

    @Test
    fun `every role reads as its displayed title, not its stored value`() {
        assertEquals("Asha · Administrator", PurchasePeople.describe(admin.name, admin.uid, team))
        // Stored `worker`, and never shown as one.
        assertEquals("Ravi · Staff", PurchasePeople.describe(staff.name, staff.uid, team))
    }

    @Test
    fun `an unknown uid gives the name alone`() {
        // The ordinary case on a Manager's or a Staff account's phone: they
        // may not read `/users`, so they are given nobody to look up in.
        assertEquals("Owner Person", PurchasePeople.describe("Owner Person", "uid_owner", emptyMap()))
        assertEquals("Somebody", PurchasePeople.describe("Somebody", "uid_gone", team))
    }

    @Test
    fun `a row with no uid gives the name alone`() {
        // A PWA-written row carries a name and no uid beside it.
        assertEquals("Ravi Worker", PurchasePeople.describe("Ravi Worker", "", team))
    }

    @Test
    fun `no name means no line, rather than a role naming nobody`() {
        assertEquals("", PurchasePeople.describe("", "uid_owner", team))
        assertEquals("", PurchasePeople.describe("   ", "uid_owner", team))
    }

    @Test
    fun `the name is trimmed, because a stored one may not be`() {
        assertEquals("Asha · Administrator", PurchasePeople.describe("  Asha  ", admin.uid, team))
    }

    @Test
    fun `an account with no uid cannot be looked up by a blank one`() {
        // Guards the `"" == ""` trap that would hand every uid-less row to
        // whichever member happened to have a blank uid.
        val nameless = Member(uid = "", name = "Nobody", role = Role.OWNER)
        val map = PurchasePeople.byUid(listOf(nameless, admin))

        assertEquals("Somebody", PurchasePeople.describe("Somebody", "", map))
    }
}
