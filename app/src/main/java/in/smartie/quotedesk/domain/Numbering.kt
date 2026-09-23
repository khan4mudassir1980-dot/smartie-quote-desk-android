package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.mapping.Money
import `in`.smartie.quotedesk.data.model.NumberingRecord

/** Who is writing, for the two provenance fields V8C4 keeps on the counter. */
data class NumberingAuthor(val name: String, val uid: String)

/**
 * The counter as somebody typed it into the Settings screen.
 *
 * Field names are the screen's; the V8C4 names (`prefix`, `fy`, `next`,
 * `pad`) are applied when the write map is built, in one place.
 */
data class NumberingDraft(
    val prefix: String = "",
    val financialYear: String = "",
    val next: Int = 1,
    val pad: Int = 3
)

/** What a settings write comes to, decided before anything reaches Firestore. */
sealed interface NumberingPlan {
    data class Write(val data: Map<String, Any?>) : NumberingPlan

    /** Nothing changed. Write nothing, and say nothing happened. */
    data object NoChange : NumberingPlan

    /** The write must not be attempted; [message] is for the person. */
    data class Refused(val message: String) : NumberingPlan
}

/**
 * What changing the counter would actually do, in the words a person needs
 * before they agree to it.
 */
data class NumberingConsequence(val headline: String, val detail: String)

/**
 * The quotation counter: what the next number will look like, and what the
 * rules will and will not accept.
 *
 * **Every refusal here is also a rule.** `/teamSettings/numbering`'s
 * configuration branch requires a **strictly greater** `next` wherever `next`
 * is being changed inside a financial year, so a lower one is refused by the
 * server whatever this file does. It is decided here as well, in the person's
 * own words, because a permission error tells nobody why — and because the
 * thing being prevented is somebody re-issuing a quotation number that is
 * already out in the world on a document a customer is holding.
 *
 * **Leaving `next` exactly where it is stays allowed**, in both layers. It is
 * not a move forward, and refusing it would mean an Owner correcting a prefix
 * or a padding had to burn a quotation number to do it. That allowance is what
 * a second rule then has to cover: configuration may never stamp
 * `lastIssued`, so a stale re-stamp cannot slip through the same opening.
 *
 * **The preview is the point of the screen.** A prefix, a year, a count and
 * a padding are four fields nobody can assemble in their head into
 * `SIE/QD/2026-27/010`, and getting it wrong is not visible until a quotation
 * has gone out under the wrong number. So the screen shows the number exactly
 * as it will be issued, from the same function that would issue it.
 *
 * Pure: no Firebase, no formatting locale, no clock.
 */
object Numbering {

    /** Only the Owner configures the counter. Everyone quoting may read it. */
    const val NOT_ALLOWED = "Only the Owner can change the quotation numbering"

    const val PREFIX_REQUIRED = "Enter the prefix, for example SIE/QD"
    const val PREFIX_MALFORMED =
        "The prefix can use letters, digits, / and - only, up to 16 characters"
    const val PREFIX_TRAILING_SLASH =
        "The prefix should not end with / — the year is joined on after it"
    const val PREFIX_DOUBLE_SLASH = "The prefix has two slashes together"
    const val YEAR_REQUIRED = "Enter the financial year, for example 2026-27"
    const val YEAR_MALFORMED = "The financial year reads like 2026-27 — four digits, a dash, two digits"
    const val NEXT_TOO_SMALL = "The next number must be 1 or more"
    const val PAD_OUT_OF_RANGE =
        "Padding must be between 1 and 6 digits. The PWA only offers 1 to 6, and would " +
            "quietly rewrite anything wider the next time somebody saved settings there."

    /**
     * The `pad` bounds, and they are **V8C4's**, not a guess at what looks
     * readable. Both of the PWA's save paths clamp with
     * `Math.min(6, Math.max(1, pd||3))` and its inputs are `min="1" max="6"`,
     * so a padding of 7 set here would appear in that input and be silently
     * rewritten to 6 on its next settings save — changing the printed number
     * format with nobody asking. The deployed configuration rule bounds it to
     * the same 1–6.
     */
    const val MIN_PAD = 1
    const val MAX_PAD = 6

    /**
     * The financial year V8C4 accepts, and the same pattern the configuration
     * rule enforces.
     */
    private val YEAR = Regex("^[0-9]{4}-[0-9]{2}$")

    /**
     * The prefix, and the same pattern the configuration rule enforces.
     *
     * **It admits `/` on purpose.** A quotation number is
     * `{prefix}/{fy}/{n}`, and the live counter's prefix `SIE/QD` is itself
     * two segments — so `SIE/QD/2025-26/009` has **four**, not three. Nothing
     * in this app parses a stored number back apart (there is no `split("/")`
     * anywhere in `app/src/main`), and [format] joins the prefix in whole, so
     * a multi-segment prefix travels through untouched. Both facts are
     * pinned by tests, because "nobody splits it" is a property that a future
     * edit could quietly break.
     */
    private val PREFIX = Regex("^[A-Za-z0-9][A-Za-z0-9/-]{0,15}$")

    /**
     * The number that will be issued next, formatted exactly as it will be
     * stored and printed: `SIE/QD/2026-27/010`.
     */
    fun format(prefix: String, financialYear: String, next: Int, pad: Int): String {
        val counted = next.coerceAtLeast(1).toString().padStart(pad.coerceIn(MIN_PAD, MAX_PAD), '0')
        return listOf(prefix.trim(), financialYear.trim(), counted)
            .filter { it.isNotEmpty() }
            .joinToString("/")
    }

    fun format(record: NumberingRecord): String =
        format(record.prefix, record.financialYear, record.next, record.pad)

    fun format(draft: NumberingDraft): String =
        format(draft.prefix, draft.financialYear, draft.next, draft.pad)

    fun draftOf(record: NumberingRecord): NumberingDraft = NumberingDraft(
        prefix = record.prefix,
        financialYear = record.financialYear,
        next = record.next,
        pad = record.pad
    )

    /** Whether the year is being changed, which is what relaxes the guard. */
    fun rollsTheYear(stored: NumberingRecord, draft: NumberingDraft): Boolean =
        stored.financialYear.trim() != draft.financialYear.trim()

    /**
     * Why this cannot be saved, or null when it can.
     *
     * The interesting one is the last. `next` is the number the *next*
     * quotation will carry, so it has not gone out yet — anything **below**
     * it has, and setting the counter there would issue a number a customer
     * is already holding. Leaving it exactly where it is changes nothing and
     * is allowed, which is what lets a prefix or a padding be corrected
     * without burning a number to do it. Rolling the year lifts the guard
     * altogether, because a new year restarts the sequence.
     *
     * The deployed rule says the same, in its own terms: `next` must be
     * strictly greater where it is being changed at all.
     */
    fun refusal(stored: NumberingRecord, draft: NumberingDraft): String? = when {
        draft.prefix.isBlank() -> PREFIX_REQUIRED
        !PREFIX.matches(draft.prefix.trim()) -> PREFIX_MALFORMED
        // The rules do not need these two: both produce a prefix the pattern
        // accepts, and both produce a number nobody would want. `SIE/QD/`
        // would print `SIE/QD//2025-26/009`, which reads as a mistake because
        // it is one.
        draft.prefix.trim().endsWith("/") -> PREFIX_TRAILING_SLASH
        draft.prefix.trim().contains("//") -> PREFIX_DOUBLE_SLASH
        draft.financialYear.isBlank() -> YEAR_REQUIRED
        !YEAR.matches(draft.financialYear.trim()) -> YEAR_MALFORMED
        draft.next < 1 -> NEXT_TOO_SMALL
        draft.pad < MIN_PAD || draft.pad > MAX_PAD -> PAD_OUT_OF_RANGE
        rollsTheYear(stored, draft) -> null
        draft.next < stored.next -> alreadyIssued(stored)
        else -> null
    }

    /**
     * The refusal names the lowest number the Owner may set, rather than
     * saying no: somebody correcting a counter needs to know what to type,
     * and "must be greater than the stored value" is a sentence that makes
     * them go and look the stored value up.
     */
    fun alreadyIssued(stored: NumberingRecord): String =
        "The next number can only move forward inside a financial year. " +
            "${format(stored)} has not gone out yet, so type ${stored.next} or more " +
            "— or change the financial year, which starts the sequence again."

    /**
     * What the Owner is about to do, for the confirmation.
     *
     * Shown for a change to the financial year or to `next`, and for nothing
     * else: changing a prefix or the padding changes how the next number
     * reads, which the preview already shows, while these two change **which
     * numbers exist**. A quotation number is the business's own reference on
     * a document somebody else is holding, so the consequence is spelled out
     * before it happens rather than explained afterwards.
     *
     * Null when nothing needing confirmation has changed.
     */
    fun consequenceOf(stored: NumberingRecord, draft: NumberingDraft): NumberingConsequence? = when {
        rollsTheYear(stored, draft) -> NumberingConsequence(
            headline = "Start a new financial year?",
            detail = "The next quotation will be ${format(draft)}, and numbering starts again " +
                "from ${draft.next}. The ${stored.financialYear} quotations already issued keep " +
                "their own numbers and are not touched."
        )
        draft.next != stored.next -> NumberingConsequence(
            headline = "Skip to ${format(draft)}?",
            detail = "The next quotation will be numbered ${format(draft)} instead of " +
                "${format(stored)}. The numbers in between are never issued, so the sequence " +
                "will have a gap in it. This cannot be undone: the counter only moves forward."
        )
        else -> null
    }

    /**
     * The write, in V8C4's own keys.
     *
     * `updated` and `by` are written because `fbSaveNumbering` writes them and
     * the PWA's settings screen shows them. `lastIssued` is **never** touched
     * here: this batch configures the counter and nothing issues a number, so
     * the record of what was last issued belongs to whoever issued it.
     */
    fun save(
        stored: NumberingRecord,
        draft: NumberingDraft,
        author: NumberingAuthor,
        at: Long,
        canConfigure: Boolean
    ): NumberingPlan {
        if (!canConfigure) return NumberingPlan.Refused(NOT_ALLOWED)
        refusal(stored, draft)?.let { return NumberingPlan.Refused(it) }

        val fields = mapOf(
            "prefix" to draft.prefix.trim(),
            "fy" to draft.financialYear.trim(),
            "next" to draft.next,
            "pad" to draft.pad
        )
        if (!changed(stored, fields)) return NumberingPlan.NoChange

        return NumberingPlan.Write(
            fields + mapOf("updated" to at, "by" to author.name)
        )
    }

    private fun changed(stored: NumberingRecord, fields: Map<String, Any?>): Boolean =
        fields["prefix"] != stored.prefix.trim() ||
            fields["fy"] != stored.financialYear.trim() ||
            fields["next"] != stored.next ||
            fields["pad"] != stored.pad
}

/**
 * The Manager discount cap.
 *
 * One number, and the only thing `/teamSettings/quoting` holds today. It is
 * separated from [Numbering] because the two are different documents with
 * different failure modes: a wrong counter reissues a number, a wrong cap
 * refuses a discount somebody is entitled to.
 *
 * **A missing document means nought, not "no limit".** The N5.9 quotation
 * rule reads the cap through a guarded `exists()` and treats an absent one as
 * zero, so an unseeded project refuses a Manager's discount rather than
 * allowing any size of it. The app agrees, so the screen and the server never
 * disagree about what a blank setting means.
 */
object DiscountCap {

    const val NOT_ALLOWED = "Only the Owner can set the discount limit"
    const val OUT_OF_RANGE = "The limit must be between 0% and 100%"

    /** What an unseeded project allows: nothing. */
    const val NONE: Double = 0.0

    const val MIN: Double = 0.0
    const val MAX: Double = 100.0

    fun refusal(percent: Double): String? =
        if (percent.isNaN() || percent < MIN || percent > MAX) OUT_OF_RANGE else null

    /** How the current setting reads on the screen, including the zero case. */
    fun describe(percent: Double): String = when {
        percent <= NONE -> "Managers cannot discount at all"
        percent >= MAX -> "Managers can discount without a limit"
        else -> "Managers can discount up to ${Money.formatQuantity(percent)}%"
    }

    fun save(
        percent: Double,
        author: NumberingAuthor,
        at: Long,
        canConfigure: Boolean
    ): NumberingPlan {
        if (!canConfigure) return NumberingPlan.Refused(NOT_ALLOWED)
        refusal(percent)?.let { return NumberingPlan.Refused(it) }
        return NumberingPlan.Write(
            mapOf(
                "managerDiscountPct" to percent,
                "updated" to at,
                "by" to author.name
            )
        )
    }
}
