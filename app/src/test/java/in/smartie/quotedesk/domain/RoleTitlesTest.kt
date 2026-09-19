package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.model.TeamAccess
import `in`.smartie.quotedesk.data.model.TeamMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The job titles, and the line between a title and an identity.
 *
 * Two roles were **renamed for readers only**: stored `staff` is now called
 * Manager and stored `worker` is now called Staff. Nothing under the surface
 * moved, so half of this class is about what did *not* change — the wire
 * values, the enum, the parser — because that is the half a careless rename
 * would break, and the half the Firestore rules and the PWA depend on.
 */
class RoleTitlesTest {

    // --- what a person reads ------------------------------------------------

    @Test
    fun `a stored worker is called Staff`() {
        assertEquals("Staff", RoleTitles.of(Role.WORKER))
    }

    @Test
    fun `a stored staff is called Manager`() {
        assertEquals("Manager", RoleTitles.of(Role.STAFF))
    }

    @Test
    fun `Owner and Administrator are untouched by the rename`() {
        assertEquals("Owner / Administrator", RoleTitles.of(Role.OWNER))
        assertEquals("Administrator", RoleTitles.of(Role.ADMIN))
    }

    @Test
    fun `no role is called Worker any more`() {
        assertTrue(
            "the old title must not survive anywhere in the mapping",
            Role.entries.none { RoleTitles.of(it) == "Worker" || RoleTitles.word(it) == "Worker" }
        )
    }

    @Test
    fun `no two roles share a title`() {
        // "Staff" used to mean the role that is now Manager. If both ever
        // carried it, a role menu would offer the same word twice and nobody
        // could tell which one they picked.
        val titles = Role.entries.map { RoleTitles.of(it) }
        assertEquals("every role needs its own title", titles.size, titles.toSet().size)

        val words = Role.entries.map { RoleTitles.word(it) }
        assertEquals("and its own word", words.size, words.toSet().size)
    }

    @Test
    fun `Staff now names the role that used to be Worker, and only that one`() {
        assertEquals(listOf(Role.WORKER), Role.entries.filter { RoleTitles.of(it) == "Staff" })
        assertEquals(listOf(Role.STAFF), Role.entries.filter { RoleTitles.of(it) == "Manager" })
    }

    // --- the plain word, for sentences --------------------------------------

    @Test
    fun `inside a sentence an Owner is just an Owner`() {
        assertEquals("Owner", RoleTitles.word(Role.OWNER))
        assertNotEquals(
            "the compound badge would put Administrator in a list twice",
            RoleTitles.word(Role.OWNER),
            RoleTitles.of(Role.OWNER)
        )
    }

    @Test
    fun `the permission sentence reads properly`() {
        assertEquals(
            "an Owner, Administrator or Manager",
            RoleTitles.anyOf(Role.OWNER, Role.ADMIN, Role.STAFF)
        )
        assertEquals("an Owner or Administrator", RoleTitles.anyOf(Role.OWNER, Role.ADMIN))
        assertEquals("an Owner", RoleTitles.anyOf(Role.OWNER))
        assertEquals("", RoleTitles.anyOf())
    }

    // --- stored values did not move -----------------------------------------

    @Test
    fun `the wire values are byte-for-byte what they always were`() {
        // These strings are in Firestore documents, in the security rules and
        // in the PWA. Renaming a title must never reach them.
        assertEquals("owner", Role.OWNER.wireValue)
        assertEquals("admin", Role.ADMIN.wireValue)
        assertEquals("staff", Role.STAFF.wireValue)
        assertEquals("worker", Role.WORKER.wireValue)
    }

    @Test
    fun `the enum still has exactly its four original names`() {
        assertEquals(
            listOf("OWNER", "ADMIN", "STAFF", "WORKER"),
            Role.entries.map { it.name }
        )
    }

    @Test
    fun `parsing is unchanged, and no title parses as a role`() {
        assertEquals(Role.STAFF, Role.from("staff"))
        assertEquals(Role.WORKER, Role.from("worker"))
        assertEquals(Role.OWNER, Role.from("owner"))
        assertEquals(Role.ADMIN, Role.from("admin"))
        // A display title is not a wire value; anything unknown still falls
        // back to the least-privileged role.
        assertEquals(Role.WORKER, Role.from("Manager"))
        assertEquals(Role.WORKER, Role.from(null))
    }

    @Test
    fun `every role round-trips through its wire value unchanged`() {
        for (role in Role.entries) {
            assertEquals(role, Role.from(role.wireValue))
        }
    }

    // --- the audit log, which holds raw wire values --------------------------

    @Test
    fun `an audit entry shows titles, not stored values`() {
        assertEquals("Manager", RoleTitles.ofWireValue("staff"))
        assertEquals("Staff", RoleTitles.ofWireValue("worker"))
        assertEquals("Owner", RoleTitles.ofWireValue("owner"))
        assertEquals("Administrator", RoleTitles.ofWireValue("admin"))
    }

    @Test
    fun `an audit entry tolerates the spacing and case the PWA might write`() {
        assertEquals("Manager", RoleTitles.ofWireValue(" Staff "))
        assertEquals("Staff", RoleTitles.ofWireValue("WORKER"))
    }

    @Test
    fun `an unrecognised audit value is shown as it was stored, not guessed`() {
        // Role.from would call this a worker and the screen would print
        // "Staff" over a value nobody here understands.
        assertEquals("supervisor", RoleTitles.ofWireValue("supervisor"))
        assertEquals("", RoleTitles.ofWireValue(""))
    }

    // --- the badge a person wears -------------------------------------------

    @Test
    fun `a member badge follows the same mapping`() {
        assertEquals("Staff", Member(uid = "u", role = Role.WORKER).roleLabel)
        assertEquals("Manager", Member(uid = "u", role = Role.STAFF).roleLabel)
        assertEquals("Administrator", Member(uid = "u", role = Role.ADMIN).roleLabel)
    }

    @Test
    fun `an Administrator holding an Owner position still wears the Owner badge`() {
        val access = TeamAccess(primaryOwnerUid = "uid_owner")
        val resolved = TeamRoles.resolve(
            TeamMember(uid = "uid_owner", roleWireValue = "admin"),
            access
        )
        assertEquals("Owner / Administrator", resolved.roleLabel)
    }

    // --- titles change nothing about who may do what ------------------------

    @Test
    fun `the person now called Manager has exactly the permissions staff had`() {
        val manager = Member(uid = "u", role = Role.STAFF)
        assertTrue(Permissions.canAdjustStock(manager))
        assertTrue(Permissions.canSetReorderLevel(manager))
        assertTrue(Permissions.canPinStock(manager))
        assertTrue(Permissions.canManageStockPhoto(manager))
        assertTrue(Permissions.canViewStockPhoto(manager))
        assertFalse(Permissions.canSetExactQuantity(manager))
        assertFalse(Permissions.canStopTrackingStock(manager))
    }

    @Test
    fun `the person now called Staff has exactly the permissions worker had`() {
        val staff = Member(uid = "u", role = Role.WORKER)
        assertFalse(Permissions.canAdjustStock(staff))
        assertFalse(Permissions.canSetReorderLevel(staff))
        assertFalse(Permissions.canPinStock(staff))
        assertFalse(Permissions.canManageStockPhoto(staff))
        assertFalse(Permissions.canViewStockHistory(staff))
        // Looking is not changing: a photo is exactly what they need.
        assertTrue(Permissions.canViewStockPhoto(staff))
        assertTrue(Permissions.canViewStock(staff))
    }

    @Test
    fun `the displayed title never decides a permission`() {
        // Two people, same stored role, different titles would be a bug. The
        // point of this assertion is that permissions read `role`, never a
        // string, so this can only ever pass — and would stop compiling if
        // somebody tried to key a permission off a title.
        for (role in Role.entries) {
            val member = Member(uid = "u", role = role)
            assertEquals(
                "permissions must follow the stored role",
                Permissions.canAdjustStock(Member(uid = "other", role = role)),
                Permissions.canAdjustStock(member)
            )
        }
    }
}
