package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.model.PartyRecord
import `in`.smartie.quotedesk.data.model.QuotationPartySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    // --- the party link, derived from the form ------------------------------------------

    private val metro = PartyRecord(id = "c_2", name = "Metro Glass", gstin = "27AABCM9999K1Z2", phone = "9822001100")
    private val saved = listOf(sunrise, metro)

    @Test
    fun `a form with nothing identifying links to nothing`() {
        assertEquals(null, QuoteParty.linkFor(QuotationPartySnapshot(site = "Plot 7"), "c_1", saved))
    }

    @Test
    fun `the picked customer survives while the form still matches it`() {
        val form = QuoteParty.snapshotOf(sunrise).copy(site = "Godown 4", address = "a new address")
        assertEquals("c_1", QuoteParty.linkFor(form, "c_1", saved))
    }

    @Test
    fun `typing another firm over the picked one moves the link, or drops it`() {
        val typedOver = QuotationPartySnapshot(name = "Metro Glass", gstin = "27aabcm9999k1z2")
        assertEquals("c_2", QuoteParty.linkFor(typedOver, "c_1", saved))
        assertEquals(null, QuoteParty.linkFor(typedOver, "c_1", listOf(sunrise)))
    }

    @Test
    fun `a picked customer that has gone costs only the link`() {
        val form = QuoteParty.snapshotOf(sunrise)
        assertEquals(null, QuoteParty.linkFor(form, "c_1", listOf(metro)))
    }

    @Test
    fun `the stand-in for sameParty is strict about identity and blind to the job`() {
        val form = QuoteParty.snapshotOf(sunrise)
        // Case, spacing and a pasted country code are the same firm.
        assertTrue(QuoteParty.sameParty(form.copy(name = "  sunrise  CONSTRUCTIONS "), sunrise))
        assertTrue(QuoteParty.sameParty(form.copy(phone = "+91 98765 43210"), sunrise))
        // A site, an address or a contact typed for this job never breaks it.
        assertTrue(QuoteParty.sameParty(form.copy(site = "x", address = "y", contact = "z"), sunrise))
        // A different name, GSTIN or phone does.
        assertFalse(QuoteParty.sameParty(form.copy(name = "Sunrise Builders"), sunrise))
        assertFalse(QuoteParty.sameParty(form.copy(gstin = "27AABCM9999K1Z2"), sunrise))
        assertFalse(QuoteParty.sameParty(form.copy(phone = "9822001100"), sunrise))
        // Blank on the form is silence, not disagreement.
        assertTrue(QuoteParty.sameParty(form.copy(gstin = "", phone = ""), sunrise))
    }
}
