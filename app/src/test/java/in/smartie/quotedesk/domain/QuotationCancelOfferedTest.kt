package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.model.QuotationRecord
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether the detail offers Cancel (N5.10 commit 7) — the three cases the
 * Owner asked the phone rows to cover, decided here first: a Manager cancels
 * their own; a Manager is not offered another's; an Owner cancels a
 * Manager's. The rules refuse the forced case (`quotation.test.js`), and
 * `QuotationCancel.plan` refuses it inside the transaction.
 */
class QuotationCancelOfferedTest {

    private val manager = Member(uid = "u_m", name = "Manager Person", role = Role.STAFF)
    private val otherManager = Member(uid = "u_m2", name = "Other Manager", role = Role.STAFF)
    private val owner = Member(uid = "u_o", name = "Owner Person", role = Role.OWNER)
    private val admin = Member(uid = "u_a", name = "Admin Person", role = Role.ADMIN)
    private val staff = Member(uid = "u_m", name = "Manager Person", role = Role.WORKER)

    private val managers = QuotationRecord(id = "qd_9", number = "SIE/QD/2025-26/009", byUid = "u_m", status = "Finalised")

    @Test
    fun `a Manager is offered Cancel on their own quotation`() {
        assertTrue(QuotationCancel.offered(manager, managers))
    }

    @Test
    fun `a Manager is not offered Cancel on another's`() {
        assertFalse(QuotationCancel.offered(otherManager, managers))
    }

    @Test
    fun `an Owner and an Administrator are offered Cancel on a Manager's`() {
        assertTrue(QuotationCancel.offered(owner, managers))
        assertTrue(QuotationCancel.offered(admin, managers))
    }

    @Test
    fun `Staff are never offered Cancel, even with a matching uid`() {
        assertFalse(QuotationCancel.offered(staff, managers))
    }

    @Test
    fun `a cancelled quotation is not offered Cancel again - V8C4's own condition`() {
        assertFalse(QuotationCancel.offered(owner, managers.copy(status = "Cancelled")))
        assertFalse(QuotationCancel.offered(owner, managers.copy(status = "cancelled")))
    }

    @Test
    fun `a beta record is not offered Cancel`() {
        assertFalse(QuotationCancel.offered(owner, managers.copy(legacyBetaShape = true)))
    }
}
