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
    fun `the form's site becomes the customer's city, as V8C4's saveParty maps it`() {
        // `if(p.site) c.city=p.site` (the Owner's reading, 2026-09-26). Until
        // N5.9a commit 8b this test pinned the opposite — that the site never
        // went back onto a customer — and V8C4 is the authority.
        val onScreen = QuoteParty.snapshotOf(sunrise).copy(site = "Bhiwandi")
        val back = QuoteParty.draftOf(onScreen)

        assertEquals("Sunrise Constructions", back.name)
        assertEquals("14 Marine Lines", back.address)
        assertEquals("Bhiwandi", back.city)

        // An empty site is silence: the stored city is left as it is.
        val silent = QuoteParty.draftOf(QuoteParty.snapshotOf(sunrise))
        assertEquals("", silent.city)
        assertEquals(PartyPlan.NoChange, PartyWrite.mergeInto(sunrise, silent, PartyAuthor("Owner", "u_1"), 100L))
    }

    @Test
    fun `saving from a quotation never renames a saved customer`() {
        // V8C4's `saveParty` update branch copies gstin, contact, phone,
        // email and address — never the name, which is written only when a
        // record is created. So a Manager correcting a spelling is never
        // refused for a rename, because none is attempted.
        val corrected = QuoteParty.snapshotOf(sunrise).copy(name = "Sunrise Construction Co", phone = "9820011223")
        val write = PartyWrite.mergeInto(
            sunrise,
            QuoteParty.draftOf(corrected),
            PartyAuthor(name = "Manager", uid = "u_m"),
            at = 100L
        ) as PartyPlan.Write
        assertEquals("9820011223", write.data["phone"])
        assertFalse(write.data.containsKey("name"))
        assertFalse(write.data.containsKey("notes"))
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

    // --- V8C4's sameParty and findCustomer, exactly --------------------------------------

    @Test
    fun `any one of GSTIN, phone and name is enough - the spelling-correction case`() {
        // The Owner's example: correct a spelling in the company name on a
        // party whose GSTIN is unchanged. `3c`'s stand-in required the name
        // and dropped this link; V8C4 keeps it on the GSTIN alone. Restoring
        // the AND fails this test.
        val corrected = QuotationPartySnapshot(name = "Sunrise Construction Co", gstin = sunrise.gstin)
        assertTrue(PartyDuplicates.sameParty(corrected, sunrise))
        assertEquals("c_1", QuoteParty.linkFor(corrected, "c_1", saved))
    }

    @Test
    fun `each clause alone matches, in V8C4's normalisation`() {
        assertTrue(PartyDuplicates.sameParty(QuotationPartySnapshot(gstin = " 27aaacs1234f1z5 "), sunrise))
        assertTrue(PartyDuplicates.sameParty(QuotationPartySnapshot(phone = "98765-43210"), sunrise))
        assertTrue(PartyDuplicates.sameParty(QuotationPartySnapshot(name = "  sunrise  CONSTRUCTIONS "), sunrise))
        assertFalse(PartyDuplicates.sameParty(QuotationPartySnapshot(name = "Sunrise Builders"), sunrise))
    }

    @Test
    fun `a phone needs seven digits, and matches exactly`() {
        val shortStored = sunrise.copy(phone = "543210")
        // Six digits on both sides: never a match, however equal.
        assertFalse(PartyDuplicates.sameParty(QuotationPartySnapshot(phone = "543210"), shortStored))
        // Exact on digits, as V8C4's `digits(c.phone)===ph`: a number typed
        // with its country code is not the one stored without it. N5.5's
        // duplicate warning (`find`) matches that by suffix; V8C4 does not.
        assertFalse(PartyDuplicates.sameParty(QuotationPartySnapshot(phone = "+91 98765 43210"), sunrise))
    }

    @Test
    fun `nothing on the form, or nothing matching, is not the same party`() {
        assertFalse(PartyDuplicates.sameParty(QuotationPartySnapshot(site = "Plot 7"), sunrise))
        assertFalse(PartyDuplicates.sameParty(QuotationPartySnapshot(name = "Metro Glass", phone = "9822001100"), sunrise))
    }

    @Test
    fun `findCustomer skips archived parties and the one excepted, in list order`() {
        val form = QuotationPartySnapshot(name = "Sunrise Constructions")
        val archived = sunrise.copy(archived = true)
        val twin = sunrise.copy(id = "c_9")
        assertEquals(null, PartyDuplicates.findCustomer(form, listOf(archived)))
        assertEquals("c_9", PartyDuplicates.findCustomer(form, listOf(archived, twin))?.id)
        assertEquals("c_9", PartyDuplicates.findCustomer(form, listOf(sunrise, twin), exceptId = "c_1")?.id)
        assertEquals("c_1", PartyDuplicates.findCustomer(form, listOf(sunrise, twin))?.id)
    }

    @Test
    fun `a quotation never links to an archived party it merely resembles`() {
        // Our 3c link used N5.5's `find`, which includes archived parties.
        val archived = metro.copy(archived = true)
        val typed = QuotationPartySnapshot(name = "Metro Glass")
        assertEquals(null, QuoteParty.linkFor(typed, "", listOf(sunrise, archived)))
        // A held party is looked up without regard to archiving, as V8C4
        // looks it up, and kept while the form still describes it.
        assertEquals("c_2", QuoteParty.linkFor(typed, "c_2", listOf(sunrise, archived)))
    }
}
