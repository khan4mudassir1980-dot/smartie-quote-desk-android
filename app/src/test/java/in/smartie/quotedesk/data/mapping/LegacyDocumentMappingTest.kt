package `in`.smartie.quotedesk.data.mapping

import `in`.smartie.quotedesk.data.model.UrgencyV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every case here is a symptom the parity audit traced to a strict cast or a
 * renamed field in the beta mappers.
 */
class LegacyDocumentMappingTest {

    // --- users -------------------------------------------------------------

    @Test
    fun `a profile without an active field is treated as active`() {
        val member = Fixtures.loadOne("users.json", "uid_legacy_no_active").toTeamMember()
        assertTrue(member.active)
        assertEquals("staff", member.roleWireValue)
    }

    @Test
    fun `a switched off profile stays switched off`() {
        val member = Fixtures.loadOne("users.json", "uid_switched_off").toTeamMember()
        assertFalse(member.active)
    }

    @Test
    fun `a profile without a role defaults to worker`() {
        val member = Fixtures.loadOne("users.json", "uid_no_role").toTeamMember()
        assertEquals("worker", member.roleWireValue)
    }

    @Test
    fun `the stored email keeps its original casing`() {
        val member = Fixtures.loadOne("users.json", "uid_owner").toTeamMember()
        assertEquals("Khan4Mudassir1980@gmail.com", member.email)
        assertEquals("khan4mudassir1980@gmail.com", Keys.normaliseEmail(member.email))
    }

    // --- products ----------------------------------------------------------

    @Test
    fun `both product id schemes resolve to one logical key`() {
        val seeded = Fixtures.loadOne("products.json", "gateMotors|SIE1000").toProductRecord()
        val edited = Fixtures.loadOne("products.json", "gateMotors__SIE1000").toProductRecord()
        assertEquals(seeded.key, edited.key)
        // The document ids differ, which is what Compose must key on.
        assertFalse(seeded.documentId == edited.documentId)
        assertEquals("gateMotors__SIE1000", Keys.productDocId(seeded.group, seeded.seedModel))
    }

    @Test
    fun `a document written before seedModel existed falls back to its key`() {
        val shutter = Fixtures.loadOne("products.json", "shutterMotors__RS500").toProductRecord()
        assertEquals("shutterMotors", shutter.group)
        assertEquals("RS500", shutter.seedModel)
        assertEquals("shutterMotors|RS500", shutter.key)
        // `active: 1` is the PWA's older boolean.
        assertTrue(shutter.active)
        assertEquals(500.0, shutter.kg!!, 0.0)
    }

    @Test
    fun `the pinned shelf reads as a list of logical keys`() {
        val pins = Fixtures.loadOne("product_pins.json", "productPins")["keys"].asStringList()
        assertEquals(listOf("gateMotors|SIE1000", "boom|BB6", "gone|MISSING"), pins)
    }

    @Test
    fun `a product with no price shows as not set rather than zero`() {
        val glass = Fixtures.loadOne("products.json", "glass__TG12").toProductRecord()
        assertNull(glass.dealer)
        assertNull(glass.contractor)
        assertNull(glass.client)
        assertEquals(18.0, glass.gst, 0.0)
    }

    @Test
    fun `a price stored as a formatted string is read`() {
        val wheel = Fixtures.loadOne("products.json", "hwWheel__SIEBAL58H_V").toProductRecord()
        assertEquals(1250.5, wheel.dealer!!, 0.0)
        assertTrue(wheel.active)
        assertEquals("hwWheel|SIEBAL58H/V", wheel.key)
    }

    @Test
    fun `an archived product is readable and marked inactive`() {
        val archived = Fixtures.loadOne("products.json", "hsd__ARCHIVED1").toProductRecord()
        assertFalse(archived.active)
    }

    @Test
    fun `categories come out of the settings map in order and keep archived flags`() {
        val categories = Fixtures.loadOne("team_settings.json", "categories").toProductCategories()
        assertEquals(listOf("motors", "glass", "retired"), categories.map { it.id })
        assertTrue(categories.first { it.id == "retired" }.archived)
    }

    // --- stock -------------------------------------------------------------

    @Test
    fun `a quantity stored as a string is not read as zero`() {
        val wheel = Fixtures.loadOne("stock.json", "hwWheel|SIEBAL58H_V").toStockRecord()
        assertEquals(5.0, wheel.quantity, 0.0)
        assertEquals(1.0, wheel.reorderLevel, 0.0)
        assertFalse(wheel.isOut)
        // The key keeps the slash; only the document id is sanitised.
        assertEquals("hwWheel|SIEBAL58H/V", wheel.key)
        assertEquals("hwWheel|SIEBAL58H_V", Keys.sanitiseDocId(wheel.key))
    }

    @Test
    fun `an item switched off with off 1 is archived`() {
        val archived = Fixtures.loadOne("stock.json", "hsd|ARCHIVED1").toStockRecord()
        assertTrue(archived.archived)
    }

    @Test
    fun `a manual item keeps its name, note and manual flag`() {
        val manual = Fixtures.loadOne("stock.json", "manualstock|manual-tape").toStockRecord()
        assertTrue(manual.manual)
        assertEquals("Insulation tape", manual.name)
        assertEquals("Kept in the van", manual.note)
        assertTrue(manual.isOut)
    }

    // --- stock movements ---------------------------------------------------

    @Test
    fun `a PWA movement keeps its signed delta`() {
        val incoming = Fixtures.loadOne("stock_moves.json", "mv_pwa_in").toStockMove()
        val outgoing = Fixtures.loadOne("stock_moves.json", "mv_pwa_out").toStockMove()
        assertEquals(3.0, incoming.delta, 0.0)
        assertEquals(-2.0, outgoing.delta, 0.0)
        assertEquals("+3", Money.formatDelta(incoming.delta))
        assertEquals("-2", Money.formatDelta(outgoing.delta))
    }

    @Test
    fun `a beta movement without a delta is signed from prev and next`() {
        val move = Fixtures.loadOne("stock_moves.json", "mv_beta_absolute").toStockMove()
        assertEquals(-2.0, move.delta, 0.0)
    }

    @Test
    fun `a movement whose numbers are strings still reads`() {
        val move = Fixtures.loadOne("stock_moves.json", "mv_string_numbers").toStockMove()
        assertEquals(4.0, move.delta, 0.0)
        assertEquals(1712000000000L, move.at)
    }

    // --- purchase ----------------------------------------------------------

    @Test
    fun `received 1 closes a requirement`() {
        val received = Fixtures.loadOne("purchase.json", "pr_received_legacy").toPurchaseRecord()
        assertTrue(received.received)
        assertTrue(received.isClosed)
        assertFalse(received.isOpen)
        assertEquals(10.0, received.quantity, 0.0)
        assertEquals(10.0, received.receivedQuantity!!, 0.0)
    }

    @Test
    fun `a soft deleted requirement is never open`() {
        val deleted = Fixtures.loadOne("purchase.json", "pr_soft_deleted").toPurchaseRecord()
        assertTrue(deleted.deleted)
        assertFalse(deleted.isOpen)
    }

    @Test
    fun `an open requirement keeps its creation time and urgency`() {
        val open = Fixtures.loadOne("purchase.json", "pr_open").toPurchaseRecord()
        assertTrue(open.isOpen)
        assertEquals(UrgencyV2.URGENT, open.urgency)
        assertEquals(1712000000000L, open.createdAt)
    }

    @Test
    fun `a requirement with no creation time falls back to its update time`() {
        val record = Fixtures.loadOne("purchase.json", "pr_critical_no_created").toPurchaseRecord()
        assertEquals(1713000000000L, record.createdAt)
        assertEquals(UrgencyV2.CRITICAL, record.urgency)
        assertFalse(record.received)
    }

    // --- parties -----------------------------------------------------------

    @Test
    fun `a PWA party keeps every field the beta used to erase`() {
        val party = Fixtures.loadOne("customers.json", "c_pwa").toPartyRecord()
        assertEquals("Sunrise Constructions", party.name)
        assertEquals("Mr Deshmukh", party.contact)
        assertEquals("contractor", party.type)
        assertEquals("Pays in 30 days", party.notes)
        assertFalse(party.archived)
    }

    @Test
    fun `a beta party maps company back to the party name`() {
        val party = Fixtures.loadOne("customers.json", "c_beta_damaged").toPartyRecord()
        assertEquals("Harbour Interiors", party.name)
        assertEquals("Mrs Pinto", party.contact)
    }

    @Test
    fun `archived 1 marks a party archived`() {
        assertTrue(Fixtures.loadOne("customers.json", "c_archived").toPartyRecord().archived)
    }

    // --- quotations --------------------------------------------------------

    @Test
    fun `a PWA quotation reads its lines, party and GST`() {
        val quote = Fixtures.loadOne("quotations.json", "q_pwa_finalised").toQuotationRecord()
        assertEquals("SIE/QD/2025-26/007", quote.number)
        assertEquals("Sunrise Constructions", quote.party.name)
        assertEquals(2, quote.lines.size)
        assertEquals("Sliding gate motor 1000 kg", quote.lines[0].title)
        assertEquals(44400.0, quote.lines[0].amount, 0.0)
        assertTrue(quote.gstEnabled)
        assertEquals(18.0, quote.gstPercent, 0.0)
        assertFalse(quote.legacyBetaShape)
    }

    @Test
    fun `a beta quotation is readable and flagged`() {
        val quote = Fixtures.loadOne("quotations.json", "q_beta_issued").toQuotationRecord()
        assertTrue(quote.legacyBetaShape)
        assertEquals("Harbour Interiors", quote.party.name)
        assertEquals("Toughened glass 12 mm", quote.lines.single().title)
        assertEquals(17400.0, quote.lines.single().amount, 0.0)
        assertEquals(18.0, quote.gstPercent, 0.0)
        assertTrue(quote.gstEnabled)
    }

    @Test
    fun `a quotation with string totals and a plain party string reads`() {
        val quote = Fixtures.loadOne("quotations.json", "q_string_totals").toQuotationRecord()
        assertEquals(12390.0, quote.total, 0.0)
        assertEquals(10500.0, quote.subtotal, 0.0)
        assertEquals("Walk-in customer", quote.party.name)
        assertEquals(1690000000000L, quote.at)
        assertTrue(quote.gstEnabled)
    }

    // --- team settings -----------------------------------------------------

    @Test
    fun `access reads with and without a primary owner uid`() {
        val beforeMigration = Fixtures.loadOne("team_settings.json", "access").toTeamAccess()
        val afterMigration = Fixtures.loadOne("team_settings.json", "access_migrated").toTeamAccess()
        assertEquals("", beforeMigration.primaryOwnerUid)
        assertEquals("uid_admin", beforeMigration.secondOwnerUid)
        assertEquals("uid_owner", afterMigration.primaryOwnerUid)
        assertEquals("", afterMigration.secondOwnerUid)
    }

    @Test
    fun `numbering reads the counter and the last issued number`() {
        val numbering = Fixtures.loadOne("team_settings.json", "numbering").toNumberingRecord()
        assertEquals("SIE/QD", numbering.prefix)
        assertEquals("2025-26", numbering.financialYear)
        assertEquals(9, numbering.next)
        assertEquals("SIE/QD/2025-26/008", numbering.lastIssuedNumber)
        assertEquals("uid_admin", numbering.lastIssuedUid)
    }

    @Test
    fun `an audit entry keeps the PWA action vocabulary and detail`() {
        val entry = Fixtures.loadOne("team_audit.json", "ta_role_change").toTeamAuditEntry()
        assertEquals("role_changed", entry.action)
        assertEquals("role changed", entry.readableAction)
        assertEquals("staff", entry.detailFrom)
        assertEquals("admin", entry.detailTo)
    }
}
