package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.model.PartyRecord
import `in`.smartie.quotedesk.data.model.QuotationPartySnapshot

/** A saved party the typed details are the same as, and why. */
data class PartyMatch(val party: PartyRecord, val on: PartyMatcher)

/**
 * Why two parties are the same one — V8C4's `matchReason` (6389-6393), in its
 * order of reliability, and in its words.
 */
enum class PartyMatcher(val label: String) {
    GSTIN("the same GSTIN"),
    PHONE("the same phone number"),
    NAME("the same company name")
}

/**
 * **V8C4's one rule for "the same party", ported exactly — and the only one in
 * this app.**
 *
 * V8C4 has exactly one definition and every path calls it (the Owner's reading,
 * 2026-09-26): `saveParty` (6407), `resolvePartyId` (6384), Add party (6573),
 * Edit party (6644, `findCustomer(v, c.id)` — which is what `exceptId` is for)
 * and Save from quotation (8016). Here: the quotation's party link
 * (`QuoteParty.linkFor`), "Save this customer"
 * (`PartyWriteRepository.saveFromQuotation`) and the Parties screen's
 * duplicate warning ([find]) all come through [firstMatch].
 *
 * **History, because it cost three corrections in one batch.** Until N5.9a
 * this file held N5.5's own rule for the Parties screen — field priority
 * across the whole list, phones matched by suffix, archived parties included —
 * while claiming to be V8C4's `findCustomer`. N5.9a commit 3d ported V8C4's
 * `sameParty` beside it, but with a `norm` that kept punctuation V8C4 strips.
 * Commit 8b made this the only rule and `norm` exact. Each port had come out
 * **stricter** than the PWA; see "Decisions that bind future work" in
 * `docs/PROJECT-STATUS.md`.
 *
 * Pure, and used before a write rather than after it.
 */
object PartyDuplicates {

    /** A phone shorter than this cannot identify anybody — V8C4's `ph.length>=7`. */
    const val MIN_PHONE_DIGITS = 7

    /**
     * **V8C4's `norm` (5681, 6335), exactly:**
     * `String(s||"").toLowerCase().replace(/[^a-z0-9]/g,"")`.
     *
     * Every character that is not a Latin letter or a digit goes — spaces,
     * dots, slashes, hyphens, ampersands. So "M/s Sunrise Ent." is
     * "M s Sunrise Ent", and "27 AABCU 9603 R1ZM" is "27AABCU9603R1ZM":
     * people type GSTINs with spaces.
     */
    fun norm(value: String): String = value.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }

    /**
     * **V8C4's `digits`, exactly:** `String(s||"").replace(/\D/g,"")` — the
     * ASCII digits only, compared by **full-string equality**. A number typed
     * with its country code is not the one stored without it, in V8C4 as here.
     */
    fun digits(value: String): String = value.filter { it in '0'..'9' }

    /**
     * **V8C4's `sameParty` (6362-6371)** — "GSTIN first, then phone, then
     * company name — the order of reliability". Any one is enough.
     */
    fun sameParty(form: QuotationPartySnapshot, saved: PartyRecord): Boolean =
        matchReason(form.gstin, form.phone, form.name, saved) != null

    /**
     * **V8C4's `findCustomer`:** the first saved party **in list order** that
     * is not [exceptId], **not archived**, and [sameParty] as the form.
     */
    fun findCustomer(
        form: QuotationPartySnapshot,
        customers: List<PartyRecord>,
        exceptId: String? = null
    ): PartyRecord? = firstMatch(form.gstin, form.phone, form.name, customers, exceptId)?.party

    /** [findCustomer] with V8C4's `matchReason`, for a question that names it. */
    fun matchFor(
        form: QuotationPartySnapshot,
        customers: List<PartyRecord>,
        exceptId: String? = null
    ): PartyMatch? = firstMatch(form.gstin, form.phone, form.name, customers, exceptId)

    /**
     * The Parties screen's duplicate warning — the same rule, from the
     * editor's draft. [ignoring] is the party being edited, which must never
     * match itself: V8C4's `findCustomer(v, c.id)`.
     *
     * Since N5.9a commit 8b, and at the Owner's ruling, this is V8C4's rule
     * rather than N5.5's: an archived party is not flagged, a phone typed with
     * its country code does not match one stored without it, and the first
     * match in list order wins. All three are what the PWA does.
     */
    fun find(
        parties: List<PartyRecord>,
        draft: PartyDraft,
        ignoring: String? = null
    ): PartyMatch? = firstMatch(draft.gstin, draft.phone, draft.name, parties, ignoring)

    private fun firstMatch(
        gstin: String,
        phone: String,
        name: String,
        parties: List<PartyRecord>,
        exceptId: String?
    ): PartyMatch? = parties.firstNotNullOfOrNull { party ->
        if (party.id == exceptId || party.archived) null
        else matchReason(gstin, phone, name, party)?.let { PartyMatch(party, it) }
    }

    /**
     * V8C4's `matchReason`: the first of its three tests that holds, in its
     * order, or null. The same three clauses as `sameParty`.
     */
    private fun matchReason(gstin: String, phone: String, name: String, saved: PartyRecord): PartyMatcher? {
        val g = norm(gstin)
        if (g.isNotEmpty() && norm(saved.gstin) == g) return PartyMatcher.GSTIN
        val ph = digits(phone)
        if (ph.length >= MIN_PHONE_DIGITS && digits(saved.phone) == ph) return PartyMatcher.PHONE
        val nm = norm(name)
        if (nm.isNotEmpty() && norm(saved.name) == nm) return PartyMatcher.NAME
        return null
    }
}
