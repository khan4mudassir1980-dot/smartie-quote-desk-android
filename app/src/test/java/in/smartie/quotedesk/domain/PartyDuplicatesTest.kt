package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.model.PartyRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The one rule for "the same party" — V8C4's `sameParty`, `findCustomer` and
 * `matchReason`, as the Owner read them (2026-09-26).
 *
 * **Until N5.9a commit 8b this file pinned N5.5's own rule** — field priority
 * across the list, phones matched by suffix, archived parties flagged. Each of
 * those was stricter or different from the PWA, and the Owner ruled that the
 * Parties screen follows V8C4: so the tests that pinned them now pin the
 * opposite, and say so.
 */
class PartyDuplicatesTest {

    private fun party(
        id: String, name: String = "", gstin: String = "", phone: String = "", archived: Boolean = false
    ) = PartyRecord(id = id, name = name, gstin = gstin, phone = phone, archived = archived)

    private val sunrise = party(
        "c_1", name = "Sunrise Constructions", gstin = "27AAACS1234F1Z5", phone = "9876543210"
    )
    private val harbour = party("c_2", name = "Harbour Interiors", phone = "9820011223")
    private val parties = listOf(sunrise, harbour)

    // --- norm and digits, exactly ------------------------------------------------------

    @Test
    fun `norm strips every character that is not a letter or a digit`() {
        // The Owner's three examples. `3d`'s port — trim, lower case, single
        // spaces — kept all of this and matched strictly fewer pairs.
        assertEquals(PartyDuplicates.norm("M s Sunrise Ent"), PartyDuplicates.norm("M/s Sunrise Ent."))
        assertEquals(PartyDuplicates.norm("Sunrise Enterprises"), PartyDuplicates.norm("Sunrise Enterprises."))
        assertEquals(PartyDuplicates.norm("27AABCU9603R1ZM"), PartyDuplicates.norm("27 AABCU 9603 R1ZM"))
        assertEquals("abc123", PartyDuplicates.norm(" A-b&C / 1.2_3 "))
    }

    @Test
    fun `digits keeps ASCII digits only, compared whole`() {
        assertEquals("919876543210", PartyDuplicates.digits("+91 98765-43210"))
        assertEquals("", PartyDuplicates.digits("n/a"))
    }

    @Test
    fun `a GSTIN typed with spaces is the same registration`() {
        val draft = PartyDraft(name = "Totally Different Ltd", gstin = "27 aaacs 1234 f1z5")
        val match = PartyDuplicates.find(parties, draft)
        assertEquals("c_1", match?.party?.id)
        assertEquals(PartyMatcher.GSTIN, match?.on)
    }

    @Test
    fun `a company name matches past its punctuation`() {
        val ms = party("c_3", name = "M/s Sunrise Ent.")
        val match = PartyDuplicates.find(listOf(ms), PartyDraft(name = "M s Sunrise Ent"))
        assertEquals("c_3", match?.party?.id)
        assertEquals(PartyMatcher.NAME, match?.on)
    }

    // --- the phone ---------------------------------------------------------------------

    @Test
    fun `a phone matches on its full digits`() {
        val draft = PartyDraft(name = "Totally Different Ltd", phone = "98765 43210")
        val match = PartyDuplicates.find(parties, draft)
        assertEquals("c_1", match?.party?.id)
        assertEquals(PartyMatcher.PHONE, match?.on)
    }

    @Test
    fun `a country code in front of a number is NOT the same line - as in V8C4`() {
        // N5.5 matched this by suffix; V8C4 compares the full digit strings
        // and accepted the miss. The Owner ruled the Parties screen follows
        // V8C4, so this pins the opposite of what it used to.
        assertNull(PartyDuplicates.find(parties, PartyDraft(name = "X", phone = "+91 98765 43210")))
    }

    @Test
    fun `a fragment of a number identifies nobody, and seven digits is the floor`() {
        assertNull(PartyDuplicates.find(parties, PartyDraft(name = "X", phone = "543210")))
        val short = party("c_3", name = "Local Shop", phone = "2266554")
        assertEquals(
            PartyMatcher.PHONE,
            PartyDuplicates.find(listOf(short), PartyDraft(name = "X", phone = "2266554"))?.on
        )
    }

    // --- which match, and why ----------------------------------------------------------

    @Test
    fun `the first match in list order wins - not the strongest field`() {
        // The draft is Harbour by name and Sunrise by GSTIN. V8C4's
        // `findCustomer` is a plain `.find()`, so the first party in the list
        // that is the same party at all is the one — and `matchReason` then
        // says why for that party. N5.5 took the GSTIN match wherever it was.
        val draft = PartyDraft(name = "Harbour Interiors", gstin = "27AAACS1234F1Z5")

        val harbourFirst = PartyDuplicates.find(listOf(harbour, sunrise), draft)
        assertEquals("c_2", harbourFirst?.party?.id)
        assertEquals(PartyMatcher.NAME, harbourFirst?.on)

        val sunriseFirst = PartyDuplicates.find(listOf(sunrise, harbour), draft)
        assertEquals("c_1", sunriseFirst?.party?.id)
        assertEquals(PartyMatcher.GSTIN, sunriseFirst?.on)
    }

    @Test
    fun `the reason is matchReason's, in its order and its words`() {
        // Sunrise matches on all three; the GSTIN is named.
        assertEquals(PartyMatcher.GSTIN, PartyDuplicates.find(parties, PartyWrite.draftOf(sunrise))?.on)
        assertEquals("the same GSTIN", PartyMatcher.GSTIN.label)
        assertEquals("the same phone number", PartyMatcher.PHONE.label)
        assertEquals("the same company name", PartyMatcher.NAME.label)
    }

    @Test
    fun `a genuinely new party matches nothing, and empty fields are never a match`() {
        assertNull(
            PartyDuplicates.find(parties, PartyDraft(name = "Metro Glass", gstin = "27AAACM9999F1Z9", phone = "9000000000"))
        )
        val blanks = listOf(party("c_a", name = "Alpha"), party("c_b", name = "Beta"))
        assertNull(PartyDuplicates.find(blanks, PartyDraft(name = "Gamma")))
        assertNull(PartyDuplicates.find(blanks, PartyDraft(name = "Gamma", gstin = " - ")))
        assertNull(PartyDuplicates.find(blanks, PartyDraft(name = "Gamma", phone = "   ")))
    }

    @Test
    fun `an archived party is NOT flagged - as in V8C4`() {
        // N5.5 flagged it; V8C4's `findCustomer` skips archived parties.
        val old = party("c_old", name = "Old Client Pvt Ltd", archived = true)
        assertNull(PartyDuplicates.find(listOf(old), PartyDraft(name = "Old Client Pvt Ltd")))
    }

    @Test
    fun `a party being edited never matches itself - which is what exceptId is for`() {
        // V8C4's Edit party calls `findCustomer(v, c.id)`.
        assertNull(PartyDuplicates.find(parties, PartyWrite.draftOf(sunrise), ignoring = sunrise.id))
        val clash = PartyWrite.draftOf(sunrise).copy(name = "Renamed", phone = harbour.phone, gstin = "")
        assertEquals("c_2", PartyDuplicates.find(parties, clash, ignoring = sunrise.id)?.party?.id)
    }
}
