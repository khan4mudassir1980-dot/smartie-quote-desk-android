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
 * `PartyRecord` has no such field. Picking a customer leaves the site alone
 * ([snapshotOf]); **saving one from the quotation writes the site into the
 * customer's `city`**, as V8C4's `saveParty` does — `if(p.site) c.city=p.site`
 * (the Owner's reading, 2026-09-26). Until N5.9a commit 8b this file said the
 * site "never goes back onto" a customer; V8C4 says otherwise, and V8C4 is the
 * authority.
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
     * The other way, for "Save this customer" — V8C4's `partyFromForm` as
     * `saveParty` reads it.
     *
     * **The form's site becomes the customer's `city`**, as `saveParty` maps
     * it. The `city` a picked customer brought onto the snapshot is not shown
     * on the form and is not carried back.
     *
     * **`type` and `notes` are left blank on purpose, and that is safe only
     * because of which write this feeds.** `PartyWrite.mergeInto` treats a
     * blank as silence — nothing stored is forgotten — while `PartyWrite.edit`
     * treats a blank as the instruction to clear the field. Handing this to
     * `edit` would wipe a customer's type and notes off them, so it must not
     * be: the quotation side merges, it never edits.
     */
    fun draftOf(party: QuotationPartySnapshot): PartyDraft = PartyDraft(
        name = party.name.trim(),
        city = party.site.trim(),
        gstin = party.gstin.trim(),
        contact = party.contact.trim(),
        phone = party.phone.trim(),
        email = party.email.trim(),
        address = party.address.trim()
    )

    // --- which saved customer a quotation is filed under -------------------------------

    /**
     * The saved customer a quotation should link to, **derived from the form**
     * — V8C4's `resolvePartyId` (6379), whose own KDoc states the principle:
     * **"A link that no longer matches what is typed is never kept — that is
     * how a quotation ends up on the wrong party."**
     *
     * 1. nothing identifying on the form — no name, GSTIN or phone — links
     *    to nothing;
     * 2. the customer the person picked ([heldId]) survives only while the
     *    form is still [PartyDuplicates.sameParty] as it;
     * 3. otherwise the first saved, unarchived customer the form is the same
     *    party as — [PartyDuplicates.findCustomer], V8C4's `findCustomer`;
     * 4. otherwise nothing.
     *
     * **Never a refusal, and never a failure.** The link is optional metadata:
     * the quotation keeps its own snapshot of the details, so a customer that
     * has gone costs a cross-reference and loses no quotation data.
     *
     * [customers] is the list the screen already holds, as V8C4 uses its
     * in-memory `state.customers`. The held customer is looked up there
     * without regard to `archived`, as V8C4 looks it up; only the fallback
     * search skips archived parties.
     *
     * **One definition of "same party".** Until N5.9a commit 3d this file
     * carried its own, stricter stand-in; it now calls [PartyDuplicates]'
     * port of V8C4's, which "Save this customer" will share.
     */
    fun linkFor(
        form: QuotationPartySnapshot,
        heldId: String,
        customers: List<PartyRecord>
    ): String? {
        if (form.name.isBlank() && form.gstin.isBlank() && form.phone.isBlank()) return null
        val held = heldId.takeIf { it.isNotBlank() }?.let { id -> customers.firstOrNull { it.id == id } }
        if (held != null && PartyDuplicates.sameParty(form, held)) return held.id
        return PartyDuplicates.findCustomer(form, customers)?.id
    }

    // --- asking before "Save this customer" writes into a saved party --------------------

    /**
     * V8C4's question before it updates a party that is already saved
     * (`#qSaveParty`, 8011-8022): it names the party and the reason, and the
     * person decides. Without it, a form typed over a picked customer would
     * silently update whichever **other** saved party it matches.
     */
    data class MergeQuestion(val partyName: String, val reason: PartyMatcher) {
        /** "“Sunrise Constructions” is already saved with the same GSTIN." */
        val message: String get() = "\u201C$partyName\u201D is already saved with ${reason.label}."
    }

    /** V8C4's OK line: "OK — update that party with these details." */
    const val UPDATE_THAT_PARTY = "Update that party with these details"

    /** V8C4's Cancel line: "Cancel — leave it alone." */
    const val LEAVE_IT_ALONE = "Leave it alone"
}
