package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.model.NumberingRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The quotation counter, and what the Settings screen may do to it.
 *
 * **The number this formats is the number that goes on a customer's
 * document.** A prefix, a financial year, a count and a padding are four
 * fields nobody assembles in their head into `SIE/QD/2026-27/010`, and an
 * error in any of them is invisible until a quotation has already gone out
 * under the wrong reference — so the screen previews the real thing, from
 * this function.
 *
 * **Every refusal here is also a rule.** The deployed configuration branch
 * requires a strictly greater `next` *where `next` is being changed at all*,
 * unless the financial year changes — so the server refuses a lower one
 * whatever this file does. It is decided twice on purpose: once in the
 * person's own words, and once where nobody can route around it.
 */
class NumberingTest {

    private val stored = NumberingRecord(
        prefix = "SIE/QD", financialYear = "2025-26", next = 9, pad = 3
    )

    private fun draft(
        prefix: String = "SIE/QD",
        financialYear: String = "2025-26",
        next: Int = 9,
        pad: Int = 3
    ) = NumberingDraft(prefix, financialYear, next, pad)

    private val owner = NumberingAuthor(name = "Mudassir", uid = "uid_owner")

    private fun written(plan: NumberingPlan): Map<String, Any?> =
        (plan as NumberingPlan.Write).data

    // --- the preview ---------------------------------------------------------------

    @Test
    fun `the next number reads exactly as it will be issued`() {
        assertEquals("SIE/QD/2026-27/010", Numbering.format("SIE/QD", "2026-27", 10, 3))
        assertEquals("SIE/QD/2025-26/009", Numbering.format(stored))
    }

    @Test
    fun `padding widens a small number and never truncates a large one`() {
        // A counter that outgrows its padding must keep counting rather than
        // start reusing numbers, so the padding is a minimum width.
        assertEquals("SIE/QD/2025-26/0009", Numbering.format("SIE/QD", "2025-26", 9, 4))
        assertEquals("SIE/QD/2025-26/1234", Numbering.format("SIE/QD", "2025-26", 1234, 3))
        assertEquals("SIE/QD/2025-26/9", Numbering.format("SIE/QD", "2025-26", 9, 1))
    }

    @Test
    fun `a prefix or year not yet typed does not leave an empty slash in the number`() {
        // The preview is live while somebody is still typing, so it must not
        // show `SIE/QD//010` and teach them that is what will be issued.
        assertEquals("2025-26/009", Numbering.format("", "2025-26", 9, 3))
        assertEquals("SIE/QD/009", Numbering.format("SIE/QD", "", 9, 3))
        assertEquals("009", Numbering.format("  ", "  ", 9, 3))
    }

    @Test
    fun `the padding is clamped to something a person could read`() {
        assertEquals("SIE/QD/2025-26/9", Numbering.format("SIE/QD", "2025-26", 9, 0))
        assertEquals(
            "SIE/QD/2025-26/000000009",
            Numbering.format("SIE/QD", "2025-26", 9, 50)
        )
    }

    // --- what may be saved -----------------------------------------------------------

    @Test
    fun `a next below the stored one is refused inside a financial year`() {
        // `next` is the number the *next* quotation will carry, so 9 has not
        // gone out yet and 8 has. Setting the counter to 8 would issue a
        // number a customer is already holding.
        assertNotNull(Numbering.refusal(stored, draft(next = 8)))
        assertNotNull(Numbering.refusal(stored, draft(next = 1)))
        assertNull(Numbering.refusal(stored, draft(next = 10)))
    }

    @Test
    fun `but leaving it exactly where it is changes nothing, and is allowed`() {
        // Not a nicety. Refusing equality here would mean an Owner correcting
        // a prefix or a padding had to burn a quotation number to do it —
        // which is the defect the deployed rule had for one commit, found by
        // the screen test rather than by reading the rule.
        assertNull(Numbering.refusal(stored, draft(next = 9)))
        assertNull(Numbering.refusal(stored, draft(prefix = "SIE/QT", next = 9)))
        assertNull(Numbering.refusal(stored, draft(pad = 4, next = 9)))
    }

    @Test
    fun `and the refusal says which number to type instead`() {
        val message = Numbering.refusal(stored, draft(next = 8)).orEmpty()

        assertTrue("names the number not yet issued", message.contains("SIE/QD/2025-26/009"))
        assertTrue("names the lowest one allowed", message.contains("type 9 or more"))
        assertTrue("and the way out", message.contains("financial year"))
    }

    @Test
    fun `rolling the financial year lifts the guard, because the sequence restarts`() {
        assertNull(Numbering.refusal(stored, draft(financialYear = "2026-27", next = 1)))
        assertTrue(Numbering.rollsTheYear(stored, draft(financialYear = "2026-27")))
        assertTrue("and whitespace is not a new year", !Numbering.rollsTheYear(stored, draft(financialYear = " 2025-26 ")))
    }

    @Test
    fun `the fields nobody can leave empty`() {
        assertEquals(Numbering.PREFIX_REQUIRED, Numbering.refusal(stored, draft(prefix = "  ", next = 10)))
        assertEquals(Numbering.YEAR_REQUIRED, Numbering.refusal(stored, draft(financialYear = "", next = 10)))
        assertEquals(Numbering.NEXT_TOO_SMALL, Numbering.refusal(stored, draft(next = 0)))
        assertEquals(Numbering.PAD_OUT_OF_RANGE, Numbering.refusal(stored, draft(next = 10, pad = 0)))
        assertEquals(Numbering.PAD_OUT_OF_RANGE, Numbering.refusal(stored, draft(next = 10, pad = 10)))
    }

    // --- the confirmation --------------------------------------------------------------

    @Test
    fun `skipping numbers is confirmed, and the confirmation names the gap`() {
        val consequence = Numbering.consequenceOf(stored, draft(next = 40))

        assertNotNull(consequence)
        assertTrue(consequence!!.headline.contains("SIE/QD/2025-26/040"))
        assertTrue("says the in-between numbers are lost", consequence.detail.contains("never issued"))
        assertTrue("and that it is one way", consequence.detail.contains("cannot be undone"))
    }

    @Test
    fun `rolling the year is confirmed in its own words`() {
        val consequence = Numbering.consequenceOf(stored, draft(financialYear = "2026-27", next = 1))

        assertNotNull(consequence)
        assertTrue(consequence!!.headline.contains("financial year"))
        assertTrue("reassures that issued quotations are untouched", consequence.detail.contains("2025-26"))
        assertTrue(consequence.detail.contains("SIE/QD/2026-27/001"))
    }

    @Test
    fun `changing only the prefix or the padding needs no confirmation`() {
        // Those change how the next number *reads*, which the preview already
        // shows. A confirmation on every edit is a confirmation nobody reads.
        assertNull(Numbering.consequenceOf(stored, draft(prefix = "SIE/QT")))
        assertNull(Numbering.consequenceOf(stored, draft(pad = 4)))
        assertNull(Numbering.consequenceOf(stored, Numbering.draftOf(stored)))
    }

    // --- the write ----------------------------------------------------------------------

    @Test
    fun `the write uses V8C4's keys and never touches lastIssued`() {
        val data = written(
            Numbering.save(stored, draft(next = 12), owner, at = 5_000L, canConfigure = true)
        )

        assertEquals(
            setOf("prefix", "fy", "next", "pad", "updated", "by"),
            data.keys
        )
        assertEquals("SIE/QD", data["prefix"])
        assertEquals("2025-26", data["fy"])
        assertEquals(12, data["next"])
        assertEquals(3, data["pad"])
        assertEquals(5_000L, data["updated"])
        assertEquals("Mudassir", data["by"])
    }

    @Test
    fun `saving an unchanged counter writes nothing at all`() {
        val plan = Numbering.save(stored, Numbering.draftOf(stored), owner, 5_000L, canConfigure = true)
        assertEquals(NumberingPlan.NoChange, plan)
    }

    @Test
    fun `anyone who is not the Owner is refused before anything is sent`() {
        val plan = Numbering.save(stored, draft(next = 12), owner, 5_000L, canConfigure = false)
        assertEquals(Numbering.NOT_ALLOWED, (plan as NumberingPlan.Refused).message)
    }

    @Test
    fun `and a refused draft never becomes a write`() {
        val plan = Numbering.save(stored, draft(next = 8), owner, 5_000L, canConfigure = true)
        assertTrue(plan is NumberingPlan.Refused)
    }

    @Test
    fun `a prefix corrected on its own is a write, and leaves the counter alone`() {
        val data = written(
            Numbering.save(stored, draft(prefix = "SIE/QT"), owner, 5_000L, canConfigure = true)
        )

        assertEquals("SIE/QT", data["prefix"])
        assertEquals("the counter does not move", 9, data["next"])
    }
}

/**
 * The Manager discount cap.
 *
 * **Absent means nought, not "no limit".** The N5.9 quotation rule reads the
 * cap through a guarded `exists()` and treats a missing document as zero, so
 * an unseeded project refuses a Manager's discount rather than allowing any
 * size of one. The app has to agree, or the screen and the server disagree
 * about what a blank setting means — and the person finds out when a
 * quotation will not save.
 */
class DiscountCapTest {

    private val owner = NumberingAuthor(name = "Mudassir", uid = "uid_owner")

    @Test
    fun `nought and a hundred are both real settings`() {
        assertNull(DiscountCap.refusal(0.0))
        assertNull(DiscountCap.refusal(100.0))
        assertNull(DiscountCap.refusal(12.5))
    }

    @Test
    fun `and anything outside them is not`() {
        assertEquals(DiscountCap.OUT_OF_RANGE, DiscountCap.refusal(-0.5))
        assertEquals(DiscountCap.OUT_OF_RANGE, DiscountCap.refusal(101.0))
        assertEquals(DiscountCap.OUT_OF_RANGE, DiscountCap.refusal(Double.NaN))
    }

    @Test
    fun `an unset cap allows nothing, which is what the rules do with it`() {
        assertEquals(0.0, DiscountCap.NONE, 0.0)
        assertTrue(DiscountCap.describe(DiscountCap.NONE).contains("cannot discount"))
    }

    @Test
    fun `and the screen says what the current setting means in words`() {
        assertTrue(DiscountCap.describe(10.0).contains("10%"))
        assertTrue(DiscountCap.describe(12.5).contains("12.5%"))
        assertTrue(DiscountCap.describe(100.0).contains("without a limit"))
    }

    @Test
    fun `only the Owner writes it`() {
        val refused = DiscountCap.save(10.0, owner, 5_000L, canConfigure = false)
        assertEquals(DiscountCap.NOT_ALLOWED, (refused as NumberingPlan.Refused).message)

        val data = (DiscountCap.save(10.0, owner, 5_000L, canConfigure = true) as NumberingPlan.Write).data
        assertEquals(10.0, data["managerDiscountPct"])
        assertEquals(5_000L, data["updated"])
        assertEquals("Mudassir", data["by"])
    }

    @Test
    fun `and an out-of-range cap never becomes a write`() {
        val plan = DiscountCap.save(150.0, owner, 5_000L, canConfigure = true)
        assertEquals(DiscountCap.OUT_OF_RANGE, (plan as NumberingPlan.Refused).message)
    }
}
