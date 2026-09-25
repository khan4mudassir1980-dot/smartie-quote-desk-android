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

    // --- which saved customer a quotation is filed under -------------------------------

    /**
     * The saved customer a quotation should link to, **derived from the form**
     * — V8C4's `resolvePartyId` (6379), as the Owner read it on 2026-09-25:
     *
     * 1. nothing identifying on the form — no name, GSTIN or phone — links
     *    to nothing;
     * 2. the customer the person picked ([heldId]) survives **only while the
     *    form still matches it** ([sameParty]): "a party that was picked and
     *    then typed over cannot be carried into the record" (6211);
     * 3. otherwise the saved customer the form matches (`findCustomer`, which
     *    here is N5.5's port, [PartyDuplicates.find]);
     * 4. otherwise nothing.
     *
     * **Never a refusal, and never a failure.** The link is optional metadata:
     * the quotation keeps its own snapshot of the details, so a customer that
     * has gone — deleted, or simply not in [customers] — costs a
     * cross-reference and loses no quotation data. V8C4 returns null there and
     * finalises; `3db056b` refused instead, which was a native-only way to
     * strand somebody at the moment they most need the number.
     *
     * [customers] is the list the screen already holds, as V8C4 uses its
     * in-memory `state.customers`. A Firestore transaction cannot run a query,
     * and the link is metadata, so a list a moment old is good enough.
     */
    fun linkFor(
        form: QuotationPartySnapshot,
        heldId: String,
        customers: List<PartyRecord>
    ): String? {
        if (form.name.isBlank() && form.gstin.isBlank() && form.phone.isBlank()) return null
        val held = heldId.takeIf { it.isNotBlank() }?.let { id -> customers.firstOrNull { it.id == id } }
        if (held != null && sameParty(form, held)) return held.id
        return PartyDuplicates.find(
            customers,
            PartyDraft(name = form.name, gstin = form.gstin, phone = form.phone)
        )?.party?.id
    }

    /**
     * Whether the form still describes the saved customer it was picked as.
     *
     * **A STAND-IN, NOT V8C4's `sameParty`, whose text is not in this
     * repository** — asked of the Owner on 2026-09-25 and recorded in
     * `docs/PROJECT-STATUS.md`. It is deliberately **strict**, because the two
     * ways of being wrong cost very different amounts: too strict drops a
     * cross-reference, which step 3 of [linkFor] may well restore; too loose
     * files a quotation for one firm under another, which is the defect this
     * exists to stop.
     *
     * So the name must match, and a GSTIN or phone present on **both** sides
     * must agree. A site, an address or a contact typed on the form never
     * breaks the match: those describe the job, or were never on the record.
     */
    fun sameParty(form: QuotationPartySnapshot, held: PartyRecord): Boolean {
        if (PartyDuplicates.normaliseName(form.name) !=
            PartyDuplicates.normaliseName(Parties.displayName(held))
        ) return false

        val formGstin = PartyDuplicates.normaliseGstin(form.gstin)
        val heldGstin = PartyDuplicates.normaliseGstin(held.gstin)
        if (formGstin.isNotEmpty() && heldGstin.isNotEmpty() && formGstin != heldGstin) return false

        val formPhone = PartyDuplicates.digitsOf(form.phone)
        val heldPhone = PartyDuplicates.digitsOf(held.phone)
        if (formPhone.isNotEmpty() && heldPhone.isNotEmpty() &&
            formPhone != heldPhone && !PartyDuplicates.sameLine(formPhone, heldPhone)
        ) return false

        return true
    }
}
