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
                rate = 1250.5,
                originalRate = 1250.5,
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
                "2.0", "1250.5", "1250.5", "dealer", "0",
            ).joinToString("\u001f"),
        ).joinToString("\u001e")

        val restored = QuoteDraftCodec.decode(v1)
        assertEquals(RateTierV2.DEALER, restored.tier)
        val line = restored.lines.single()
        assertEquals("gate|SIE1000", line.key)
        assertEquals(2.0, line.quantity, 0.0)
        assertEquals(1250.5, line.rate!!, 0.0)
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
            id = "ln_x", area = area, rate = 450.0, title = "Rolling shutter", key = "rs|RS500"
        )
        val line = roundTrip(drafted).lines.single()
        assertNotNull(line.area)
        assertEquals(area, line.area)
        assertEquals(ProductUnit.AREA, line.unit)
        // The stored quantity is the total chargeable area, so the PWA prints
        // the line right: 113.5 per door, two doors.
        assertEquals(227.0, line.quantity, 0.0)
        assertEquals(450.0, line.rate!!, 0.0)
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
