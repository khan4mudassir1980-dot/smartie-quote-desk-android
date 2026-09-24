package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.model.PartyRecord
import `in`.smartie.quotedesk.data.model.QuotationPartySnapshot

/**
 * Moving a customer onto a quotation, and the corrections back off it.
 *
 * Pure, because the two directions are not symmetrical and the asymmetry is
 * where the money and the mistakes are. Keeping it out of the screen is what
 * makes both directions unit tests rather than assertions about a form.
 *
 * **The site is the asymmetry.** `QuotationPartySnapshot` carries a `site` and
 * `PartyRecord` has no such field, because they are different facts: the
 * customer's `address` is where the firm is, and the site is where *this job*
 * is. A shutter fitted at a godown in Bhiwandi is quoted to an office in
 * Mumbai. So the site never comes off a customer and never goes back onto one.
 */
object QuoteParty {

    /**
     * The customer as this quotation will print them.
     *
     * [QuotationPartySnapshot.site] is left blank deliberately — see the file
     * KDoc. `QuoteDraft.withParty` is what keeps a site somebody already typed.
     */
    fun snapshotOf(party: PartyRecord): QuotationPartySnapshot = QuotationPartySnapshot(
        name = Parties.displayName(party),
        gstin = party.gstin.trim(),
        contact = party.contact.trim(),
        phone = party.phone.trim(),
        email = party.email.trim(),
        address = party.address.trim(),
        city = party.city.trim()
    )

    /**
     * The other way, for "Save this customer".
     *
     * **`site`, `type` and `notes` are left blank on purpose, and that is safe
     * only because of which write this feeds.** `PartyWrite.mergeInto` treats
     * a blank as silence — nothing stored is forgotten — while `PartyWrite.edit`
     * treats a blank as the instruction to clear the field. Handing this to
     * `edit` would wipe a customer's type and notes off them, so it must not
     * be: the quotation side merges, it never edits.
     */
    fun draftOf(party: QuotationPartySnapshot): PartyDraft = PartyDraft(
        name = party.name.trim(),
        city = party.city.trim(),
        gstin = party.gstin.trim(),
        contact = party.contact.trim(),
        phone = party.phone.trim(),
        email = party.email.trim(),
        address = party.address.trim()
    )
}
