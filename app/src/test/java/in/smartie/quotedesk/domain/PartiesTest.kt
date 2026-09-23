package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.model.PartyRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Arranging the saved customers, and the two things about them that are not
 * obvious.
 *
 * **A party is never dropped.** Archiving hides a customer from the working
 * list, but every quotation ever raised against them still points at the same
 * document — so a list that quietly forgot one would make old quotations look
 * as though they were issued to nobody.
 *
 * **Some rows have no firm name at all.** The beta stored the firm in
 * `company` and the contact person in `name`; `toPartyRecord` puts those back
 * the right way round, so the classic damaged row arrives here already fixed.
 * What survives is a party with a contact and nothing to call the firm, and
 * this is where the app decides to say so out loud rather than show a person
 * where a customer's name belongs.
 */
class PartiesTest {

    private fun party(
        id: String,
        name: String = "",
        contact: String = "",
        phone: String = "",
        gstin: String = "",
        city: String = "",
        notes: String = "",
        archived: Boolean = false
    ) = PartyRecord(
        id = id, name = name, contact = contact, phone = phone,
        gstin = gstin, city = city, notes = notes, archived = archived
    )

    private val sunrise = party(
        "c_1", name = "Sunrise Constructions", contact = "Mr Deshmukh",
        phone = "9876543210", gstin = "27AAACS1234F1Z5", city = "Mumbai"
    )
    private val harbour = party("c_2", name = "Harbour Interiors", city = "Thane")
    private val old = party("c_3", name = "Old Client Pvt Ltd", archived = true)

    // --- the two lists ----------------------------------------------------------

    @Test
    fun `archived parties are counted apart, not thrown away`() {
        val book = Parties.build(listOf(sunrise, old, harbour))

        assertEquals(listOf("c_2", "c_1"), book.active.map { it.id })
        assertEquals(listOf("c_3"), book.archived.map { it.id })
        assertEquals(3, book.matchCount)
        assertFalse(book.isEmpty)
    }

    @Test
    fun `and the working list is sorted by name, whatever case it was typed in`() {
        val lower = party("c_a", name = "apex fabricators")
        val upper = party("c_b", name = "Apex Engineering")
        val book = Parties.build(listOf(lower, upper))

        // Engineering before fabricators — not every capital before every
        // lower case, which would put "Apex Engineering" and "apex
        // fabricators" in two different groups.
        assertEquals(listOf("c_b", "c_a"), book.active.map { it.id })
    }

    // --- searching ----------------------------------------------------------------

    @Test
    fun `a search finds a firm, a contact person and a GSTIN`() {
        val parties = listOf(sunrise, harbour, old)

        assertEquals(listOf("c_1"), Parties.build(parties, "sunrise").active.map { it.id })
        assertEquals(listOf("c_1"), Parties.build(parties, "deshmukh").active.map { it.id })
        assertEquals(listOf("c_1"), Parties.build(parties, "27AAACS").active.map { it.id })
        // A GSTIN is written both ways on paper.
        assertEquals(listOf("c_1"), Parties.build(parties, "27aaacs1234f1z5").active.map { it.id })
    }

    @Test
    fun `a phone matches on its digits, however it was written down`() {
        val parties = listOf(sunrise, harbour)

        listOf("9876543210", "98765 43210", "+91-9876543210", "98765-43210").forEach { typed ->
            assertEquals(typed, listOf("c_1"), Parties.build(parties, typed).active.map { it.id })
        }
        // A partial number still narrows the list.
        assertEquals(listOf("c_1"), Parties.build(parties, "543210").active.map { it.id })
    }

    @Test
    fun `a search does not read the notes`() {
        // A note is somebody's aside. Searching it turns a hunt for "Sharma"
        // into every party whose notes happen to mention one.
        val noted = party("c_4", name = "Metro Glass", notes = "Ask for Sharma before noon")
        val book = Parties.build(listOf(noted), "sharma")

        assertTrue(book.isEmpty)
        assertEquals(0, book.matchCount)
    }

    @Test
    fun `an archived party is still findable, in its own list`() {
        // Hidden from the working list is not the same as gone: somebody
        // searching a name they remember must still be able to reach it.
        val book = Parties.build(listOf(sunrise, old), "old client")

        assertTrue(book.active.isEmpty())
        assertEquals(listOf("c_3"), book.archived.map { it.id })
        assertEquals(1, book.matchCount)
    }

    @Test
    fun `a blank search is every party, and is not a search`() {
        val book = Parties.build(listOf(sunrise, harbour), "   ")
        assertFalse(book.searching)
        assertEquals(2, book.active.size)
    }

    // --- rows with nothing to call the firm --------------------------------------------

    @Test
    fun `a row with only a contact is flagged, and shows the person`() {
        // Nothing here can be invented: there is no firm name to show, so the
        // contact is displayed and the row says why.
        val contactOnly = party("c_contact", contact = "Mrs Pinto", phone = "9820011223")

        assertTrue(Parties.missingFirmName(contactOnly))
        assertEquals("Mrs Pinto", Parties.displayName(contactOnly))
    }

    @Test
    fun `a row with a proper firm name is not flagged, even with a contact`() {
        assertFalse(Parties.missingFirmName(sunrise))
        assertEquals("Sunrise Constructions", Parties.displayName(sunrise))
    }

    @Test
    fun `a row with neither says it has no name rather than showing a blank`() {
        val nameless = party("c_empty", phone = "9820011223")

        assertEquals(Parties.UNNAMED, Parties.displayName(nameless))
        // Not flagged: there is no contact standing in for a firm name here,
        // because there is nothing at all. Saying "No firm name" beside a row
        // that shows "Party not named" would be telling somebody twice.
        assertFalse(Parties.missingFirmName(nameless))
    }

    @Test
    fun `whitespace is not a name`() {
        assertEquals(Parties.UNNAMED, Parties.displayName(party("c_ws", name = "   ")))
    }
}
