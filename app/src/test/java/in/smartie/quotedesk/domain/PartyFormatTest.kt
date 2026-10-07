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
 * have produced it otherwise. The one difference is the example GSTIN in
 * [PartyFormat.GSTIN_LENGTH]: a sample whose check character is wrong, by the
 * Owner's decision of 2026-10-07 (N5.12).
 */
class PartyFormatTest {

    // The message's own example. It passes the shape check, which reads no
    // check character; that it is checksum-invalid is the point (N5.12).
    private val valid = "22AAAAA0000A1Z5"

    // --- blank is valid: format checks, not required fields ---------------------------------

    @Test
    fun `blank is valid in all three`() {
        listOf("", "   ", "\t\n", "\u00A0").forEach { blank ->
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
            "A GSTIN is 15 characters, for example 22AAAAA0000A1Z5",
            PartyFormat.gstinProblem(valid.dropLast(1))
        )
        assertEquals(PartyFormat.GSTIN_LENGTH, PartyFormat.gstinProblem(valid + "1"))
        // Fifteen characters, but the fourteenth must be Z.
        assertEquals(
            "That GSTIN does not look right — check it against the certificate",
            PartyFormat.gstinProblem("22AAAAA0000A1X5")
        )
        // The thirteenth is [1-9A-Z]: a zero is not a GSTIN.
        assertEquals(PartyFormat.GSTIN_SHAPE, PartyFormat.gstinProblem("22AAAAA0000A0Z5"))
    }

    @Test
    fun `a GSTIN typed in lower case is upper-cased first, and spaces inside it are not forgiven`() {
        assertNull(PartyFormat.gstinProblem("27fxjpk9635l1zm"))
        // Trimmed at the ends only, as JavaScript's trim(); a space inside
        // makes it sixteen characters.
        assertNull(PartyFormat.gstinProblem("  $valid  "))
        assertEquals(PartyFormat.GSTIN_LENGTH, PartyFormat.gstinProblem("22AAAAA 0000A1Z5"))
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
        assertNull(PartyFormat.gstinProblem("\u00A0$valid\u00A0"))
        assertNull(PartyFormat.gstinProblem("\uFEFF$valid"))
        // U+001C: Kotlin's trim() removes it, JavaScript's does not — so in
        // V8C4 it is a sixteenth character.
        assertEquals(PartyFormat.GSTIN_LENGTH, PartyFormat.gstinProblem("$valid\u001C"))
        assertEquals("x", PartyFormat.jsTrim("\u2003\u3000x\u2028"))
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
        assertNull(PartyFormat.emailProblem("sales@sunrise.invalid"))
        assertNull(PartyFormat.emailProblem("  sales@sunrise.invalid  "))
        assertEquals("That does not look like an email address", PartyFormat.emailProblem("sales@sunrise.i"))
        assertEquals(PartyFormat.EMAIL_SHAPE, PartyFormat.emailProblem("sales.sunrise.invalid"))
        assertEquals(PartyFormat.EMAIL_SHAPE, PartyFormat.emailProblem("sales@@sunrise.invalid"))
    }

    @Test
    fun `whitespace inside an email is JavaScript's, not Java's`() {
        assertEquals(PartyFormat.EMAIL_SHAPE, PartyFormat.emailProblem("sa les@sunrise.invalid"))
        // Java's \s does not match U+00A0 or U+3000; JavaScript's does, so
        // V8C4 refuses both.
        assertEquals(PartyFormat.EMAIL_SHAPE, PartyFormat.emailProblem("sa\u00A0les@sunrise.invalid"))
        assertEquals(PartyFormat.EMAIL_SHAPE, PartyFormat.emailProblem("sales@sun\u3000rise.in"))
    }
}
