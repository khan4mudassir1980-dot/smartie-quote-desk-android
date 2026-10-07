package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.model.RateTierV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A draft on the device, and the two ways storing one can lose money.
 *
 * **An unset rate must never come back as zero**, which is what the original
 * file was written for. **And a version bump must never erase what is already
 * stored**: `decode` answers an empty draft when the version string is one it
 * does not know, so appending a field under a new version silently wipes every
 * draft on every phone at the next update. Fields are appended and old
 * versions stay readable; the `v1` test below is what keeps that true.
 */
class QuoteDraftCodecTest {

    private val draft = QuoteDraft(
        tier = RateTierV2.CONTRACTOR,
        lines = listOf(
            DraftLine(
                id = "ln_a",
                key = "gate|SIE1000",
                title = "SIE1000",
                spec = "24V DC, 1000 kg",
                unit = "each",
                quantity = 2.0,
                rate = 1234.5,
                originalRate = 1234.5,
                tier = RateTierV2.CONTRACTOR,
            ),
            DraftLine(
                id = "ln_b",
                key = "gate|SIE600",
                title = "SIE600",
                quantity = 1.0,
                rate = null,
                originalRate = null,
                tier = RateTierV2.CONTRACTOR,
            ),
        ),
    )

    private fun roundTrip(value: QuoteDraft) = QuoteDraftCodec.decode(QuoteDraftCodec.encode(value))

    @Test
    fun `a draft survives a round trip unchanged`() {
        assertEquals(draft, roundTrip(draft))
    }

    @Test
    fun `an edit's origin and a copy's survive a round trip - N5_10`() {
        // Kept on the device so an edit survives the app being killed, and so
        // a reopened edit still knows which quotation and which revision it
        // was opened at.
        val edit = draft.copy(
            id = "qd_issued",
            editOf = EditOrigin(
                quotationId = "qd_issued",
                number = "SIE/QD/2025-26/009",
                revision = 3,
                tier = RateTierV2.CONTRACTOR,
                discountAmount = 3_400.0,
                discountBase = 34_000.0
            ),
            copiedFrom = CopyOrigin("SIE/QD/2025-26/007", 1_712_000_000_000L)
        )
        assertEquals(edit, roundTrip(edit))
    }

    @Test
    fun `a draft stored before N5_10 reads back as no edit and no copy`() {
        // The head of a draft written by 9b stops at the transport note, its
        // 22nd field; the eight N5.10 appends are simply absent.
        val records = QuoteDraftCodec.encode(draft).split('\u001E')
        val head = records.first().split('\u001F')
        // Nine since N5.10 commit 8 appended `tierRepriced`.
        assertEquals("today's head is 22 fields and nine more", 31, head.size)
        val before = (listOf(head.take(22).joinToString("\u001F")) + records.drop(1)).joinToString("\u001E")
        val restored = QuoteDraftCodec.decode(before)
        assertNull(restored.editOf)
        assertNull(restored.copiedFrom)
        assertEquals(draft, restored)
    }

    @Test
    fun `a draft stored before N5_10 commit 8 reads back as not repriced`() {
        // Its head stops after the copy's origin, the 30th field.
        val copied = draft.copy(copiedFrom = CopyOrigin("SIE/QD/2025-26/009", 1_760_000_000_000L), tierRepriced = true)
        val records = QuoteDraftCodec.encode(copied).split('\u001E')
        val head = records.first().split('\u001F')
        val before = (listOf(head.take(30).joinToString("\u001F")) + records.drop(1)).joinToString("\u001E")

        val restored = QuoteDraftCodec.decode(before)
        assertEquals(CopyOrigin("SIE/QD/2025-26/009", 1_760_000_000_000L), restored.copiedFrom)
        assertFalse(restored.tierRepriced)
    }

    @Test
    fun `a switch of tier that repriced a line survives the store`() {
        val repriced = draft.copy(copiedFrom = CopyOrigin("SIE/QD/2025-26/009"), tierRepriced = true)
        assertTrue(roundTrip(repriced).tierRepriced)
        assertFalse(roundTrip(repriced.copy(tierRepriced = false)).tierRepriced)
    }

    @Test
    fun `an edit's unreadable tier comes back as none to keep, never Dealer`() {
        val edit = draft.copy(editOf = EditOrigin("qd_1", "SIE/QD/2025-26/009", tier = RateTierV2.CLIENT))
        val damaged = QuoteDraftCodec.encode(edit).replace("\u001Fclient\u001F", "\u001Fwholesale\u001F")
        assertNull(QuoteDraftCodec.decode(damaged).editOf?.tier)
    }

    @Test
    fun `an unset rate comes back unset, never zero`() {
        val restored = roundTrip(draft)
        val unpriced = restored.lines.first { it.key == "gate|SIE600" }
        assertNull(unpriced.rate)
        assertNull(unpriced.originalRate)
        assertNull(unpriced.amount)
        assertTrue(unpriced.needsRate)
        assertEquals(1, restored.needsRateCount)
    }

    @Test
    fun `a hand-typed rate stays marked as edited`() {
        val edited = draft.setRate("ln_a", 999.0)
        val line = roundTrip(edited).lines.first { it.id == "ln_a" }
        assertTrue(line.rateEdited)
        assertEquals(999.0, line.rate!!, 0.0)
    }

    @Test
    fun `separators inside a product name survive`() {
        val awkward = QuoteDraft(
            lines = listOf(
                DraftLine(
                    id = "ln_odd",
                    key = "gate|ODD",
                    title = "SIE1000\u001fX",
                    spec = "back\\slash",
                    quantity = 1.0,
                    rate = 10.0,
                )
            )
        )
        val restored = roundTrip(awkward)
        assertEquals("SIE1000\u001fX", restored.lines.single().title)
        assertEquals("back\\slash", restored.lines.single().spec)
    }

    @Test
    fun `an empty or unreadable store decodes to an empty draft`() {
        assertEquals(QuoteDraft(), QuoteDraftCodec.decode(null))
        assertEquals(QuoteDraft(), QuoteDraftCodec.decode(""))
        assertEquals(QuoteDraft(), QuoteDraftCodec.decode("rubbish"))
        assertEquals(QuoteDraft(), QuoteDraftCodec.decode("v9\u001fclient"))
    }

    @Test
    fun `a truncated or zero-quantity line is dropped rather than restored wrong`() {
        val encoded = QuoteDraftCodec.encode(draft)
        val broken = encoded.substringBeforeLast('\u001e') + "\u001egate|XX"
        val restored = QuoteDraftCodec.decode(broken)
        assertEquals(1, restored.lineCount)
        assertEquals("gate|SIE1000", restored.lines.single().key)
    }

    @Test
    fun `an empty draft keeps its tier`() {
        val empty = QuoteDraft(tier = RateTierV2.DEALER)
        assertEquals(empty, roundTrip(empty))
    }

    // --- the version cliff, which is the expensive one --------------------------

    @Test
    fun `a v1 draft still decodes, rather than being silently erased`() {
        // Written by hand in the exact shape v1 wrote: nine fields, no id, no
        // manual flag, no opening. If this ever fails, every draft on every
        // phone is being thrown away by an app update.
        val v1 = listOf(
            "v1\u001fdealer",
            listOf(
                "gate|SIE1000", "SIE1000", "24V DC", "each",
                "2.0", "1234.5", "1234.5", "dealer", "0",
            ).joinToString("\u001f"),
        ).joinToString("\u001e")

        val restored = QuoteDraftCodec.decode(v1)
        assertEquals(RateTierV2.DEALER, restored.tier)
        val line = restored.lines.single()
        assertEquals("gate|SIE1000", line.key)
        assertEquals(2.0, line.quantity, 0.0)
        assertEquals(1234.5, line.rate!!, 0.0)
        // It had no id, so one is derived from the product key it did carry.
        assertEquals("${QuoteDraftCodec.V1_ID_PREFIX}gate|SIE1000", line.id)
        assertFalse(line.manual)
        assertNull(line.area)
    }

    // --- the shapes v1 could not hold at all -------------------------------------

    @Test
    fun `a manual line survives, where v1 dropped it for having no product key`() {
        // v1 discarded any line with a blank key, so a hand-typed line never
        // came back from a restart at all.
        val manual = QuoteDraft().addManual(
            id = "ln_m", title = "Site measurement visit", quantity = 1.0, rate = 2000.0
        )
        val line = roundTrip(manual).lines.single()
        assertEquals("ln_m", line.id)
        assertEquals("Site measurement visit", line.title)
        assertEquals("", line.key)
        assertTrue(line.manual)
        assertEquals(2000.0, line.rate!!, 0.0)
    }

    @Test
    fun `an opening survives with every part of it`() {
        val area = AreaLine(width = 3000.0, height = 3500.0, count = 2.0, minimumSqft = 10.0)
        val drafted = QuoteDraft().addArea(
            id = "ln_x", area = area, rate = 350.0, title = "Rolling shutter", key = "rs|RS500"
        )
        val line = roundTrip(drafted).lines.single()
        assertNotNull(line.area)
        assertEquals(area, line.area)
        assertEquals(ProductUnit.AREA, line.unit)
        // The stored quantity is the total chargeable area, so the PWA prints
        // the line right: 113.5 per door, two doors.
        assertEquals(227.0, line.quantity, 0.0)
        assertEquals(350.0, line.rate!!, 0.0)
    }

    @Test
    fun `an opening with no minimum keeps having none`() {
        val area = AreaLine(width = 10.0, height = 8.0, unit = DimensionUnit.FT)
        val line = roundTrip(
            QuoteDraft().addArea(id = "ln_y", area = area, rate = 100.0, title = "Panel")
        ).lines.single()
        assertNull(line.area!!.minimumSqft)
        assertEquals(DimensionUnit.FT, line.area!!.unit)
        assertEquals(80.0, line.quantity, 0.0)
    }
}
