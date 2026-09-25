package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.model.PartyRecord
import `in`.smartie.quotedesk.data.model.QuotationPartySnapshot

/** A party that already exists, and which of the three tests found it. */
data class PartyMatch(val party: PartyRecord, val on: PartyMatcher)

/** How an existing party was recognised, in the order V8C4 tries them. */
enum class PartyMatcher(val label: String) {
    GSTIN("the same GSTIN"),
    PHONE("the same phone number"),
    NAME("the same name")
}

/**
 * Finding the customer somebody is about to enter for a second time — the
 * Parties screen's duplicate warning ([find]) — and, separately, V8C4's own
 * rule for "the same party" ([sameParty], [findCustomer]).
 *
 * **[find] is NOT V8C4's `findCustomer`,** whatever this KDoc said until
 * N5.9a. The Owner's reading of V8C4 (6362-6371, 2026-09-25) shows
 * `findCustomer` is `sameParty` over the list in list order, skipping archived
 * parties, with an exact phone match. [find] searches by field priority
 * across the whole list, matches phones by suffix, and includes archived
 * parties. Two definitions of one rule is the defect class this project keeps
 * finding, so the difference is recorded in `docs/PROJECT-STATUS.md` for the
 * Owner to rule on; until then [find] serves only the Parties screen, and
 * everything that must match V8C4 — the quotation's party link, "Save this
 * customer" — calls [findCustomer].
 *
 * [find] tries three things **in this order**, and the order is not cosmetic: a GSTIN is a registration and identifies a firm outright; a
 * phone number is nearly as good; a name is the weakest of the three, because
 * two genuinely different firms can share one. Reporting the strongest match
 * that fired is what lets the warning say *why* it thinks this is a duplicate,
 * which is the difference between a person trusting it and dismissing it.
 *
 * **Seven digits is the floor for a phone.** Shorter than that is an
 * extension, a fragment or a typo, and matching on it would offer to open an
 * unrelated customer.
 *
 * Archived parties are searched too. Somebody re-entering a customer who was
 * archived last year has found the duplicate they were about to create, and
 * being told so is more useful than silence.
 *
 * Pure, and used before a write rather than after it.
 */
object PartyDuplicates {

    /** A phone shorter than this cannot identify anybody. */
    const val MIN_PHONE_DIGITS = 7

    /**
     * The existing party this draft would duplicate, or null.
     *
     * [ignoring] is the party being edited, which must never match itself.
     */
    fun find(
        parties: List<PartyRecord>,
        draft: PartyDraft,
        ignoring: String? = null
    ): PartyMatch? {
        val candidates = parties.filter { it.id != ignoring }

        val gstin = normaliseGstin(draft.gstin)
        if (gstin.isNotEmpty()) {
            candidates.firstOrNull { normaliseGstin(it.gstin) == gstin }
                ?.let { return PartyMatch(it, PartyMatcher.GSTIN) }
        }

        val phone = digitsOf(draft.phone)
        if (phone.length >= MIN_PHONE_DIGITS) {
            candidates.firstOrNull { sameLine(phone, digitsOf(it.phone)) }
                ?.let { return PartyMatch(it, PartyMatcher.PHONE) }
        }

        val name = normaliseName(draft.name)
        if (name.isNotEmpty()) {
            candidates.firstOrNull { normaliseName(it.name) == name }
                ?.let { return PartyMatch(it, PartyMatcher.NAME) }
        }

        return null
    }

    /** Case and spacing are how the same registration gets typed twice. */
    fun normaliseGstin(value: String): String =
        value.filter { !it.isWhitespace() }.uppercase()

    fun digitsOf(value: String): String = value.filter { it.isDigit() }

    /**
     * Whether two numbers, already reduced to digits, are the same line.
     *
     * **Not string equality**, and that is the whole of it: a number pasted
     * out of a phone's contacts carries its country code and the same number
     * typed by hand does not, so `919876543210` and `9876543210` are one
     * customer and comparing them as strings would let the duplicate through
     * — which is the mistake this object exists to stop. The test is a suffix
     * in either direction, and **both** sides must clear [MIN_PHONE_DIGITS],
     * because a stored fragment matching every number that happens to end in
     * it would be noise rather than a find.
     *
     * N5.3's search has the same shape for the same reason and deliberately
     * not the same code: there the typed value is a *partial* search and a
     * containment anywhere is wanted, whereas this is an identity test
     * between two numbers somebody wrote down in full.
     */
    fun sameLine(one: String, other: String): Boolean {
        if (one.length < MIN_PHONE_DIGITS || other.length < MIN_PHONE_DIGITS) return false
        return one.endsWith(other) || other.endsWith(one)
    }

    /** Case, surrounding space and doubled spaces, all of which people type. */
    fun normaliseName(value: String): String =
        value.trim().lowercase().replace(WHITESPACE, " ")

    // --- V8C4's "same party", exactly --------------------------------------------------

    /**
     * **V8C4's `sameParty` (6362-6371), exactly** — "Do these typed details
     * still describe this saved party? GSTIN first, then phone, then company
     * name — the order of reliability." **Any one of the three is enough**:
     *
     * ```
     * (g  && norm(c.gstin)===g) ||
     * (ph && ph.length>=7 && digits(c.phone)===ph) ||
     * (nm && norm(c.name)===nm)
     * ```
     *
     * V8C4 accepts the loose end deliberately. Correct a spelling in the
     * company name on a party whose GSTIN is unchanged and the GSTIN alone
     * keeps them the same party; the seven-digit floor stops a short or junk
     * number matching anything. The phone match is **exact** on digits — a
     * number typed with its country code does not match one stored without
     * it, in V8C4 as here. It compares against the record's own `name`, not
     * the display fallback.
     *
     * One question is open and recorded: V8C4's `norm` is not in this
     * repository. [norm] here is trim, lower case and single spaces — the
     * same normalisation [normaliseName] applies — used for the GSTIN and
     * the name alike, as V8C4 uses one `norm` for both.
     *
     * `N5.9a` commit 3c shipped a stricter stand-in in `QuoteParty` — the
     * name **and** any GSTIN or phone on both sides — which dropped a link
     * V8C4 keeps. Replaced by this in commit 3d.
     */
    fun sameParty(form: QuotationPartySnapshot, saved: PartyRecord): Boolean {
        val gstin = norm(form.gstin)
        val phone = digitsOf(form.phone)
        val name = norm(form.name)
        return (gstin.isNotEmpty() && norm(saved.gstin) == gstin) ||
            (phone.isNotEmpty() && phone.length >= MIN_PHONE_DIGITS && digitsOf(saved.phone) == phone) ||
            (name.isNotEmpty() && norm(saved.name) == name)
    }

    /**
     * **V8C4's `findCustomer`:** the first saved party, in list order, that
     * is not [exceptId], **not archived**, and [sameParty] as the form.
     *
     * Archived parties are skipped, as V8C4 skips them: a quotation must not
     * be filed under — nor "Save this customer" write into — a party somebody
     * archived.
     */
    fun findCustomer(
        form: QuotationPartySnapshot,
        customers: List<PartyRecord>,
        exceptId: String? = null
    ): PartyRecord? = customers.firstOrNull {
        it.id != exceptId && !it.archived && sameParty(form, it)
    }

    /** V8C4's `norm`, as far as this repository can know it — see [sameParty]. */
    private fun norm(value: String): String = normaliseName(value)

    private val WHITESPACE = Regex("\\s+")
}
