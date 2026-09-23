package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.model.PartyRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Catching the customer somebody is about to enter for a second time.
 *
 * V8C4 tries three things **in this order** and the order carries meaning: a
 * GSTIN is a registration and identifies a firm outright; a phone is nearly as
 * good; a name is the weakest, because two genuinely different firms can share
 * one. Reporting *which* test fired is what lets the warning justify itself,
 * and a warning that cannot justify itself gets dismissed.
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

    @Test
    fun `a GSTIN is the strongest match, and is tried first`() {
        // Everything else differs; the registration is the same firm.
        val draft = PartyDraft(name = "Totally Different Ltd", gstin = "27AAACS1234F1Z5")
        val match = PartyDuplicates.find(parties, draft)

        assertEquals("c_1", match?.party?.id)
        assertEquals(PartyMatcher.GSTIN, match?.on)
    }

    @Test
    fun `and it is matched past the case and spacing people type`() {
        val draft = PartyDraft(name = "X", gstin = " 27aaacs1234f1z5 ")
        assertEquals(PartyMatcher.GSTIN, PartyDuplicates.find(parties, draft)?.on)
    }

    @Test
    fun `a phone is next, on its digits alone`() {
        val draft = PartyDraft(name = "Totally Different Ltd", phone = "+91 98765 43210")
        val match = PartyDuplicates.find(parties, draft)

        assertEquals("c_1", match?.party?.id)
        assertEquals(PartyMatcher.PHONE, match?.on)
    }

    @Test
    fun `but a fragment of a number identifies nobody`() {
        // Six digits is an extension or a typo. Offering to open an unrelated
        // customer on that basis is worse than saying nothing.
        val draft = PartyDraft(name = "Totally Different Ltd", phone = "543210")
        assertNull(PartyDuplicates.find(parties, draft))

        // Seven is the floor, and a party whose whole number is seven digits
        // is matched by it.
        val short = party("c_3", name = "Local Shop", phone = "2266554")
        assertEquals(
            PartyMatcher.PHONE,
            PartyDuplicates.find(listOf(short), PartyDraft(name = "X", phone = "2266554"))?.on
        )
    }

    @Test
    fun `a name is the last thing tried, and the weakest`() {
        val draft = PartyDraft(name = "  harbour   interiors  ")
        val match = PartyDuplicates.find(parties, draft)

        assertEquals("c_2", match?.party?.id)
        assertEquals(PartyMatcher.NAME, match?.on)
    }

    @Test
    fun `the strongest match that fires is the one reported`() {
        // The draft matches Harbour by name and Sunrise by GSTIN. GSTIN wins,
        // because that is the order, and the reported party changes with it.
        val draft = PartyDraft(name = "Harbour Interiors", gstin = "27AAACS1234F1Z5")
        val match = PartyDuplicates.find(parties, draft)

        assertEquals("c_1", match?.party?.id)
        assertEquals(PartyMatcher.GSTIN, match?.on)
    }

    @Test
    fun `a genuinely new party matches nothing`() {
        val draft = PartyDraft(
            name = "Metro Glass", gstin = "27AAACM9999F1Z9", phone = "9000000000"
        )
        assertNull(PartyDuplicates.find(parties, draft))
    }

    @Test
    fun `an empty field is not a match, however many parties have one`() {
        // Two parties with no GSTIN must not be duplicates of each other.
        val blanks = listOf(party("c_a", name = "Alpha"), party("c_b", name = "Beta"))
        assertNull(PartyDuplicates.find(blanks, PartyDraft(name = "Gamma")))
        assertNull(PartyDuplicates.find(blanks, PartyDraft(name = "Gamma", gstin = "  ")))
        assertNull(PartyDuplicates.find(blanks, PartyDraft(name = "Gamma", phone = "   ")))
    }

    @Test
    fun `an archived party still counts as a duplicate`() {
        // Re-entering a customer archived last year is exactly the mistake
        // this exists to catch, and silence would let it through.
        val old = party("c_old", name = "Old Client Pvt Ltd", archived = true)
        val match = PartyDuplicates.find(listOf(old), PartyDraft(name = "Old Client Pvt Ltd"))

        assertEquals("c_old", match?.party?.id)
    }

    @Test
    fun `a party being edited never matches itself`() {
        // Without this, saving a party unchanged would warn that it duplicates
        // itself, every single time.
        assertNull(
            PartyDuplicates.find(parties, PartyWrite.draftOf(sunrise), ignoring = sunrise.id)
        )
        // And it still catches a genuine clash with somebody else.
        val clash = PartyWrite.draftOf(sunrise).copy(phone = harbour.phone, gstin = "")
        assertEquals("c_2", PartyDuplicates.find(parties, clash, ignoring = sunrise.id)?.party?.id)
    }
}
