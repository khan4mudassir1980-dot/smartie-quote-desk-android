package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.model.PartyRecord
import `in`.smartie.quotedesk.data.model.QuotationPartySnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A customer onto a quotation, and the corrections back off it.
 *
 * **The site is the whole of it.** A quotation carries a `site` and a customer
 * has no such field, because they are different facts: the customer's address
 * is where the firm is, the site is where this job is. A shutter fitted at a
 * godown in Bhiwandi is quoted to an office in Mumbai. So the site must not
 * arrive from a customer, must not be overwritten by choosing one, and must
 * not be written back onto one.
 */
class QuotePartyTest {

    private val sunrise = PartyRecord(
        id = "c_1",
        name = "Sunrise Constructions",
        type = "contractor",
        city = "Mumbai",
        gstin = "27AAACS1234F1Z5",
        contact = "Mr Deshmukh",
        phone = "9876543210",
        email = "accounts@sunrise.example",
        address = "14 Marine Lines",
        notes = "Pays in 30 days"
    )

    @Test
    fun `a customer arrives with no site, because a customer has none`() {
        val snapshot = QuoteParty.snapshotOf(sunrise)

        assertEquals("Sunrise Constructions", snapshot.name)
        assertEquals("27AAACS1234F1Z5", snapshot.gstin)
        assertEquals("Mr Deshmukh", snapshot.contact)
        assertEquals("9876543210", snapshot.phone)
        assertEquals("accounts@sunrise.example", snapshot.email)
        assertEquals("14 Marine Lines", snapshot.address)
        assertEquals("Mumbai", snapshot.city)
        assertEquals("", snapshot.site)
    }

    @Test
    fun `a party with no firm name shows the contact, as the list does`() {
        val nameless = PartyRecord(id = "c_2", contact = "Mr Rane")
        assertEquals("Mr Rane", QuoteParty.snapshotOf(nameless).name)
        assertEquals(Parties.displayName(nameless), QuoteParty.snapshotOf(nameless).name)
    }

    @Test
    fun `choosing a customer keeps a site already typed`() {
        // Somebody writes the site down first and then picks the firm. They
        // have not asked for the site to be forgotten.
        val draft = QuoteDraft(id = "qd_1")
            .copy(party = QuotationPartySnapshot(site = "Bhiwandi godown"))
            .withParty(sunrise)

        assertEquals("Bhiwandi godown", draft.party.site)
        assertEquals("Sunrise Constructions", draft.party.name)
        assertEquals("c_1", draft.partyId)
    }

    @Test
    fun `the site never goes back onto the customer`() {
        val onScreen = QuoteParty.snapshotOf(sunrise).copy(site = "Bhiwandi godown")
        val back = QuoteParty.draftOf(onScreen)

        assertEquals("Sunrise Constructions", back.name)
        assertEquals("14 Marine Lines", back.address)
        // There is nowhere for it to go, and `PartyDraft` has no such field.
        assertEquals("Mumbai", back.city)
    }

    @Test
    fun `type and notes come back blank, which only merging may be handed`() {
        // `PartyWrite.mergeInto` reads a blank as silence and leaves what is
        // stored alone. `PartyWrite.edit` reads it as "forget this", so
        // handing it this draft would wipe a customer's type and notes off
        // them. The quotation side merges; it never edits.
        val back = QuoteParty.draftOf(QuoteParty.snapshotOf(sunrise))
        assertEquals("", back.type)
        assertEquals("", back.notes)

        val author = PartyAuthor(name = "Owner", uid = "u_1")
        val plan = PartyWrite.mergeInto(sunrise, back, author, at = 100L)
        // Nothing changed, so nothing is written — and in particular the
        // stored `contractor` and the stored note are not blanked.
        assertEquals(PartyPlan.NoChange, plan)
    }

    @Test
    fun `a correction typed on the quotation is taken, and only that field`() {
        val corrected = QuoteParty.snapshotOf(sunrise).copy(phone = "9820011223")
        val plan = PartyWrite.mergeInto(
            sunrise,
            QuoteParty.draftOf(corrected),
            PartyAuthor(name = "Owner", uid = "u_1"),
            at = 100L
        )

        val write = plan as PartyPlan.Write
        assertEquals("9820011223", write.data["phone"])
        assertEquals(null, write.data["type"])
        assertEquals(null, write.data["notes"])
        assertEquals(true, write.merge)
    }
}
