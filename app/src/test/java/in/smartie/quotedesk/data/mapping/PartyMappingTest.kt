package `in`.smartie.quotedesk.data.mapping

import `in`.smartie.quotedesk.domain.Parties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading a party, and in particular reading **archived**.
 *
 * Whether a customer is archived decides which of two lists they appear in,
 * and the field has been written four different ways across the PWA, the
 * native beta and whatever a hand-edit left behind: the number `1`, the
 * string `"1"`, the boolean `true`, and the word `"yes"`. All four mean
 * archived. A missing field, `0` and `false` all mean in use.
 *
 * `asBoolOrNull` already coerces every one of them correctly, so nothing in
 * `Parties` re-derives it. This file is here so that cannot quietly change:
 * a reader that started answering `false` for `"yes"` would not fail anything
 * else, and the only symptom would be an archived customer reappearing in
 * somebody's working list.
 */
class PartyMappingTest {

    private fun party(vararg fields: Pair<String, Any?>) =
        DocData("c_1", mapOf("id" to "c_1", "name" to "Sunrise Constructions", *fields))
            .toPartyRecord()

    @Test
    fun `every way of writing archived is read as archived`() {
        listOf<Any>(1, 1L, 1.0, "1", true, "true", "yes", "YES", "Y", "on").forEach { written ->
            assertTrue(
                "archived written as ${written::class.simpleName} $written",
                party("archived" to written).archived
            )
        }
    }

    @Test
    fun `and every way of saying it is in use is read as in use`() {
        listOf<Any?>(0, 0L, 0.0, "0", false, "false", "no", "NO", "n", "off", "").forEach { written ->
            assertFalse(
                "in use written as $written",
                party("archived" to written).archived
            )
        }
    }

    @Test
    fun `a party with no archived field at all is in use`() {
        // Every PWA-written party predates the field. None of them is
        // archived, and a default of `true` would empty the working list.
        assertFalse(party().archived)
        assertFalse(party("archived" to null).archived)
    }

    @Test
    fun `a value nobody can read is treated as in use, not hidden`() {
        // A row nobody can interpret is better shown with the working list
        // than silently filed away where nobody looks for it.
        assertFalse(party("archived" to "perhaps").archived)
        assertFalse(party("archived" to listOf(1)).archived)
    }

    // --- the beta's company-vs-name damage ------------------------------------

    @Test
    fun `the beta's company becomes the party name and its name becomes the contact`() {
        val record = DocData(
            "c_beta",
            mapOf("id" to "c_beta", "company" to "Harbour Interiors", "name" to "Mrs Pinto")
        ).toPartyRecord()

        assertEquals("Harbour Interiors", record.name)
        assertEquals("Mrs Pinto", record.contact)
        // Untangled, so it is not flagged as damaged.
        assertFalse(Parties.missingFirmName(record))
    }

    @Test
    fun `a beta row that never filled in company is indistinguishable, and is not guessed at`() {
        // The firm was never recorded, so this reads as a party called "Mrs
        // Pinto" — which is exactly how a sole trader legitimately recorded
        // under their own name reads. The app cannot tell the two apart and
        // does not try: flagging on a name that merely looks like a person's
        // would put a red tag on real customers.
        val record = DocData("c_beta2", mapOf("id" to "c_beta2", "name" to "Mrs Pinto"))
            .toPartyRecord()

        assertEquals("Mrs Pinto", record.name)
        assertEquals("", record.contact)
        assertFalse(Parties.missingFirmName(record))
    }
}
