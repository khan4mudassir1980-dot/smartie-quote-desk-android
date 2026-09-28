package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.model.PartyRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The Parties screen's checks on Add and Edit (N5.10 commit 10, the Owner's
 * amendment D): Dealer or Client, chosen — no default — and `PartyFormat`'s
 * GSTIN, phone and email checks, shown bare. Nothing else: no merge
 * question, no name restriction, no archived rule.
 *
 * `PartyWrite.formRefusal` is what the editor asks before it sends anything,
 * and `create` and `edit` ask it again, so each case is pinned on both
 * writers.
 */
class PartyFormRefusalTest {

    private val author = PartyAuthor(name = "Asha", uid = "uid_admin")

    private val contractor = PartyRecord(
        id = "c_1", name = "Sunrise Constructions", type = "contractor", city = "Mumbai",
        gstin = "27AAACS1234F1Z5", phone = "9876543210", email = "accounts@sunrise.invalid"
    )

    private val chosen = PartyDraft(name = "Metro Glass", type = "dealer", city = "Mumbai")

    private fun created(draft: PartyDraft) = PartyWrite.create("c_new", draft, author, 1L)

    private fun edited(draft: PartyDraft, stored: PartyRecord = contractor) =
        PartyWrite.edit(stored, draft, author, 2L, canRename = true, canArchive = true)

    // --- the type -------------------------------------------------------------------

    @Test
    fun `the screen offers Dealer and Client only`() {
        assertEquals(listOf("dealer", "client"), PartyWrite.OFFERED_TYPES)
    }

    @Test
    fun `a contractor, or an unknown type, opens in the editor with nothing chosen`() {
        assertEquals("", PartyWrite.draftOf(contractor).type)
        assertEquals("", PartyWrite.draftOf(contractor.copy(type = "reseller")).type)
        assertEquals("", PartyWrite.draftOf(contractor.copy(type = "")).type)
        assertEquals("dealer", PartyWrite.draftOf(contractor.copy(type = "Dealer")).type)
    }

    @Test
    fun `a contractor is asked Dealer or Client at its next edit`() {
        assertEquals(PartyPlan.Refused(PartyWrite.CHOOSE_TYPE), edited(PartyWrite.draftOf(contractor)))

        val answered = edited(PartyWrite.draftOf(contractor).copy(type = "client")) as PartyPlan.Write
        assertEquals("client", answered.data["type"])
    }

    @Test
    fun `a stored Dealer is shown chosen, and can be changed`() {
        val dealer = contractor.copy(type = "dealer")

        assertEquals(PartyPlan.NoChange, edited(PartyWrite.draftOf(dealer), dealer))
        assertEquals("client", (edited(PartyWrite.draftOf(dealer).copy(type = "client"), dealer) as PartyPlan.Write).data["type"])
    }

    // --- the three formats, on Add and on Edit (R32) ------------------------------------

    private val badGstin = "27AAACS1234"
    private val badPhone = "12345"
    private val badEmail = "accounts-at-metro"

    @Test
    fun `Add refuses a malformed GSTIN, shown bare`() {
        val problem = PartyFormat.gstinProblem(badGstin)
        assertNotNull(problem)
        assertEquals(PartyPlan.Refused(problem!!), created(chosen.copy(gstin = badGstin)))
    }

    @Test
    fun `Add refuses a malformed phone, shown bare`() {
        val problem = PartyFormat.phoneProblem(badPhone)
        assertNotNull(problem)
        assertEquals(PartyPlan.Refused(problem!!), created(chosen.copy(phone = badPhone)))
    }

    @Test
    fun `Add refuses a malformed email, shown bare`() {
        val problem = PartyFormat.emailProblem(badEmail)
        assertNotNull(problem)
        assertEquals(PartyPlan.Refused(problem!!), created(chosen.copy(email = badEmail)))
    }

    @Test
    fun `Edit refuses the same three`() {
        val draft = PartyWrite.draftOf(contractor).copy(type = "client")

        assertEquals(PartyPlan.Refused(PartyFormat.gstinProblem(badGstin)!!), edited(draft.copy(gstin = badGstin)))
        assertEquals(PartyPlan.Refused(PartyFormat.phoneProblem(badPhone)!!), edited(draft.copy(phone = badPhone)))
        assertEquals(PartyPlan.Refused(PartyFormat.emailProblem(badEmail)!!), edited(draft.copy(email = badEmail)))
    }

    @Test
    fun `blank passes all three, as in V8C4`() {
        assertNull(PartyWrite.formRefusal(chosen.copy(gstin = "", phone = "", email = "")))
    }

    @Test
    fun `the name comes first, then the type, then the formats`() {
        assertEquals(PartyWrite.NAME_REQUIRED, PartyWrite.formRefusal(PartyDraft(type = "", phone = badPhone)))
        assertEquals(PartyWrite.CHOOSE_TYPE, PartyWrite.formRefusal(PartyDraft(name = "X", phone = badPhone)))
        assertEquals(PartyFormat.phoneProblem(badPhone), PartyWrite.formRefusal(PartyDraft(name = "X", type = "client", phone = badPhone)))
    }
}
