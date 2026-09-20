package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.model.PurchaseRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who may change which requirement.
 *
 * Two rules meet here and both are easy to get wrong in a way nobody would
 * notice until it mattered: **ownership is a uid**, so two people with the
 * same name must not inherit each other's permissions and a row whose author
 * cannot be proved belongs to nobody; and **a delivery closes the record**,
 * so the window a creator has to tidy up their own work ends the moment
 * anything arrives against it.
 *
 * Titles, for reading this file: stored `staff` is displayed **Manager** and
 * stored `worker` is displayed **Staff**.
 */
class PurchaseAccessTest {

    private val owner = Member(uid = "uid_owner", name = "Omar", role = Role.OWNER)
    private val admin = Member(uid = "uid_admin", name = "Asha", role = Role.ADMIN)

    /** Stored `staff`, displayed **Manager**. */
    private val manager = Member(uid = "uid_staff", name = "Sam", role = Role.STAFF)

    /** Stored `worker`, displayed **Staff**. */
    private val staff = Member(uid = "uid_worker", name = "Ravi", role = Role.WORKER)

    /** A second Staff account, same display name, different person. */
    private val otherStaff = Member(uid = "uid_worker_2", name = "Ravi", role = Role.WORKER)

    private fun requirement(
        byUid: String = staff.uid,
        quantity: Double = 10.0,
        receivedQuantity: Double? = null,
        receivedBy: String = "",
        receivedAt: Long = 0L,
        received: Boolean = false,
        status: String = "Needed",
        deleted: Boolean = false
    ) = PurchaseRecord(
        id = "pr_one",
        name = "Sliding gate rack",
        quantity = quantity,
        by = "Ravi",
        byUid = byUid,
        status = status,
        received = received,
        receivedQuantity = receivedQuantity,
        receivedBy = receivedBy,
        receivedAt = receivedAt,
        deleted = deleted
    )

    // --- what "untouched" means -----------------------------------------------

    @Test
    fun `a requirement nobody has delivered against is untouched`() {
        assertTrue(PurchaseAccess.isUntouched(requirement()))
    }

    @Test
    fun `any receipt field at all closes the record, whatever it says`() {
        // The four are written together and removed together, so one of them
        // present means a delivery was recorded — even a zero the PWA wrote.
        assertFalse(PurchaseAccess.isUntouched(requirement(receivedQuantity = 4.0)))
        assertFalse(PurchaseAccess.isUntouched(requirement(receivedQuantity = 0.0)))
        assertFalse(PurchaseAccess.isUntouched(requirement(receivedBy = "Sam")))
        assertFalse(PurchaseAccess.isUntouched(requirement(receivedAt = 9_000L)))
    }

    @Test
    fun `a removed or closed requirement is not untouched`() {
        assertFalse(PurchaseAccess.isUntouched(requirement(deleted = true)))
        assertFalse(PurchaseAccess.isUntouched(requirement(received = true, status = "Received")))
        assertFalse(PurchaseAccess.isUntouched(requirement(status = "Cancelled")))
        // A status nobody here writes is not the open state either.
        assertFalse(PurchaseAccess.isUntouched(requirement(status = "Ordered")))
    }

    // --- ownership is a uid -----------------------------------------------------

    @Test
    fun `two people with the same display name do not share permissions`() {
        val mine = requirement(byUid = staff.uid)
        assertEquals("the two accounts are deliberately identical by name", staff.name, otherStaff.name)
        assertTrue(PurchaseAccess.canEdit(staff, mine))
        assertFalse("a name is not an identity", PurchaseAccess.canEdit(otherStaff, mine))
        assertFalse(PurchaseAccess.canRemove(otherStaff, mine))
    }

    @Test
    fun `a row with no creator uid belongs to nobody`() {
        // The PWA wrote requirements without one — three of the four fixtures
        // are that shape. `"" == ""` would hand every one of them to whoever
        // happened to be signed in.
        val legacy = requirement(byUid = "")
        assertFalse(PurchaseAccess.isCreator(staff, legacy))
        assertFalse(PurchaseAccess.canEdit(staff, legacy))
        assertFalse(PurchaseAccess.canRemove(staff, legacy))
        // And it is still ordinary work for the roles that never needed to be
        // its creator.
        assertTrue(PurchaseAccess.canEdit(manager, legacy))
        assertTrue(PurchaseAccess.canEdit(admin, legacy))
    }

    @Test
    fun `a session with no uid matches nothing`() {
        val unresolved = staff.copy(uid = "")
        assertFalse(PurchaseAccess.isCreator(unresolved, requirement(byUid = "")))
        assertFalse(PurchaseAccess.canEdit(unresolved, requirement(byUid = "")))
    }

    @Test
    fun `a switched-off account keeps nothing`() {
        val off = staff.copy(active = false)
        assertFalse(PurchaseAccess.canEdit(off, requirement(byUid = off.uid)))
        assertFalse(PurchaseAccess.canRemove(off, requirement(byUid = off.uid)))
    }

    // --- Staff, the limited role ------------------------------------------------

    @Test
    fun `Staff corrects their own untouched requirement`() {
        val mine = requirement(byUid = staff.uid)
        assertTrue(PurchaseAccess.canEdit(staff, mine))
        assertTrue(PurchaseAccess.canSetUrgency(staff, mine))
        assertTrue(PurchaseAccess.canRemove(staff, mine))
    }

    @Test
    fun `Staff may do nothing to somebody else's requirement`() {
        val theirs = requirement(byUid = manager.uid)
        assertFalse(PurchaseAccess.canEdit(staff, theirs))
        assertFalse(PurchaseAccess.canSetUrgency(staff, theirs))
        assertFalse(PurchaseAccess.canRemove(staff, theirs))
    }

    @Test
    fun `Staff never receives, reopens or writes off a shortfall`() {
        val mine = requirement(byUid = staff.uid)
        val partly = requirement(byUid = staff.uid, receivedQuantity = 4.0)
        val done = requirement(byUid = staff.uid, received = true, status = "Received")

        assertFalse("not even on their own requirement", PurchaseAccess.canReceive(staff, mine))
        assertFalse(PurchaseAccess.canCloseShortfall(staff, partly))
        assertFalse(PurchaseAccess.canReopen(staff, done))
    }

    @Test
    fun `Staff loses their own requirement the moment something arrives`() {
        val partly = requirement(byUid = staff.uid, receivedQuantity = 4.0, receivedBy = "Sam")
        assertFalse(PurchaseAccess.canEdit(staff, partly))
        assertFalse(PurchaseAccess.canSetUrgency(staff, partly))
        assertFalse(PurchaseAccess.canRemove(staff, partly))
    }

    @Test
    fun `and after a full receipt too`() {
        val done = requirement(
            byUid = staff.uid,
            receivedQuantity = 10.0,
            received = true,
            status = "Received"
        )
        assertFalse(PurchaseAccess.canEdit(staff, done))
        assertFalse(PurchaseAccess.canRemove(staff, done))
    }

    // --- reopen gives it back ----------------------------------------------------

    @Test
    fun `reopening returns the requirement to its creator`() {
        // Reopen removes all four receipt fields, so the row is untouched
        // again and the person who raised it may correct it again. This is
        // the Owner's decision, recorded in docs/N4.2-plan.md: no
        // `everReceived` marker, because reopen means as good as new.
        val reopened = requirement(
            byUid = staff.uid,
            receivedQuantity = null,
            receivedBy = "",
            receivedAt = 0L,
            received = false,
            status = "Needed"
        )
        assertTrue(PurchaseAccess.isUntouched(reopened))
        assertTrue(PurchaseAccess.canEdit(staff, reopened))
        assertTrue(PurchaseAccess.canRemove(staff, reopened))
    }

    // --- Manager -----------------------------------------------------------------

    @Test
    fun `a Manager still edits anybody's untouched requirement`() {
        val theirs = requirement(byUid = staff.uid)
        assertTrue(PurchaseAccess.canEdit(manager, theirs))
        assertTrue(PurchaseAccess.canSetUrgency(manager, theirs))
    }

    @Test
    fun `a Manager removes their own untouched requirement and no other`() {
        assertTrue(PurchaseAccess.canRemove(manager, requirement(byUid = manager.uid)))
        assertFalse(PurchaseAccess.canRemove(manager, requirement(byUid = staff.uid)))
    }

    @Test
    fun `a Manager loses edit, urgency and remove once anything arrives`() {
        val partly = requirement(byUid = manager.uid, receivedQuantity = 4.0)
        assertFalse(PurchaseAccess.canEdit(manager, partly))
        assertFalse(PurchaseAccess.canSetUrgency(manager, partly))
        assertFalse(PurchaseAccess.canRemove(manager, partly))
    }

    @Test
    fun `but keeps receiving the outstanding quantity, and writing off the rest`() {
        val partly = requirement(byUid = staff.uid, quantity = 10.0, receivedQuantity = 4.0)
        assertTrue(PurchaseAccess.canReceive(manager, partly))
        assertTrue(PurchaseAccess.canCloseShortfall(manager, partly))
    }

    @Test
    fun `a Manager never reopens`() {
        val done = requirement(byUid = manager.uid, received = true, status = "Received")
        assertFalse(PurchaseAccess.canReopen(manager, done))
        assertTrue(PurchaseAccess.canReopen(admin, done))
        assertTrue(PurchaseAccess.canReopen(owner, done))
    }

    // --- the shortfall's own window -----------------------------------------------

    @Test
    fun `a shortfall can only be written off when part of it arrived`() {
        val untouched = requirement(quantity = 10.0)
        val partly = requirement(quantity = 10.0, receivedQuantity = 4.0)
        val whole = requirement(quantity = 10.0, receivedQuantity = 10.0)

        assertFalse("nothing arrived: remove it instead", PurchaseAccess.canCloseShortfall(manager, untouched))
        assertTrue(PurchaseAccess.canCloseShortfall(manager, partly))
        assertFalse("nothing is outstanding", PurchaseAccess.canCloseShortfall(manager, whole))
    }

    @Test
    fun `a closed or removed requirement is short of nothing`() {
        val closed = requirement(receivedQuantity = 4.0, received = true, status = "Received")
        val gone = requirement(receivedQuantity = 4.0, deleted = true)
        assertFalse(PurchaseAccess.canCloseShortfall(manager, closed))
        assertFalse(PurchaseAccess.canCloseShortfall(manager, gone))
    }

    // --- Owner and Administrator keep everything ------------------------------------

    @Test
    fun `an Owner and an Administrator may still correct a received requirement`() {
        val partly = requirement(byUid = staff.uid, receivedQuantity = 4.0)
        for (privileged in listOf(owner, admin)) {
            assertTrue(PurchaseAccess.canEdit(privileged, partly))
            assertTrue(PurchaseAccess.canSetUrgency(privileged, partly))
            assertTrue(PurchaseAccess.canRemove(privileged, partly))
        }
    }

    @Test
    fun `nobody touches a removed requirement, not even an Owner`() {
        val gone = requirement(deleted = true)
        assertFalse(PurchaseAccess.canEdit(owner, gone))
        assertFalse(PurchaseAccess.canRemove(owner, gone))
    }

    // --- the sentences --------------------------------------------------------------

    @Test
    fun `a refusal says what to do next, by role title`() {
        val partly = requirement(byUid = staff.uid, receivedQuantity = 4.0)
        val message = PurchaseAccess.refusalFor(staff, partly)
        assertEquals(PurchaseAccess.LOCKED_BY_RECEIPT, message)
        assertTrue("the titles are the ones a person reads", message.contains("Administrator"))
        assertFalse("and never the stored value", message.contains("admin"))

        assertEquals(
            PurchaseAccess.SOMEBODY_ELSES,
            PurchaseAccess.refusalFor(staff, requirement(byUid = manager.uid))
        )
        assertEquals(
            PurchaseAccess.NO_KNOWN_CREATOR,
            PurchaseAccess.refusalFor(staff, requirement(byUid = ""))
        )
        assertEquals(
            PurchaseAccess.ALREADY_REMOVED,
            PurchaseAccess.refusalFor(staff, requirement(deleted = true))
        )
    }
}
