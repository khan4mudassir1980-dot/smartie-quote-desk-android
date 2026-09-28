package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.model.PartyRecord
import `in`.smartie.quotedesk.data.model.QuotationPartySnapshot
import `in`.smartie.quotedesk.data.model.RateTierV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A saved party's type and the quotation's rate (N5.10 commit 9, the Owner's
 * amendment D). The rate follows the type only where there is nothing to
 * reprice and the quotation is not an edit; otherwise it stays, and a note
 * says so. The customer's type is never written from the quotation — that is
 * `PartyWriteRepositoryTest`'s "the stored type is left alone".
 */
class QuotePartyTypeTest {

    private val dealer = PartyRecord(id = "c_d", name = "Harbour Traders", type = "dealer")
    private val client = PartyRecord(id = "c_c", name = "Walk-in Builders", type = "Client")
    private val contractor = PartyRecord(id = "c_k", name = "Sunrise Constructions", type = "contractor")
    private val unset = PartyRecord(id = "c_u", name = "Metro Glass", type = "")

    private val empty = QuoteDraft(id = "qd_1", tier = RateTierV2.CLIENT)
    private val withLines = empty.addManual(id = "ln_1", title = "Site visit", rate = 1_000.0)

    @Test
    fun `picking a Dealer on an empty quotation sets Dealer rates`() {
        // R27. V8C4's own rule (6604): nothing on it, nothing to reprice.
        val pick = QuoteParty.pick(empty, dealer)

        assertEquals(RateTierV2.DEALER, pick.draft.tier)
        assertEquals("c_d", pick.draft.partyId)
        assertNull(pick.note)
    }

    @Test
    fun `picking a Dealer on a quotation with lines leaves its rates, and says so`() {
        // R28.
        val pick = QuoteParty.pick(withLines, dealer)

        assertEquals(RateTierV2.CLIENT, pick.draft.tier)
        assertEquals(withLines.lines, pick.draft.lines)
        assertEquals("c_d", pick.draft.partyId)
        assertEquals(
            "Harbour Traders is saved as a Dealer — this quotation stays at Client rates. Switch above to reprice.",
            pick.note
        )
    }

    @Test
    fun `picking a Dealer on an edit draft leaves its rates - even an empty one`() {
        // R28, Q6: an edit corrects a quotation; it does not reissue it.
        val edit = empty.copy(editOf = EditOrigin("qd_1", "SIE/QD/2025-26/009", tier = RateTierV2.CLIENT))

        val pick = QuoteParty.pick(edit, dealer)

        assertEquals(RateTierV2.CLIENT, pick.draft.tier)
        assertEquals(QuoteParty.typeNote("Harbour Traders", RateTierV2.DEALER, RateTierV2.CLIENT), pick.note)
    }

    @Test
    fun `a copy with lines is a quotation with lines`() {
        val copy = withLines.copy(copiedFrom = CopyOrigin("SIE/QD/2025-26/009"))

        assertEquals(RateTierV2.CLIENT, QuoteParty.pick(copy, dealer).draft.tier)
    }

    @Test
    fun `a party of the quotation's own type moves nothing and says nothing`() {
        val pick = QuoteParty.pick(withLines, client)

        assertEquals(RateTierV2.CLIENT, pick.draft.tier)
        assertNull(pick.note)
    }

    @Test
    fun `a legacy contractor, or a party with no type, leaves the rate and says nothing`() {
        listOf(contractor, unset).forEach { party ->
            listOf(empty, withLines).forEach { draft ->
                val pick = QuoteParty.pick(draft.copy(tier = RateTierV2.DEALER), party)
                assertEquals(party.name, RateTierV2.DEALER, pick.draft.tier)
                assertNull(party.name, pick.note)
                assertEquals(party.id, pick.draft.partyId)
            }
        }
    }

    @Test
    fun `the site already typed survives a pick, as it always has`() {
        val typed = withLines.copy(party = QuotationPartySnapshot(site = "Plot 7"))

        assertEquals("Plot 7", QuoteParty.pick(typed, dealer).draft.party.site)
    }

    @Test
    fun `a new customer's question names them and offers no default`() {
        assertEquals("Save Metro Glass as a Dealer or a Client?", QuoteParty.typeQuestion(" Metro Glass "))
        assertEquals(listOf(RateTierV2.DEALER, RateTierV2.CLIENT), QuoteTier.OFFERED)
    }

    @Test
    fun `only Dealer and Client are types the builder offers`() {
        assertEquals(RateTierV2.DEALER, QuoteParty.offeredTierOf(" Dealer "))
        assertEquals(RateTierV2.CLIENT, QuoteParty.offeredTierOf("client"))
        assertNull(QuoteParty.offeredTierOf("contractor"))
        assertNull(QuoteParty.offeredTierOf(""))
        assertNull(QuoteParty.offeredTierOf("wholesale"))
    }
}
