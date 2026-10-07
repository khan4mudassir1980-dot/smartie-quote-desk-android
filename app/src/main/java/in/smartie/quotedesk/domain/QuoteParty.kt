package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.model.PartyRecord
import `in`.smartie.quotedesk.data.model.QuotationPartySnapshot
import `in`.smartie.quotedesk.data.model.RateTierV2

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
 * customer's `city`**, as V8C4's `saveParty` does (from 6404; the Owner's
 * reading, 2026-09-26). Until N5.9a commit 8b this file said the
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
     * The other way, for "Save this customer" — the party details on the
     * form, as V8C4's `saveParty` reads them (from 6404).
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
     * — V8C4's `resolvePartyId` (6379), on the principle its own comment
     * states: **a link that no longer matches what is typed is never kept,
     * because keeping one files a quotation under the wrong party.**
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
     * in-memory customer list (6379). The held customer is looked up there
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

    /**
     * The link an **edit** keeps — never one it finds (N5.10).
     *
     * `PROJECT-STATUS` records it as a rule: **an absent `partyId` is never
     * re-resolved.** A walk-in quoted without being filed has no saved
     * customer, and matching one up on an edit would file somebody's
     * quotation under a party nobody chose. So this is [linkFor] without its
     * step 3: the customer held — the stored link, or one the person picked
     * while editing — survives while the form is still that party; nothing is
     * searched for. A held customer the screen's list does not hold (not
     * loaded, or gone) is kept as it was: there is nothing to judge it by.
     */
    fun keptLink(form: QuotationPartySnapshot, heldId: String, customers: List<PartyRecord>): String {
        if (heldId.isBlank()) return ""
        if (form.name.isBlank() && form.gstin.isBlank() && form.phone.isBlank()) return ""
        val held = customers.firstOrNull { it.id == heldId } ?: return heldId
        return if (PartyDuplicates.sameParty(form, held)) heldId else ""
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

    // --- the party's type and the quotation's rate (N5.10, amendment D) ---------------

    /**
     * What picking a saved customer does: the party and its link always; the
     * rate **only on a quotation with nothing on it**, and never on an edit.
     */
    data class Pick(val draft: QuoteDraft, val note: String?)

    /**
     * A saved party's type as a tier the builder offers — Dealer or Client —
     * or null for a legacy `contractor`, an unknown word or none at all.
     */
    fun offeredTierOf(type: String): RateTierV2? =
        QuoteTier.OFFERED.firstOrNull { it.wireValue.equals(type.trim(), ignoreCase = true) }

    /**
     * Picking [record] onto [draft], by the Owner's table (amendment D):
     *
     * - **a Dealer or Client on a quotation with no lines, not an edit** — the
     *   rate follows the type, V8C4's own rule (6604); there is nothing to
     *   reprice;
     * - **on one with lines** (a copy included) **or on an edit** (Q6) — the
     *   rate stays, and [typeNote] says so when the two differ;
     * - **a legacy contractor, or any other type** — the rate stays and
     *   nothing is said.
     *
     * Only this quotation's rate is ever moved; the customer's type is never
     * written from here (`draftOf` states none).
     */
    fun pick(draft: QuoteDraft, record: PartyRecord): Pick {
        val picked = draft.withParty(record)
        val type = offeredTierOf(record.type) ?: return Pick(picked, null)
        if (draft.isEmpty && !draft.isEdit) return Pick(picked.withTier(type) { null }.draft, null)
        return Pick(picked, typeNote(record.name, type, draft.tier))
    }

    /**
     * Ours: "<name> is saved as a Dealer — this quotation stays at Client
     * rates. Switch above to reprice." Null when the two agree.
     */
    fun typeNote(name: String, type: RateTierV2, tier: RateTierV2): String? {
        if (type == tier) return null
        return "${name.trim().ifBlank { "This customer" }} is saved as a ${type.label} — " +
            "this quotation stays at ${tier.label} rates. Switch above to reprice."
    }

    /** Asked before a new customer is created from the quotation; there is no default. */
    fun typeQuestion(name: String): String = "Save ${name.trim()} as a Dealer or a Client?"
}
