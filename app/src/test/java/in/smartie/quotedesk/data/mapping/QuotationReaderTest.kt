package `in`.smartie.quotedesk.data.mapping

import `in`.smartie.quotedesk.data.model.QuotationDiscountRecord
import `in`.smartie.quotedesk.data.model.QuotationInstallationRecord
import `in`.smartie.quotedesk.data.model.RateTierV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What N5.10 needs from a stored quotation that N5.4's reader never read.
 *
 * An edit rebuilds a draft from the stored document and writes every money
 * figure back, so anything the reader drops is lost on the first save: a
 * quotation's installation and discount, before N5.10 commit 1. And anything
 * the reader *defaults* becomes a figure nobody chose — which is why the raw
 * tier, the raw GST rate and the raw installation mode are kept beside the
 * lenient readings the screens use.
 */
class QuotationReaderTest {

    private fun quotation(vararg fields: Pair<String, Any?>) = DocData(
        "qd_1",
        mapOf(
            "id" to "qd_1",
            "no" to "SIE/QD/2025-26/009",
            "at" to 1_750_000_000_000L,
            "byUid" to "u_manager",
            "tier" to "client",
            "gst" to true,
            "gstPct" to 18.0,
            "subtotal" to 42_960.0,
            "total" to 50_693.0
        ) + fields
    ).toQuotationRecord()

    @Test
    fun `a native quotation's installation and discount are read back`() {
        // R1: without this an edit's first save drops both, silently.
        val record = quotation(
            "install" to mapOf("mode" to "door", "rate" to 500.0, "amt" to 2_000.0, "basis" to 4.0),
            "disc" to mapOf("kind" to "pct", "value" to 10.0, "amt" to 4_440.0),
            "discBase" to 44_400.0
        )

        assertEquals(
            QuotationInstallationRecord(mode = "door", rate = 500.0, amount = 2_000.0, basis = 4.0),
            record.installation
        )
        assertEquals(QuotationDiscountRecord(kind = "pct", value = 10.0, amount = 4_440.0), record.discount)
        assertEquals(44_400.0, record.discountBase)
    }

    @Test
    fun `a quotation carrying neither has none, not zeros`() {
        // Every V8C4 quotation: no `install`, no `disc`, no `discBase`.
        val record = quotation()

        assertNull(record.installation)
        assertNull(record.discount)
        assertNull(record.discountBase)
    }

    @Test
    fun `an unreadable installation is kept as stored, never defaulted`() {
        // `InstallationMode.from` would read "weekly" as `fixed`. The record
        // keeps the word, and a figure that does not read stays null, so the
        // edit can refuse rather than rebuild a charge nobody made.
        val record = quotation("install" to mapOf("mode" to " weekly ", "rate" to "a lot", "amt" to 900.0))

        assertEquals("weekly", record.installation?.mode)
        assertNull(record.installation?.rate)
        assertEquals(900.0, record.installation?.amount)
        assertNull(record.installation?.basis)
    }

    @Test
    fun `the edit stamp and the revision are read`() {
        val record = quotation(
            "lastEditedBy" to "Asha Nair",
            "lastEditedByUid" to "u_manager",
            "lastEditedAt" to 1_760_000_000_000L,
            "rev" to 3
        )

        assertEquals("Asha Nair", record.lastEditedBy)
        assertEquals("u_manager", record.lastEditedByUid)
        assertEquals(1_760_000_000_000L, record.lastEditedAt)
        assertEquals(3, record.revision)
    }

    @Test
    fun `a quotation nobody has edited has no stamp and revision zero`() {
        // Revision zero is what the rule reads an absent `rev` as, so the first
        // edit's `rev` of one is the stored revision plus one on both sides.
        val record = quotation()

        assertEquals("", record.lastEditedBy)
        assertEquals(0L, record.lastEditedAt)
        assertEquals(0, record.revision)
    }

    @Test
    fun `an absent GST rate is not set for an edit, though the screen reads zero`() {
        val absent = quotation("gstPct" to null)
        assertEquals("the screen's reading, unchanged", 0.0, absent.gstPercent, 0.0)
        assertNull("an edit's: not set, never 0%", absent.storedGstPercent)

        assertEquals(18.0, quotation().storedGstPercent)
    }

    @Test
    fun `an unknown tier is kept as stored beside the Dealer the screen reads`() {
        val record = quotation("tier" to " wholesale ")

        assertEquals(RateTierV2.from("wholesale"), record.tier)
        assertEquals("wholesale", record.storedTier)
    }
}
