package `in`.smartie.quotedesk.data.repository

import `in`.smartie.quotedesk.data.mapping.DocData
import `in`.smartie.quotedesk.data.model.PartyRecord
import `in`.smartie.quotedesk.data.model.QuotationPartySnapshot
import `in`.smartie.quotedesk.domain.Discount
import `in`.smartie.quotedesk.domain.DiscountKind
import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.domain.QuotationWrite
import `in`.smartie.quotedesk.domain.QuoteDiscount
import `in`.smartie.quotedesk.domain.QuoteDraft
import `in`.smartie.quotedesk.domain.QuoteMath
import `in`.smartie.quotedesk.domain.Role
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The finalise transaction, against a store that behaves as Firestore does
 * where it matters: reads see committed documents, a replayed body's writes
 * are discarded, and a commit can land while its acknowledgement is lost.
 *
 * **Uses `runBlocking`, not `runTest`,** so this class also runs in the local
 * JVM sweep, whose classpath has coroutines-core and not the test artefact.
 *
 * Stored `staff` is displayed **Manager** and stored `worker` **Staff**.
 */
class QuotationWriteRepositoryTest {

    /** Documents by path, e.g. `quotations/qd_1` or `teamSettings/numbering`. */
    private class FakeStore(
        val docs: MutableMap<String, Map<String, Any?>> = mutableMapOf(),
        /** How many times Firestore runs the body before committing. */
        private val attempts: Int = 1,
        /** Called before each run, with its index — to change a document between runs. */
        private val beforeRun: (Int) -> Unit = {}
    ) : QuotationStore {
        var transactions = 0
        var bodyRuns = 0
        val reads = mutableListOf<String>()

        /** Commit the next transaction, then throw as though its response was lost. */
        var loseNextResponse = false

        override suspend fun <T> transaction(body: (QuotationTransaction) -> T): T {
            transactions++
            var result: T? = null
            val staged = mutableListOf<Pair<String, Map<String, Any?>>>()
            repeat(attempts) { run ->
                beforeRun(run)
                bodyRuns++
                // Firestore discards what an abandoned run recorded.
                staged.clear()
                result = body(object : QuotationTransaction {
                    override fun readQuotation(id: String) = read("quotations/$id")
                    override fun readNumbering() = read(NUMBERING)
                    override fun readQuoting() = read("teamSettings/quoting")

                    override fun writeQuotation(id: String, data: Map<String, Any?>) {
                        staged += "quotations/$id" to data
                    }

                    override fun writeNumbering(fields: Map<String, Any?>) {
                        // An update: named fields replaced whole, the rest kept.
                        staged += NUMBERING to (docs.getValue(NUMBERING) + fields)
                    }
                })
            }
            staged.forEach { (path, data) -> docs[path] = data }
            if (loseNextResponse) {
                loseNextResponse = false
                throw IllegalStateException("the commit landed; its acknowledgement did not")
            }
            @Suppress("UNCHECKED_CAST")
            return result as T
        }

        private fun read(path: String): DocData? {
            reads += path
            return docs[path]?.let { DocData(path.substringAfterLast('/'), it) }
        }

        fun quotations(): List<String> = docs.keys.filter { it.startsWith("quotations/") }
    }

    private val manager = Member(uid = "u_m", name = "Manager Person", role = Role.STAFF)
    private val staff = Member(uid = "u_w", name = "Staff Person", role = Role.WORKER)

    private val counter = mapOf<String, Any?>("prefix" to "SIE/QD", "fy" to "2025-26", "next" to 9, "pad" to 3)

    private val draft = QuoteDraft(
        id = "qd_1",
        party = QuotationPartySnapshot(name = "Walk-in Builders"),
        gstPercent = 18.0
    ).addManual(id = "ln_1", title = "Site visit", rate = 1_000.0)

    private var clockReads = 0
    private fun repository(store: FakeStore) = QuotationWriteRepository(store) {
        clockReads++
        1_760_000_000_000L
    }

    private fun seeded(vararg extra: Pair<String, Map<String, Any?>>) =
        mutableMapOf(NUMBERING to counter, *extra)

    private fun FakeStore.doc(path: String): Map<String, Any?> = docs.getValue(path)

    // --- issuing ------------------------------------------------------------------------

    @Test
    fun `a first finalise writes the quotation and moves the counter on by one`() = runBlocking {
        val store = FakeStore(seeded())
        val outcome = repository(store).finalise(manager, draft, customers = emptyList(), snap = emptyMap())

        assertEquals(FinaliseOutcome.Issued("qd_1", "SIE/QD/2025-26/009"), outcome)
        assertEquals(listOf("quotations/qd_1"), store.quotations())
        assertEquals("SIE/QD/2025-26/009", store.doc("quotations/qd_1")["no"])
        assertEquals(10, store.doc(NUMBERING)["next"])
        @Suppress("UNCHECKED_CAST")
        val lastIssued = store.doc(NUMBERING)["lastIssued"] as Map<String, Any?>
        assertEquals("android", lastIssued["src"])
        // The counter's configuration is untouched, as the issue rule requires.
        assertEquals("SIE/QD", store.doc(NUMBERING)["prefix"])
    }

    @Test
    fun `a second call for the same draft returns the same number and writes nothing`() = runBlocking {
        val store = FakeStore(seeded())
        val repository = repository(store)

        repository.finalise(manager, draft, customers = emptyList(), snap = emptyMap())
        val again = repository.finalise(manager, draft, customers = emptyList(), snap = emptyMap())

        assertEquals(FinaliseOutcome.AlreadyIssued("qd_1", "SIE/QD/2025-26/009"), again)
        assertEquals(1, store.quotations().size)
        assertEquals(10, store.doc(NUMBERING)["next"])
    }

    @Test
    fun `a lost response is answered with the stored number, never a second one`() = runBlocking {
        // The N4.4 B2 shape at the most expensive place it can appear: the
        // commit landed, the acknowledgement did not, and the person tries
        // again. Were the read-first removed — the plan built as though no
        // quotation existed — the second call would take number 010 for the
        // same job, and this test would fail on both assertions below.
        val store = FakeStore(seeded())
        val repository = repository(store)
        store.loseNextResponse = true

        try {
            repository.finalise(manager, draft, customers = emptyList(), snap = emptyMap())
            fail("the lost response should have surfaced")
        } catch (expected: IllegalStateException) {
            // What the person sees: something went wrong. The quotation exists.
        }
        val retry = repository.finalise(manager, draft, customers = emptyList(), snap = emptyMap())

        assertEquals(FinaliseOutcome.AlreadyIssued("qd_1", "SIE/QD/2025-26/009"), retry)
        assertEquals(10, store.doc(NUMBERING)["next"])
    }

    @Test
    fun `an issued quotation is answered without reading the counter`() = runBlocking {
        val store = FakeStore(seeded())
        val repository = repository(store)
        repository.finalise(manager, draft, customers = emptyList(), snap = emptyMap())
        store.reads.clear()

        repository.finalise(manager, draft, customers = emptyList(), snap = emptyMap())

        // Not in the read set, so a busy counter cannot make Firestore re-run
        // a body that is only fetching a number it already has.
        assertEquals(listOf("quotations/qd_1"), store.reads)
    }

    @Test
    fun `when Firestore re-runs the body, the plan is rebuilt from the fresh reads`() = runBlocking {
        // Somebody else issued 009, 010 and 011 between the two runs.
        val docs = seeded()
        val store = FakeStore(docs, attempts = 2) { run ->
            if (run == 1) docs[NUMBERING] = counter + ("next" to 12)
        }

        val outcome = repository(store).finalise(manager, draft, customers = emptyList(), snap = emptyMap())

        assertEquals(FinaliseOutcome.Issued("qd_1", "SIE/QD/2025-26/012"), outcome)
        assertEquals(13, store.doc(NUMBERING)["next"])
        assertEquals("SIE/QD/2025-26/012", store.doc("quotations/qd_1")["no"])
        // One clock read for two runs: the document is the same either way.
        assertEquals(1, clockReads)
        assertEquals(2, store.bodyRuns)
    }

    // --- refusing -----------------------------------------------------------------------

    @Test
    fun `a refusal writes nothing`() = runBlocking {
        val store = FakeStore()
        val outcome = repository(store).finalise(manager, draft, customers = emptyList(), snap = emptyMap())

        assertEquals(FinaliseOutcome.Refused(QuotationWrite.NUMBERING_NOT_SET), outcome)
        assertTrue(store.docs.isEmpty())
    }

    @Test
    fun `Staff and a draft with no identity are refused before any transaction opens`() = runBlocking {
        // Staff cannot read /quotations, so the first read would be denied —
        // and a denial is what the retry must read as contention.
        val store = FakeStore(seeded())
        val repository = repository(store)

        assertEquals(
            FinaliseOutcome.Refused(QuotationWrite.NOT_ALLOWED),
            repository.finalise(staff, draft, customers = emptyList(), snap = emptyMap())
        )
        assertEquals(
            FinaliseOutcome.Refused(QuotationWrite.NO_IDENTITY),
            repository.finalise(manager, draft.copy(id = ""), customers = emptyList(), snap = emptyMap())
        )
        assertEquals(0, store.transactions)
    }

    @Test
    fun `the cap is read inside the transaction, so a limit lowered since the screen loaded refuses locally`() =
        runBlocking {
            val discounted = draft.copy(discount = Discount(DiscountKind.PERCENT, 5.0))

            val lowered = FakeStore(seeded("teamSettings/quoting" to mapOf("managerDiscountPct" to 2)))
            assertEquals(
                // 2% of 1,000 is 20; 5% asks for 50.
                FinaliseOutcome.Refused(QuoteMath.overTheCap(2.0, 20.0)),
                repository(lowered).finalise(manager, discounted, customers = emptyList(), snap = emptyMap())
            )
            assertTrue(lowered.quotations().isEmpty())

            val unset = FakeStore(seeded())
            assertEquals(
                FinaliseOutcome.Refused(QuoteDiscount.CAP_NOT_SET),
                repository(unset).finalise(manager, discounted, customers = emptyList(), snap = emptyMap())
            )
        }

    // --- who it is for ------------------------------------------------------------------

    @Test
    fun `the party link comes from the screen's customers, and the transaction reads only its own two documents`() =
        runBlocking {
            // As V8C4: its transaction reads the quotation and the counter and
            // nothing else; the link is derived from `state.customers`.
            val sunrise = PartyRecord(id = "c_1", name = "Sunrise Constructions")
            val picked = draft.copy(
                partyId = "c_1",
                party = QuotationPartySnapshot(name = "Sunrise Constructions", site = "Plot 7")
            )
            val store = FakeStore(seeded())

            repository(store).finalise(manager, picked, customers = listOf(sunrise), snap = emptyMap())

            assertEquals(listOf("quotations/qd_1", NUMBERING), store.reads)
            assertEquals("c_1", store.doc("quotations/qd_1")["partyId"])
            @Suppress("UNCHECKED_CAST")
            val party = store.doc("quotations/qd_1")["party"] as Map<String, Any?>
            assertEquals("Plot 7", party["site"])
        }

    @Test
    fun `a saved customer missing from the list costs the link, never the quotation`() = runBlocking {
        val picked = draft.copy(partyId = "c_gone", party = QuotationPartySnapshot(name = "Sunrise Constructions"))
        val store = FakeStore(seeded())

        val outcome = repository(store).finalise(manager, picked, customers = emptyList(), snap = emptyMap())

        assertEquals(FinaliseOutcome.Issued("qd_1", "SIE/QD/2025-26/009"), outcome)
        assertFalse(store.doc("quotations/qd_1").containsKey("partyId"))
    }

    private companion object {
        const val NUMBERING = "teamSettings/numbering"
    }
}
