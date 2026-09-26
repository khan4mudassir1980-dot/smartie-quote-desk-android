package `in`.smartie.quotedesk.domain

/**
 * **V8C4's three format checks — `gstinProblem`, `phoneProblem` and
 * `emailProblem` (6341-6360) — ported verbatim**, messages included, from the
 * text the Owner sent on 2026-09-26 (advisor-read evidence; recorded in
 * `docs/PROJECT-STATUS.md`).
 *
 * **Blank is valid in all three.** They are format checks, not required
 * fields: each answers null for an empty or all-space value, as V8C4's do.
 *
 * Used by N5.9b's finalise gate — prefixed "Client GSTIN: " and "Client
 * phone: ", as `ensureFinalised` does — and by "Save this customer", which
 * shows the problem bare, as `#qSaveParty`'s `toast(bad)` does.
 *
 * ## Where a port could differ by coincidence, and does not
 *
 * - **Whitespace.** JavaScript's `trim()` and `\s` share one set; Kotlin's
 *   `trim()` and Java's `\s` each use a different one (Kotlin trims U+001C to
 *   U+001F, JavaScript does not; JavaScript trims U+FEFF and its `\s`
 *   matches U+00A0, Java's does not). So the set is spelled out here,
 *   [JS_WHITESPACE], and used for trimming and inside the email pattern.
 * - **Case.** The GSTIN is upper-cased with Kotlin's `String.uppercase()`,
 *   which is locale-invariant as JavaScript's `toUpperCase()` is. A
 *   default-locale call would turn "i" into "İ" on a Turkish-locale phone,
 *   and a correctly typed GSTIN would fail the pattern.
 * - **Anchors.** Java's `$` also matches before a final line terminator;
 *   JavaScript's does not. [Regex.matches] tests the whole string, which is
 *   what `^…$` means in JavaScript.
 * - **Digits.** [PartyDuplicates.digits] is V8C4's `digits` — the one
 *   definition — so the phone counts ASCII digits exactly as `\D` removes the
 *   rest.
 */
object PartyFormat {

    const val GSTIN_LENGTH = "A GSTIN is 15 characters, for example 27FXJPK9635L1ZM"
    const val GSTIN_SHAPE = "That GSTIN does not look right — check it against the certificate"
    const val PHONE_TOO_SHORT = "A phone number needs at least 10 digits"
    const val PHONE_TOO_LONG = "That phone number has too many digits"
    const val EMAIL_SHAPE = "That does not look like an email address"

    /** JavaScript's `WhiteSpace` and `LineTerminator`: what `trim()` removes and `\s` matches. */
    val JS_WHITESPACE: Set<Char> = buildSet {
        addAll(listOf('\t', '\n', '\u000B', '\u000C', '\r', ' ', ' ', ' '))
        addAll(' '..' ')
        addAll(listOf(' ', ' ', ' ', ' ', '　', '﻿'))
    }

    /** The same set as a regex character-class body, for the email pattern. */
    private const val JS_SPACE_CLASS =
        "\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"

    /** `GSTIN_RE`, as V8C4 has it. */
    private val GSTIN = Regex("[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z]{1}[1-9A-Z]{1}Z[0-9A-Z]{1}")

    /** `/^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/`, with JavaScript's `\s`. */
    private val EMAIL = Regex(
        "[^$JS_SPACE_CLASS@]+@[^$JS_SPACE_CLASS@]+\\.[^$JS_SPACE_CLASS@]{2,}"
    )

    /** `"" is fine. Anything else must look like a real GSTIN.` */
    fun gstinProblem(value: String): String? {
        val s = jsTrim(value).uppercase()
        if (s.isEmpty()) return null
        if (s.length != 15) return GSTIN_LENGTH
        if (!GSTIN.matches(s)) return GSTIN_SHAPE
        return null
    }

    /** `"" is fine. Otherwise 10 digits, optionally with a country code.` */
    fun phoneProblem(value: String): String? {
        val s = jsTrim(value)
        if (s.isEmpty()) return null
        val d = PartyDuplicates.digits(s)
        if (d.length < 10) return PHONE_TOO_SHORT
        if (d.length > 13) return PHONE_TOO_LONG
        return null
    }

    /** Blank, or something shaped like an address. */
    fun emailProblem(value: String): String? {
        val s = jsTrim(value)
        return if (s.isEmpty() || EMAIL.matches(s)) null else EMAIL_SHAPE
    }

    /** JavaScript's `String.prototype.trim`. */
    fun jsTrim(value: String): String = value.trim { it in JS_WHITESPACE }
}
