package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.model.TeamAccess
import `in`.smartie.quotedesk.data.model.TeamMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** One assertion group per row of the parity audit's section 7 matrix. */
class PermissionsTest {

    private val primaryOwner = Member(
        uid = "uid_owner", email = "owner@example.invalid", role = Role.OWNER,
        ownerRank = OwnerRank.PRIMARY
    )
    private val additionalOwner = Member(
        uid = "uid_second", email = "second@example.invalid", role = Role.OWNER,
        ownerRank = OwnerRank.ADDITIONAL
    )
    private val admin = Member(uid = "uid_admin", email = "admin@example.invalid", role = Role.ADMIN)
    private val staff = Member(uid = "uid_staff", email = "staff@example.invalid", role = Role.STAFF)
    private val worker = Member(uid = "uid_worker", email = "worker@example.invalid", role = Role.WORKER)

    // --- products and quotations ------------------------------------------

    @Test
    fun `workers never reach products, prices or quotations`() {
        assertFalse(Permissions.canViewProducts(worker))
        assertFalse(Permissions.canQuote(worker))
        assertFalse(Permissions.canViewQuotationHistory(worker))
        assertFalse(Permissions.canUseParties(worker))
        listOf(primaryOwner, additionalOwner, admin, staff).forEach {
            assertTrue(it.uid, Permissions.canViewProducts(it))
            assertTrue(it.uid, Permissions.canQuote(it))
        }
    }

    @Test
    fun `only owners and administrators change products, categories and pins`() {
        assertTrue(Permissions.canEditProducts(primaryOwner))
        assertTrue(Permissions.canEditProducts(additionalOwner))
        assertTrue(Permissions.canEditProducts(admin))
        assertFalse(Permissions.canEditProducts(staff))
        assertFalse(Permissions.canEditProducts(worker))
        assertFalse(Permissions.canManageCategoriesAndPins(staff))
    }

    @Test
    fun `staff may quote but not cancel a quotation`() {
        assertTrue(Permissions.canQuote(staff))
        assertFalse(Permissions.canCancelQuotation(staff))
        assertTrue(Permissions.canCancelQuotation(admin))
    }

    @Test
    fun `staff edit party contact details but do not rename or archive`() {
        assertTrue(Permissions.canUseParties(staff))
        assertFalse(Permissions.canRenameOrArchiveParty(staff))
        assertTrue(Permissions.canRenameOrArchiveParty(admin))
    }

    // --- stock -------------------------------------------------------------

    @Test
    fun `owner, administrator and staff add and subtract stock`() {
        listOf(primaryOwner, additionalOwner, admin, staff).forEach {
            assertTrue(it.uid, Permissions.canAdjustStock(it))
        }
        assertFalse(Permissions.canAdjustStock(worker))
    }

    @Test
    fun `a stock note is never required`() {
        assertFalse(Permissions.REQUIRES_STOCK_NOTE)
        assertEquals("Quick stock update", Permissions.DEFAULT_STOCK_NOTE)
    }

    @Test
    fun `exact quantity correction is owner and administrator only and needs a reason`() {
        assertTrue(Permissions.canSetExactQuantity(admin))
        assertTrue(Permissions.canSetExactQuantity(primaryOwner))
        assertFalse(Permissions.canSetExactQuantity(staff))
        assertFalse(Permissions.canSetExactQuantity(worker))
        assertTrue(Permissions.requiresCorrectionReason(admin))
    }

    @Test
    fun `staff set the reorder level but never an exact quantity`() {
        listOf(primaryOwner, additionalOwner, admin, staff).forEach {
            assertTrue(it.uid, Permissions.canSetReorderLevel(it))
        }
        assertFalse(Permissions.canSetReorderLevel(worker))
        // The line the v9 rules draw: `min` for Staff, `set` for an
        // Administrator. Both halves, so neither can drift alone.
        assertTrue(Permissions.canSetReorderLevel(staff))
        assertFalse(Permissions.canSetExactQuantity(staff))
    }

    @Test
    fun `a worker sees stock but no history and no controls`() {
        assertTrue(Permissions.canViewStock(worker))
        assertFalse(Permissions.canViewStockHistory(worker))
        assertFalse(Permissions.canPinStock(worker))
        assertFalse(Permissions.canAdjustStock(worker))
        assertFalse(Permissions.canSetReorderLevel(worker))
        assertFalse(Permissions.canSetExactQuantity(worker))
        assertFalse(Permissions.canStopTrackingStock(staff))
    }

    // --- purchase ----------------------------------------------------------

    @Test
    fun `a worker views and adds purchase requirements but never edits one`() {
        assertTrue(Permissions.canViewPurchase(worker))
        assertTrue(Permissions.canAddPurchase(worker))
        assertFalse(Permissions.canEditPurchase(worker))
        assertFalse(Permissions.canSetPurchaseStatus(worker))
        assertFalse(Permissions.canDeletePurchase(worker))
        assertTrue(Permissions.canEditPurchase(staff))
        assertFalse(Permissions.canDeletePurchase(staff))
        assertTrue(Permissions.canDeletePurchase(admin))
    }

    // --- settings and team -------------------------------------------------

    @Test
    fun `settings are editable by administrators, readable by staff, hidden from workers`() {
        assertTrue(Permissions.canEditSettings(admin))
        assertFalse(Permissions.canEditSettings(staff))
        assertTrue(Permissions.canViewSettings(staff))
        assertFalse(Permissions.canViewSettings(worker))
    }

    @Test
    fun `only owners and administrators open Team`() {
        assertTrue(Permissions.canViewTeam(admin))
        assertTrue(Permissions.canViewTeamActivity(primaryOwner))
        assertFalse(Permissions.canViewTeam(staff))
        assertFalse(Permissions.canViewTeam(worker))
    }

    @Test
    fun `the migration report is for owners only`() {
        assertTrue(Permissions.canViewMigrationReport(primaryOwner))
        assertTrue(Permissions.canViewMigrationReport(additionalOwner))
        assertFalse(Permissions.canViewMigrationReport(admin))
    }

    // --- managing people ---------------------------------------------------

    @Test
    fun `nobody manages their own account`() {
        listOf(primaryOwner, additionalOwner, admin, staff, worker).forEach {
            assertFalse(it.uid, Permissions.canManage(it, it))
        }
    }

    @Test
    fun `a duplicate record with the same email is still the caller`() {
        val duplicate = worker.copy(uid = "uid_worker_second", email = "WORKER@example.invalid")
        assertFalse(Permissions.canManage(admin.copy(email = "worker@example.invalid"), duplicate))
    }

    @Test
    fun `the primary owner cannot be touched by anyone`() {
        listOf(primaryOwner, additionalOwner, admin, staff, worker).forEach {
            assertFalse(it.uid, Permissions.canManage(it, primaryOwner))
            assertFalse(it.uid, Permissions.canToggleActive(it, primaryOwner))
            assertFalse(it.uid, Permissions.canRemove(it, primaryOwner))
            assertTrue(it.uid, Permissions.roleOptionsFor(it, primaryOwner, ownerCount = 2).isEmpty())
        }
    }

    @Test
    fun `an additional owner cannot modify either owner`() {
        assertFalse(Permissions.canManage(additionalOwner, primaryOwner))
        assertFalse(Permissions.canManage(additionalOwner, additionalOwner))
        assertTrue(Permissions.canManage(additionalOwner, admin))
        assertTrue(Permissions.canManage(additionalOwner, worker))
    }

    @Test
    fun `an administrator manages administrators, staff and workers but no owner`() {
        assertTrue(Permissions.canManage(admin, staff))
        assertTrue(Permissions.canManage(admin, worker))
        assertTrue(Permissions.canManage(admin, Member(uid = "uid_other_admin", role = Role.ADMIN)))
        assertFalse(Permissions.canManage(admin, primaryOwner))
        assertFalse(Permissions.canManage(admin, additionalOwner))

        val options = Permissions.roleOptionsFor(admin, worker, ownerCount = 2)
        assertEquals(listOf(Role.WORKER, Role.STAFF, Role.ADMIN), options)
    }

    @Test
    fun `staff and workers manage nobody`() {
        listOf(staff, worker).forEach { viewer ->
            listOf(primaryOwner, additionalOwner, admin, staff, worker).forEach { target ->
                assertFalse("${viewer.uid} -> ${target.uid}", Permissions.canManage(viewer, target))
            }
        }
    }

    @Test
    fun `a switched off administrator manages nobody`() {
        val disabled = admin.copy(active = false)
        assertFalse(Permissions.canManage(disabled, worker))
        assertFalse(Permissions.canViewTeam(disabled))
        assertFalse(Permissions.canAdjustStock(disabled))
    }

    @Test
    fun `only the primary owner may offer the owner position and only when a slot is free`() {
        val withSlotFree = Permissions.roleOptionsFor(primaryOwner, admin, ownerCount = 1)
        assertTrue(withSlotFree.contains(Role.OWNER))

        val slotTaken = Permissions.roleOptionsFor(primaryOwner, admin, ownerCount = 2)
        assertFalse(slotTaken.contains(Role.OWNER))

        assertFalse(Permissions.roleOptionsFor(admin, staff, ownerCount = 1).contains(Role.OWNER))
        assertFalse(Permissions.roleOptionsFor(additionalOwner, staff, ownerCount = 1).contains(Role.OWNER))
    }

    @Test
    fun `appointing a second owner is refused when the position is taken`() {
        assertTrue(Permissions.canAppointAdditionalOwner(primaryOwner, admin, ownerCount = 1))
        assertFalse(Permissions.canAppointAdditionalOwner(primaryOwner, admin, ownerCount = 2))
        assertFalse(Permissions.canAppointAdditionalOwner(admin, staff, ownerCount = 1))
        assertFalse(Permissions.canAppointAdditionalOwner(additionalOwner, staff, ownerCount = 1))
    }

    @Test
    fun `only the primary owner demotes or emergency revokes the additional owner`() {
        assertTrue(Permissions.canDemoteAdditionalOwner(primaryOwner, additionalOwner))
        assertTrue(Permissions.canEmergencyRevoke(primaryOwner, additionalOwner))
        assertFalse(Permissions.canEmergencyRevoke(additionalOwner, additionalOwner))
        assertFalse(Permissions.canEmergencyRevoke(admin, additionalOwner))
        assertFalse(Permissions.canEmergencyRevoke(primaryOwner, admin))
        // Demoting an additional owner keeps its own dropdown entry.
        assertTrue(
            Permissions.roleOptionsFor(primaryOwner, additionalOwner, ownerCount = 2)
                .containsAll(listOf(Role.WORKER, Role.STAFF, Role.ADMIN, Role.OWNER))
        )
    }

    @Test
    fun `owners are switched off through revoke, never through the toggle`() {
        assertFalse(Permissions.canToggleActive(primaryOwner, additionalOwner))
        assertFalse(Permissions.canRemove(primaryOwner, additionalOwner))
        assertTrue(Permissions.canToggleActive(primaryOwner, admin))
        assertTrue(Permissions.canRemove(admin, worker))
    }

    // --- owner resolution --------------------------------------------------

    @Test
    fun `the primary owner is resolved from the access document`() {
        val access = TeamAccess(primaryOwnerUid = "uid_owner", secondOwnerUid = "uid_second")
        val owner = TeamRoles.resolve(TeamMember(uid = "uid_owner", roleWireValue = "owner"), access)
        val second = TeamRoles.resolve(TeamMember(uid = "uid_second", roleWireValue = "owner"), access)
        assertEquals(OwnerRank.PRIMARY, owner.ownerRank)
        assertEquals(OwnerRank.ADDITIONAL, second.ownerRank)
        assertEquals("Owner / Administrator", owner.roleLabel)
        assertEquals("Primary", owner.ownerSubLabel)
        assertEquals("Additional", second.ownerSubLabel)
    }

    @Test
    fun `before migration the email fallback identifies the primary owner`() {
        val access = TeamAccess()
        val member = TeamMember(
            uid = "uid_owner",
            email = "Khan4Mudassir1980@gmail.com",
            roleWireValue = "admin"
        )
        val resolved = TeamRoles.resolve(member, access, "khan4mudassir1980@gmail.com")
        assertEquals(OwnerRank.PRIMARY, resolved.ownerRank)
        // Audit U4: the owner must never be shown as a plain Administrator.
        assertEquals("Owner / Administrator", resolved.roleLabel)
    }

    @Test
    fun `a seeded uid wins over the email fallback`() {
        val access = TeamAccess(primaryOwnerUid = "uid_real_owner")
        val impostor = TeamMember(
            uid = "uid_other",
            email = "khan4mudassir1980@gmail.com",
            roleWireValue = "worker"
        )
        val resolved = TeamRoles.resolve(impostor, access, "khan4mudassir1980@gmail.com")
        assertEquals(OwnerRank.NONE, resolved.ownerRank)
    }

    @Test
    fun `owner positions are counted for the two owner limit`() {
        val members = listOf(primaryOwner, additionalOwner, admin, staff, worker)
        assertEquals(2, TeamRoles.ownerCount(members))
        assertEquals(Permissions.MAX_OWNERS, TeamRoles.ownerCount(members))
    }
}
