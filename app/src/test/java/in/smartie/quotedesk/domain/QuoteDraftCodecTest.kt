package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.model.RateTierV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuoteDraftCodecTest {

    private val draft = QuoteDraft(
        tier = RateTierV2.CONTRACTOR,
        lines = listOf(
            DraftLine(
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
                key = "gate|SIE600",
                title = "SIE600",
                quantity = 1.0,
                rate = null,
                originalRate = null,
                tier = RateTierV2.CONTRACTOR,
            ),
        ),
    )

    @Test
    fun `a draft survives a round trip unchanged`() {
        assertEquals(draft, QuoteDraftCodec.decode(QuoteDraftCodec.encode(draft)))
    }

    @Test
    fun `an unset rate comes back unset, never zero`() {
        val restored = QuoteDraftCodec.decode(QuoteDraftCodec.encode(draft))
        val unpriced = restored.lines.first { it.key == "gate|SIE600" }
        assertNull(unpriced.rate)
        assertNull(unpriced.originalRate)
        assertNull(unpriced.amount)
        assertTrue(unpriced.needsRate)
        assertEquals(1, restored.needsRateCount)
    }

    @Test
    fun `a hand-typed rate stays marked as edited`() {
        val edited = draft.setRate("gate|SIE1000", 999.0)
        val restored = QuoteDraftCodec.decode(QuoteDraftCodec.encode(edited))
        val line = restored.lines.first { it.key == "gate|SIE1000" }
        assertTrue(line.rateEdited)
        assertEquals(999.0, line.rate!!, 0.0)
    }

    @Test
    fun `separators inside a product name survive`() {
        val awkward = QuoteDraft(
            lines = listOf(
                DraftLine(
                    key = "gate|ODD",
                    title = "SIE1000\\X",
                    spec = "back\\slash",
                    quantity = 1.0,
                    rate = 10.0,
                )
            )
        )
        val restored = QuoteDraftCodec.decode(QuoteDraftCodec.encode(awkward))
        assertEquals("SIE1000\\X", restored.lines.single().title)
        assertEquals("back\\slash", restored.lines.single().spec)
    }

    @Test
    fun `an empty or unreadable store decodes to an empty draft`() {
        assertEquals(QuoteDraft(), QuoteDraftCodec.decode(null))
        assertEquals(QuoteDraft(), QuoteDraftCodec.decode(""))
        assertEquals(QuoteDraft(), QuoteDraftCodec.decode("rubbish"))
        assertEquals(QuoteDraft(), QuoteDraftCodec.decode("v9client"))
    }

    @Test
    fun `a truncated or zero-quantity line is dropped rather than restored wrong`() {
        val encoded = QuoteDraftCodec.encode(draft)
        val broken = encoded.substringBeforeLast('') + "gate|XX"
        val restored = QuoteDraftCodec.decode(broken)
        assertEquals(1, restored.lineCount)
        assertEquals("gate|SIE1000", restored.lines.single().key)
    }

    @Test
    fun `an empty draft keeps its tier`() {
        val empty = QuoteDraft(tier = RateTierV2.DEALER)
        assertEquals(empty, QuoteDraftCodec.decode(QuoteDraftCodec.encode(empty)))
    }
}
