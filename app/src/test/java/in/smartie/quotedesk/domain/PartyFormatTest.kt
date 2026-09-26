package `in`.smartie.quotedesk.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/**
 * V8C4's `gstinProblem`, `phoneProblem` and `emailProblem`, ported verbatim.
 *
 * Every message is pinned by its text, because the text **is** the port: the
 * Owner sent it from V8C4 (6341-6360) and nothing in this repository could
 * have produced it otherwise.
 */
class PartyFormatTest {

    private val valid = "27FXJPK9635L1ZM" // V8C4's own example, in its message

    // --- blank is valid: format checks, not required fields ---------------------------------

    @Test
    fun `blank is valid in all three`() {
        listOf("", "   ", "\t\n", " ").forEach { blank ->
            assertNull(PartyFormat.gstinProblem(blank))
            assertNull(PartyFormat.phoneProblem(blank))
            assertNull(PartyFormat.emailProblem(blank))
        }
    }

    // --- GSTIN ----------------------------------------------------------------------------

    @Test
    fun `a GSTIN is fifteen characters in V8C4's shape`() {
        assertNull(PartyFormat.gstinProblem(valid))
        assertEquals(
            "A GSTIN is 15 characters, for example 27FXJPK9635L1ZM",
            PartyFormat.gstinProblem(valid.dropLast(1))
        )
        assertEquals(PartyFormat.GSTIN_LENGTH, PartyFormat.gstinProblem(valid + "1"))
        // Fifteen characters, but the fourteenth must be Z.
        assertEquals(
            "That GSTIN does not look right — check it against the certificate",
            PartyFormat.gstinProblem("27FXJPK9635L1XM")
        )
        // The thirteenth is [1-9A-Z]: a zero is not a GSTIN.
        assertEquals(PartyFormat.GSTIN_SHAPE, PartyFormat.gstinProblem("27FXJPK9635L0ZM"))
    }

    @Test
    fun `a GSTIN typed in lower case is upper-cased first, and spaces inside it are not forgiven`() {
        assertNull(PartyFormat.gstinProblem("27fxjpk9635l1zm"))
        // Trimmed at the ends only, as JavaScript's trim(); a space inside
        // makes it sixteen characters.
        assertNull(PartyFormat.gstinProblem("  $valid  "))
        assertEquals(PartyFormat.GSTIN_LENGTH, PartyFormat.gstinProblem("27FXJPK 9635L1ZM"))
    }

    @Test
    fun `upper-casing does not depend on the phone's locale - the Turkish i`() {
        // "27abcie1234f1z5" contains an "i". A default-locale upper-case on a
        // Turkish phone makes it "İ", which is not [A-Z], and a correct GSTIN
        // would be refused. `uppercase()` is locale-invariant, as JavaScript's
        // toUpperCase() is.
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("İ", "i".uppercase(Locale.getDefault()))
            assertNull(PartyFormat.gstinProblem("27abcie1234f1z5"))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `JavaScript's whitespace is trimmed, and only JavaScript's`() {
        // U+00A0 and U+FEFF: JavaScript trims both. Kotlin's trim() does not
        // trim U+FEFF.
        assertNull(PartyFormat.gstinProblem(" $valid "))
        assertNull(PartyFormat.gstinProblem("﻿$valid"))
        // U+001C: Kotlin's trim() removes it, JavaScript's does not — so in
        // V8C4 it is a sixteenth character.
        assertEquals(PartyFormat.GSTIN_LENGTH, PartyFormat.gstinProblem("$valid\u001C"))
        assertEquals("x", PartyFormat.jsTrim(" 　x "))
    }

    // --- phone ----------------------------------------------------------------------------

    @Test
    fun `a phone has ten to thirteen digits, whatever it is written with`() {
        assertNull(PartyFormat.phoneProblem("98765 43210"))
        assertNull(PartyFormat.phoneProblem("+91 98765-43210"))
        assertNull(PartyFormat.phoneProblem("091 98765 43210")) // thirteen
        assertEquals(
            "A phone number needs at least 10 digits",
            PartyFormat.phoneProblem("987654321")
        )
        assertEquals(
            "That phone number has too many digits",
            PartyFormat.phoneProblem("0091 98765 43210") // fourteen
        )
        // Something typed with no digits at all is not blank, and has none.
        assertEquals(PartyFormat.PHONE_TOO_SHORT, PartyFormat.phoneProblem("n/a"))
    }

    // --- email ----------------------------------------------------------------------------

    @Test
    fun `an email needs a name, an at, a domain and a two-letter ending`() {
        assertNull(PartyFormat.emailProblem("sales@sunrise.in"))
        assertNull(PartyFormat.emailProblem("  sales@sunrise.in  "))
        assertEquals("That does not look like an email address", PartyFormat.emailProblem("sales@sunrise.i"))
        assertEquals(PartyFormat.EMAIL_SHAPE, PartyFormat.emailProblem("sales.sunrise.in"))
        assertEquals(PartyFormat.EMAIL_SHAPE, PartyFormat.emailProblem("sales@@sunrise.in"))
    }

    @Test
    fun `whitespace inside an email is JavaScript's, not Java's`() {
        assertEquals(PartyFormat.EMAIL_SHAPE, PartyFormat.emailProblem("sa les@sunrise.in"))
        // Java's \s does not match U+00A0 or U+3000; JavaScript's does, so
        // V8C4 refuses both.
        assertEquals(PartyFormat.EMAIL_SHAPE, PartyFormat.emailProblem("sa les@sunrise.in"))
        assertEquals(PartyFormat.EMAIL_SHAPE, PartyFormat.emailProblem("sales@sun　rise.in"))
    }
}
