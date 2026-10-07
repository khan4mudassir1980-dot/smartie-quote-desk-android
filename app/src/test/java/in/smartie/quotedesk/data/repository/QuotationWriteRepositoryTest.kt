package `in`.smartie.quotedesk.data.repository

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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Date

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

    private val manager = Member(uid = "u_m", name = "Manager Person", role = Role.STAFF)
    private val staff = Member(uid = "u_w", name = "Staff Person", role = Role.WORKER)

    private val counter = mapOf<String, Any?>("prefix" to "SIE/QD", "fy" to "2025-26", "next" to 9, "pad" to 3)

    private val draft = QuoteDraft(
        id = "qd_1",
        party = QuotationPartySnapshot(name = "Walk-in Builders"),
        gstPercent = 18.0
    ).addManual(id = "ln_1", title = "Site visit", rate = 1_000.0)

    private var clockReads = 0
    private val pauses = mutableListOf<Long>()
    private fun repository(store: FakeQuotationStore, jitter: Double = 0.5) = QuotationWriteRepository(
        store,
        now = {
            clockReads++
            1_760_000_000_000L
        },
        pause = { pauses += it },
        jitter = { jitter }
    )

    private fun seeded(vararg extra: Pair<String, Map<String, Any?>>) =
        mutableMapOf(NUMBERING to counter, *extra)

    private fun FinaliseOutcome.summary(): String = when (this) {
        is FinaliseOutcome.Issued -> "Issued $quotationId $number"
        is FinaliseOutcome.AlreadyIssued -> "AlreadyIssued $quotationId $number"
        is FinaliseOutcome.Refused -> "Refused $message"
    }

    private fun FakeQuotationStore.doc(path: String): Map<String, Any?> = docs.getValue(path)

    // --- issuing ------------------------------------------------------------------------

    @Test
    fun `a first finalise writes the quotation and moves the counter on by one`() = runBlocking {
        val store = FakeQuotationStore(seeded())
        val outcome = repository(store).finalise(manager, draft, customers = emptyList(), snap = emptyMap())

        assertEquals("Issued qd_1 SIE/QD/2025-26/009", outcome.summary())
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
    fun `a finalise stores serverAt from the server beside the device's at, and the issue date is the server's`() =
        runBlocking {
            // N5.11, the Owner's decision 1.1: the issue time from the server,
            // as V8C4's finalise writes it (fact e). The fake stores the
            // server's time, as Firestore does, far from the phone's `at`.
            val store = FakeQuotationStore(seeded())
            val repository = repository(store)
            repository.finalise(manager, draft, customers = emptyList(), snap = null)

            val stored = store.doc("quotations/qd_1")
            assertEquals(Date(SERVER_TIME), stored["serverAt"])
            assertNotEquals(SERVER_TIME, stored["at"])
            // Read back — as the gate's answer to a second press is — the
            // printed date is the server's.
            val again = repository.finalise(manager, draft, customers = emptyList(), snap = null)
            assertEquals(SERVER_TIME, (again as FinaliseOutcome.AlreadyIssued).record.issuedAt)
        }

    @Test
    fun `a fresh finalise's own record holds no server time, so the PDF reads the quotation back from the server`() =
        runBlocking {
            // N5.11 commit 9: the first PDF after Finalise must print the
            // server's issue date, and only a read from the server has it.
            val store = FakeQuotationStore(seeded())
            val repository = repository(store)
            val issued = repository.finalise(manager, draft, customers = emptyList(), snap = null)
                as FinaliseOutcome.Issued
            assertEquals("the placeholder, not a time", 0L, issued.record.serverAt)
            val writesBefore = store.docs.toMap()

            val stored = repository.stored(issued.quotationId)!!

            assertEquals(SERVER_TIME, stored.serverAt)
            assertEquals(SERVER_TIME, stored.issuedAt)
            assertEquals(issued.number, stored.number)
            assertEquals("a read writes nothing", writesBefore, store.docs.toMap())
            assertEquals(null, repository.stored("qd_none"))
        }

    @Test
    fun `until N6 the caller passes no snap, and the stored quotation has no snap key`() = runBlocking {
        val store = FakeQuotationStore(seeded())
        repository(store).finalise(manager, draft, customers = emptyList(), snap = null)

        assertFalse(store.doc("quotations/qd_1").containsKey("snap"))
    }

    @Test
    fun `a second call for the same draft returns the same number and writes nothing`() = runBlocking {
        val store = FakeQuotationStore(seeded())
        val repository = repository(store)

        repository.finalise(manager, draft, customers = emptyList(), snap = emptyMap())
        val again = repository.finalise(manager, draft, customers = emptyList(), snap = emptyMap())

        assertEquals("AlreadyIssued qd_1 SIE/QD/2025-26/009", again.summary())
        assertEquals(1, store.quotations().size)
        assertEquals(10, store.doc(NUMBERING)["next"])
    }

    @Test
    fun `both outcomes hand back the whole record, as V8C4's reused path hands back doc`() = runBlocking {
        // V8C4 (5111-5157) answers with the stored number, the whole record and
        // a reused flag, and its caller takes the record: the stored quotation,
        // not a number.
        val store = FakeQuotationStore(seeded())
        val repository = repository(store)

        val issued = repository.finalise(manager, draft, customers = emptyList(), snap = emptyMap())
        val reused = repository.finalise(manager, draft, customers = emptyList(), snap = emptyMap())

        for (record in listOf(
            (issued as FinaliseOutcome.Issued).record,
            (reused as FinaliseOutcome.AlreadyIssued).record
        )) {
            assertEquals("SIE/QD/2025-26/009", record.number)
            assertEquals("Walk-in Builders", record.party.name)
            assertEquals(listOf("Site visit"), record.lines.map { it.title })
            assertEquals(1_180.0, record.total, 0.0)
            assertEquals(manager.uid, record.byUid)
        }
    }

    @Test
    fun `a lost response is answered with the stored number, never a second one`() = runBlocking {
        // The N4.4 B2 shape at the most expensive place it can appear: the
        // commit landed, the acknowledgement did not, and the person tries
        // again. Were the read-first removed — the plan built as though no
        // quotation existed — the second call would take number 010 for the
        // same job, and this test would fail on both assertions below.
        val store = FakeQuotationStore(seeded())
        val repository = repository(store)
        store.loseNextResponse = true

        try {
            repository.finalise(manager, draft, customers = emptyList(), snap = emptyMap())
            fail("the lost response should have surfaced")
        } catch (expected: IllegalStateException) {
            // What the person sees: something went wrong. The quotation exists.
        }
        val retry = repository.finalise(manager, draft, customers = emptyList(), snap = emptyMap())

        assertEquals("AlreadyIssued qd_1 SIE/QD/2025-26/009", retry.summary())
        assertEquals(10, store.doc(NUMBERING)["next"])
    }

    @Test
    fun `an issued quotation is answered without reading the counter`() = runBlocking {
        val store = FakeQuotationStore(seeded())
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
        val store = FakeQuotationStore(docs, attempts = 2) { run ->
            if (run == 1) docs[NUMBERING] = counter + ("next" to 12)
        }

        val outcome = repository(store).finalise(manager, draft, customers = emptyList(), snap = emptyMap())

        assertEquals("Issued qd_1 SIE/QD/2025-26/012", outcome.summary())
        assertEquals(13, store.doc(NUMBERING)["next"])
        assertEquals("SIE/QD/2025-26/012", store.doc("quotations/qd_1")["no"])
        // One clock read for two runs: the document is the same either way.
        assertEquals(1, clockReads)
        assertEquals(2, store.bodyRuns)
    }

    // --- the bounded retry -----------------------------------------------------------------

    @Test
    fun `a refusal is retried, and the retry takes the number that is free now`() = runBlocking {
        // Somebody else took 009 while this attempt was in flight; the rules
        // refused ours. The retry re-reads and issues 010.
        val docs = seeded()
        val store = FakeQuotationStore(docs)
        store.refuseNext = 1
        store.onRefuse = { docs[NUMBERING] = counter + ("next" to 10) }

        val outcome = repository(store).finalise(manager, draft, customers = emptyList(), snap = emptyMap())

        assertEquals("Issued qd_1 SIE/QD/2025-26/010", outcome.summary())
        assertEquals(2, store.transactions)
        assertEquals(11, store.doc(NUMBERING)["next"])
        assertEquals(1, pauses.size)
    }

    @Test
    fun `refused at every attempt, it stops at the bound and says so`() = runBlocking {
        val store = FakeQuotationStore(seeded())
        store.refuseNext = 99

        val outcome = repository(store).finalise(manager, draft, customers = emptyList(), snap = emptyMap())

        assertTrue(outcome is FinaliseOutcome.Refused)
        assertEquals(QuotationWriteRepository.COUNTER_REFUSED, (outcome as FinaliseOutcome.Refused).message)
        assertTrue(outcome.cause is Refusal)
        assertEquals(QuotationWriteRepository.MAX_ATTEMPTS, store.transactions)
        // A pause between attempts, none after the last.
        assertEquals(QuotationWriteRepository.MAX_ATTEMPTS - 1, pauses.size)
        assertTrue(store.quotations().isEmpty())
        assertEquals(9, store.doc(NUMBERING)["next"])
    }

    @Test
    fun `any other failure is not retried`() = runBlocking {
        // A lost response, an offline device, a quota: none of them is the
        // race for the counter, and a retry would only hide it.
        val store = FakeQuotationStore(seeded())
        store.loseNextResponse = true
        try {
            repository(store).finalise(manager, draft, customers = emptyList(), snap = emptyMap())
            fail("a failure that is not a refusal must surface")
        } catch (expected: IllegalStateException) {
        }
        assertEquals(1, store.transactions)
        assertTrue(pauses.isEmpty())
    }

    @Test
    fun `a retry after a refusal still leads with the read-first`() = runBlocking {
        // Whatever the first attempt did, the second cannot issue a second
        // number for this draft: here the quotation turns up between them.
        val docs = seeded()
        val store = FakeQuotationStore(docs)
        store.refuseNext = 1
        store.onRefuse = {
            docs["quotations/qd_1"] = mapOf("id" to "qd_1", "no" to "SIE/QD/2025-26/009", "byUid" to manager.uid)
        }

        val outcome = repository(store).finalise(manager, draft, customers = emptyList(), snap = emptyMap())

        assertEquals("AlreadyIssued qd_1 SIE/QD/2025-26/009", outcome.summary())
        assertEquals(9, store.doc(NUMBERING)["next"])
    }

    @Test
    fun `the pause grows with each attempt and carries jitter`() {
        assertEquals(150L, QuotationWriteRepository.backoffFor(1, 0.0))
        assertEquals(300L, QuotationWriteRepository.backoffFor(1, 1.0))
        assertEquals(300L, QuotationWriteRepository.backoffFor(2, 0.0))
        assertEquals(375L, QuotationWriteRepository.backoffFor(2, 0.5))
    }

    // --- refusing -----------------------------------------------------------------------

    @Test
    fun `a refusal writes nothing`() = runBlocking {
        val store = FakeQuotationStore()
        val outcome = repository(store).finalise(manager, draft, customers = emptyList(), snap = emptyMap())

        assertEquals(FinaliseOutcome.Refused(QuotationWrite.NUMBERING_NOT_SET), outcome)
        assertTrue(store.docs.isEmpty())
    }

    @Test
    fun `Staff and a draft with no identity are refused before any transaction opens`() = runBlocking {
        // Staff cannot read /quotations, so the first read would be denied —
        // and a denial is what the retry must read as contention.
        val store = FakeQuotationStore(seeded())
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

            val lowered = FakeQuotationStore(seeded("teamSettings/quoting" to mapOf("managerDiscountPct" to 2)))
            assertEquals(
                // 2% of 1,000 is 20; 5% asks for 50.
                FinaliseOutcome.Refused(QuoteMath.overTheCap(2.0, 20.0)),
                repository(lowered).finalise(manager, discounted, customers = emptyList(), snap = emptyMap())
            )
            assertTrue(lowered.quotations().isEmpty())

            val unset = FakeQuotationStore(seeded())
            assertEquals(
                FinaliseOutcome.Refused(QuoteDiscount.CAP_NOT_SET),
                repository(unset).finalise(manager, discounted, customers = emptyList(), snap = emptyMap())
            )
        }

    @Test
    fun `an unconfigured cap is refused locally in ONE transaction - never retried as contention`() =
        runBlocking {
            // The 9b plan's amendment A, through the repository directly and
            // bypassing any gate. The unconfigured-cap check moved into
            // `QuoteDraft.refusal` in N5.9b; `plan` calls it with the cap the
            // transaction has just read. Were it lost, the discount would reach
            // the rules, whose PERMISSION_DENIED the retry reads as contention:
            // six attempts, about three seconds, and a reason about the counter.
            val store = FakeQuotationStore(seeded()).apply {
                // The deployed rule, modelled: with no /teamSettings/quoting a
                // Manager's discount worth more than zero is refused at commit
                // (`discountOk()`, N5.9a commit 2, `ccc08c4`).
                //
                // **The rule itself is proved by the emulator**, not by this:
                // `firestore/tests/quotation.test.js`, "with no quoting
                // document at all, a Manager gets no discount" — a Manager's
                // create with `disc.amt` 1700 and no quoting document fails,
                // and the same quotation without a discount succeeds. This
                // fake only models what that test proves (rule 6), and
                // models it for a Manager, the only author this test uses;
                // the rule's exemption for an Owner or Administrator is not
                // modelled here.
                refuseCommitWhen = { staged ->
                    "teamSettings/quoting" !in docs && staged.values.any { doc ->
                        val amount = (doc["disc"] as? Map<*, *>)?.get("amt")
                        amount is Number && amount.toDouble() > 0.0
                    }
                }
            }
            val discounted = draft.copy(discount = Discount(DiscountKind.PERCENT, 5.0))

            val outcome = repository(store).finalise(manager, discounted, customers = emptyList(), snap = null)

            assertEquals(FinaliseOutcome.Refused(QuoteDiscount.CAP_NOT_SET), outcome)
            // The attempt count is the point.
            assertEquals(1, store.transactions)
            assertTrue(pauses.isEmpty())
            // And the cap it refused on was read inside the transaction.
            assertTrue(store.reads.contains("teamSettings/quoting"))
            assertTrue(store.quotations().isEmpty())
        }

    // --- who it is for ------------------------------------------------------------------

    @Test
    fun `the party link comes from the screen's customers, and the transaction reads only its own two documents`() =
        runBlocking {
            // As V8C4: its transaction reads the quotation and the counter and
            // nothing else; the link is derived from its in-memory customer list (6379).
            val sunrise = PartyRecord(id = "c_1", name = "Sunrise Constructions")
            val picked = draft.copy(
                partyId = "c_1",
                party = QuotationPartySnapshot(name = "Sunrise Constructions", site = "Plot 7")
            )
            val store = FakeQuotationStore(seeded())

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
        val store = FakeQuotationStore(seeded())

        val outcome = repository(store).finalise(manager, picked, customers = emptyList(), snap = emptyMap())

        assertEquals("Issued qd_1 SIE/QD/2025-26/009", outcome.summary())
        assertFalse(store.doc("quotations/qd_1").containsKey("partyId"))
    }
}
