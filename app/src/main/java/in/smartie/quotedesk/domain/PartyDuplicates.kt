package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.model.PartyRecord

/** A party that already exists, and which of the three tests found it. */
data class PartyMatch(val party: PartyRecord, val on: PartyMatcher)

/** How an existing party was recognised, in the order V8C4 tries them. */
enum class PartyMatcher(val label: String) {
    GSTIN("the same GSTIN"),
    PHONE("the same phone number"),
    NAME("the same name")
}

/**
 * Finding the customer somebody is about to enter for a second time.
 *
 * V8C4's `findCustomer` tries three things **in this order**, and the order is
 * not cosmetic: a GSTIN is a registration and identifies a firm outright; a
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

    private val WHITESPACE = Regex("\\s+")
}
