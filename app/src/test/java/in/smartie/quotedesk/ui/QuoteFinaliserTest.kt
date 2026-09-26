package `in`.smartie.quotedesk.ui

import `in`.smartie.quotedesk.data.model.QuotationPartySnapshot
import `in`.smartie.quotedesk.data.model.QuotationRecord
import `in`.smartie.quotedesk.data.repository.FakeQuotationStore
import `in`.smartie.quotedesk.data.repository.FinaliseOutcome
import `in`.smartie.quotedesk.data.repository.NUMBERING
import `in`.smartie.quotedesk.data.repository.QuotationWriteRepository
import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.domain.PartyFormat
import `in`.smartie.quotedesk.domain.QuotationWrite
import `in`.smartie.quotedesk.domain.QuoteDraft
import `in`.smartie.quotedesk.domain.QuoteDrafts
import `in`.smartie.quotedesk.domain.Role
import `in`.smartie.quotedesk.ui.products.GateOutcome
import `in`.smartie.quotedesk.ui.products.GatePhase
import `in`.smartie.quotedesk.ui.products.QuoteFinaliser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The finalise gate — V8C4's `ensureFinalised` — step by step.
 *
 * Each pre-check is tested for what it says **and** for what it does not do:
 * nothing is sent. The last tests run the real `QuotationWriteRepository`
 * over the fake store, because the property the Owner asked for — the next
 * quotation is never answered with the previous number — lives in the
 * joint between the gate, the retire and the read-first.
 *
 * **Uses `runBlocking`,** so it also runs in the local JVM sweep.
 */
class QuoteFinaliserTest {

    private val manager = Member(uid = "u_m", name = "Manager Person", role = Role.STAFF)

    private val ready = QuoteDraft(
        id = "qd_1",
        party = QuotationPartySnapshot(name = "Walk-in Builders"),
        gstPercent = 18.0
    ).addManual(id = "ln_1", title = "Site visit", rate = 1_000.0)

    private fun record(number: String) = QuotationRecord(id = "qd_1", number = number)

    // What the gate touched, so every test can say what it did NOT do.
    private val sent = mutableListOf<String>()
    private val retired = mutableListOf<String>()
    private val asked = mutableListOf<String>()
    private val logged = mutableListOf<Throwable>()

    private fun gate(
        online: Boolean = true,
        answer: Boolean = true,
        capMillis: Long = QuoteFinaliser.CAP_MILLIS,
        retire: suspend (String) -> Unit = { retired += it },
        finalise: suspend (QuoteDraft) -> FinaliseOutcome = { FinaliseOutcome.Issued(record("SIE/QD/2025-26/009")) }
    ) = QuoteFinaliser(
        online = { online },
        finalise = { draft -> sent += draft.id; finalise(draft) },
        retire = retire,
        confirmZeroRates = { question -> asked += question; answer },
        describe = { it.message ?: "Something went wrong" },
        log = { logged += it },
        capMillis = capMillis
    )

    private fun nothingSent() = assertTrue("sent $sent", sent.isEmpty())

    // --- 1. refusal(), called and never rebuilt -------------------------------------------

    @Test
    fun `the draft's own refusal comes first, in its own words, and nothing is sent`() = runBlocking {
        val outcome = gate().ensureFinalised(QuoteDraft(id = "qd_1", gstPercent = 18.0), capPercent = null)

        assertEquals(GateOutcome.NotFinalised("Add a line to the quotation first"), outcome)
        nothingSent()
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `an unpriced line is refused before any question is asked`() = runBlocking {
        val outcome = gate().ensureFinalised(ready.setRate("ln_1", null), capPercent = null)

        assertEquals(GateOutcome.NotFinalised(QuoteDraft.LINE_NEEDS_RATE), outcome)
        assertTrue(asked.isEmpty())
        nothingSent()
    }

    // --- 3. the ₹0 question ----------------------------------------------------------------

    private fun withZeroLines(count: Int): QuoteDraft =
        (1..count).fold(ready) { draft, n -> draft.addManual(id = "z_$n", title = "Free $n", rate = 0.0) }

    @Test
    fun `one line priced at zero is named in our sentence, not V8C4's`() {
        assertEquals(
            "1 line is priced at ₹0:\n\n• Free 1\n\nContinue anyway?",
            QuoteFinaliser.zeroRateQuestion(withZeroLines(1))
        )
    }

    @Test
    fun `four are all named, and a fifth becomes an ellipsis`() {
        assertEquals(
            "4 lines are priced at ₹0:\n\n• Free 1\n• Free 2\n• Free 3\n• Free 4\n\nContinue anyway?",
            QuoteFinaliser.zeroRateQuestion(withZeroLines(4))
        )
        assertEquals(
            "5 lines are priced at ₹0:\n\n• Free 1\n• Free 2\n• Free 3\n• Free 4\n• …\n\nContinue anyway?",
            QuoteFinaliser.zeroRateQuestion(withZeroLines(5))
        )
    }

    @Test
    fun `no line priced at zero, no question`() = runBlocking {
        assertEquals(null, QuoteFinaliser.zeroRateQuestion(ready))
        gate().ensureFinalised(ready, capPercent = null)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `Cancel sends nothing and says nothing`() = runBlocking {
        val outcome = gate(answer = false).ensureFinalised(withZeroLines(1), capPercent = null)

        assertEquals(GateOutcome.Cancelled, outcome)
        assertEquals(1, asked.size)
        nothingSent()
    }

    @Test
    fun `Continue anyway issues the quotation`() = runBlocking {
        val outcome = gate(answer = true).ensureFinalised(withZeroLines(2), capPercent = null)

        assertEquals(GateOutcome.Finalised("SIE/QD/2025-26/009", "Finalised as SIE/QD/2025-26/009"), outcome)
        assertEquals(listOf("qd_1"), sent)
    }

    // --- 4 and 5. the client's GSTIN and phone ----------------------------------------------

    @Test
    fun `a malformed client GSTIN is refused with V8C4's prefix, and nothing is sent`() = runBlocking {
        val draft = ready.copy(party = ready.party.copy(gstin = "27ABC"))
        val outcome = gate().ensureFinalised(draft, capPercent = null)

        assertEquals(
            GateOutcome.NotFinalised("Client GSTIN: A GSTIN is 15 characters, for example 27FXJPK9635L1ZM"),
            outcome
        )
        nothingSent()
    }

    @Test
    fun `a malformed client phone is refused with V8C4's prefix, and nothing is sent`() = runBlocking {
        val draft = ready.copy(party = ready.party.copy(phone = "12345"))
        val outcome = gate().ensureFinalised(draft, capPercent = null)

        assertEquals(GateOutcome.NotFinalised("Client phone: " + PartyFormat.PHONE_TOO_SHORT), outcome)
        nothingSent()
    }

    @Test
    fun `blank GSTIN and phone are fine - they are format checks, not required fields`() = runBlocking {
        val outcome = gate().ensureFinalised(ready, capPercent = null)
        assertTrue(outcome is GateOutcome.Finalised)
    }

    @Test
    fun `the question comes before the GSTIN, as in V8C4`() = runBlocking {
        val draft = withZeroLines(1).copy(party = ready.party.copy(gstin = "27ABC"))
        val outcome = gate().ensureFinalised(draft, capPercent = null)

        assertEquals(1, asked.size)
        assertTrue(outcome is GateOutcome.NotFinalised)
        assertTrue((outcome as GateOutcome.NotFinalised).message.startsWith(QuoteFinaliser.CLIENT_GSTIN))
    }

    // --- 6. offline — a courtesy ------------------------------------------------------------

    @Test
    fun `offline is refused in V8C4's words before anything is built, and nothing is sent`() = runBlocking {
        val outcome = gate(online = false).ensureFinalised(ready, capPercent = null)

        assertEquals(
            GateOutcome.NotFinalised("Finalising needs an internet connection — the number is shared with the team"),
            outcome
        )
        nothingSent()
    }

    @Test
    fun `the format checks come before the connection, as in V8C4`() = runBlocking {
        val draft = ready.copy(party = ready.party.copy(phone = "12345"))
        val outcome = gate(online = false).ensureFinalised(draft, capPercent = null)
        assertEquals(GateOutcome.NotFinalised("Client phone: " + PartyFormat.PHONE_TOO_SHORT), outcome)
    }

    // --- 7. the finalise, and its cap -------------------------------------------------------

    @Test
    fun `a finalise that never returns ends at the cap, says press again, and the gate reopens`() =
        runBlocking {
            // Bounded by the test's own timeout, so with the cap removed this
            // fails rather than hangs.
            withTimeout(5_000) {
                val gate = gate(capMillis = 50, finalise = { awaitCancellation() })

                val outcome = gate.ensureFinalised(ready, capPercent = null)

                assertEquals(
                    GateOutcome.NotFinalised(
                        "Not finalised — The connection timed out. Please try again. Your quotation is untouched. " +
                            "Press Finalise again — if a number was taken, the same one comes back."
                    ),
                    outcome
                )
                assertTrue(retired.isEmpty())
                assertEquals(GatePhase.IDLE, gate.phase.value)
            }
        }

    @Test
    fun `a thrown failure is V8C4's catch, with the press-again line, and nothing is retired`() = runBlocking {
        val outcome = gate(finalise = { throw IllegalStateException("No connection. Check the internet and try again.") })
            .ensureFinalised(ready, capPercent = null)

        assertEquals(
            GateOutcome.NotFinalised(
                "Not finalised — No connection. Check the internet and try again. Your quotation is untouched. " +
                    QuoteFinaliser.PRESS_AGAIN
            ),
            outcome
        )
        assertTrue(retired.isEmpty())
        assertEquals(1, logged.size)
    }

    @Test
    fun `a refusal is V8C4's catch WITHOUT the press-again line - a second press cannot change it`() =
        runBlocking {
            val outcome = gate(finalise = { FinaliseOutcome.Refused(QuotationWrite.NUMBERING_NOT_SET) })
                .ensureFinalised(ready, capPercent = null)

            assertEquals(
                GateOutcome.NotFinalised(
                    "Not finalised — Quotation numbering has not been set up - the Owner sets it in Settings. " +
                        "Your quotation is untouched."
                ),
                outcome
            )
            assertTrue(retired.isEmpty())
        }

    @Test
    fun `a real cancellation is not a failure to report`() = runBlocking {
        val gate = gate(finalise = { throw CancellationException("the screen went away") })
        try {
            gate.ensureFinalised(ready, capPercent = null)
            fail("a cancellation was swallowed")
        } catch (expected: CancellationException) {
            assertEquals("the screen went away", expected.message)
        }
        assertTrue(logged.isEmpty())
        assertEquals(GatePhase.IDLE, gate.phase.value)
    }

    // --- 8. success, in V8C4's order ---------------------------------------------------------

    @Test
    fun `issued - the number in hand, then the draft retired, then Finalised as`() = runBlocking {
        val order = mutableListOf<String>()
        val gate = gate(
            finalise = { order += "finalise"; FinaliseOutcome.Issued(record("SIE/QD/2025-26/009")) },
            retire = { order += "retire $it" }
        )

        val outcome = gate.ensureFinalised(ready, capPercent = null)

        assertEquals(listOf("finalise", "retire qd_1"), order)
        assertEquals(GateOutcome.Finalised("SIE/QD/2025-26/009", "Finalised as SIE/QD/2025-26/009"), outcome)
    }

    @Test
    fun `already issued says the same, and retires the same`() = runBlocking {
        val outcome = gate(finalise = { FinaliseOutcome.AlreadyIssued(record("SIE/QD/2025-26/009")) })
            .ensureFinalised(ready, capPercent = null)

        assertEquals(GateOutcome.Finalised("SIE/QD/2025-26/009", "Finalised as SIE/QD/2025-26/009"), outcome)
        assertEquals(listOf("qd_1"), retired)
    }

    @Test
    fun `no number means the shared counter did not respond, and nothing is retired`() = runBlocking {
        val outcome = gate(finalise = { FinaliseOutcome.Issued(record("")) }).ensureFinalised(ready, capPercent = null)

        assertEquals(
            GateOutcome.NotFinalised("Not finalised — The shared counter did not respond. Your quotation is untouched."),
            outcome
        )
        assertTrue(retired.isEmpty())
    }

    @Test
    fun `a retire that fails after an issue is still a success - the next press retires it`() = runBlocking {
        val outcome = gate(retire = { throw IllegalStateException("disk full") }).ensureFinalised(ready, capPercent = null)

        assertTrue(outcome is GateOutcome.Finalised)
        assertEquals("disk full", logged.single().message)
    }

    // --- one gate at a time ----------------------------------------------------------------

    @Test
    fun `a second press while the first is taking a number does nothing`() = runBlocking {
        // Bounded: without the guard the second press would wait on the same
        // release as the first, and the test must fail rather than hang.
        withTimeout(5_000) { secondPressWhileTaking() }
    }

    private suspend fun secondPressWhileTaking() = coroutineScope {
        val release = CompletableDeferred<Unit>()
        val gate = gate(finalise = {
            release.await()
            FinaliseOutcome.Issued(record("SIE/QD/2025-26/009"))
        })

        val first = launch { gate.ensureFinalised(ready, capPercent = null) }
        repeat(5) { yield() }
        assertEquals(GatePhase.TAKING_NUMBER, gate.phase.value)

        assertEquals(GateOutcome.AlreadyRunning, gate.ensureFinalised(ready, capPercent = null))

        release.complete(Unit)
        first.join()
        assertEquals(listOf("qd_1"), sent)
        assertEquals(GatePhase.IDLE, gate.phase.value)
    }

    @Test
    fun `and while the question is open, a press does nothing either`() = runBlocking {
        withTimeout(5_000) { secondPressWhileAsking() }
    }

    private suspend fun secondPressWhileAsking() = coroutineScope {
        val answer = CompletableDeferred<Boolean>()
        val gate = QuoteFinaliser(
            online = { true },
            finalise = { sent += it.id; FinaliseOutcome.Issued(record("SIE/QD/2025-26/009")) },
            retire = { retired += it },
            confirmZeroRates = { answer.await() },
            describe = { it.message.orEmpty() }
        )

        val first = launch { gate.ensureFinalised(withZeroLines(1), capPercent = null) }
        repeat(5) { yield() }
        assertEquals(GatePhase.CHECKING, gate.phase.value)
        assertEquals(GateOutcome.AlreadyRunning, gate.ensureFinalised(withZeroLines(1), capPercent = null))

        answer.complete(false)
        first.join()
        assertTrue(sent.isEmpty())
    }

    // --- through the real repository: the Owner's property -----------------------------------

    private val counter = mapOf<String, Any?>("prefix" to "SIE/QD", "fy" to "2025-26", "next" to 9, "pad" to 3)

    @Test
    fun `finalise, retire, build the next quotation, finalise - the second takes the NEXT number`() =
        runBlocking {
            val store = FakeQuotationStore(mutableMapOf(NUMBERING to counter))
            val repository = QuotationWriteRepository(store, now = { 1_760_000_000_000L }, pause = {})
            var drafts = QuoteDrafts().save(ready)
            val freshIds = ArrayDeque(listOf("qd_2", "qd_3"))
            val gate = QuoteFinaliser(
                online = { true },
                finalise = { draft -> repository.finalise(manager, draft, customers = emptyList(), snap = null) },
                retire = { id -> drafts = drafts.retire(id, freshIds.removeFirst()) },
                confirmZeroRates = { true },
                describe = { it.message.orEmpty() }
            )

            val first = gate.ensureFinalised(drafts.current!!, capPercent = null)

            // The builder is on whatever draft is current now, and the person
            // fills in the next quotation.
            val next = drafts.current!!.copy(
                party = QuotationPartySnapshot(name = "Harbour Interiors"),
                gstPercent = 18.0
            ).addManual(id = "ln_9", title = "Delivery", rate = 500.0)
            val second = gate.ensureFinalised(next, capPercent = null)

            assertEquals("Finalised as SIE/QD/2025-26/009", (first as GateOutcome.Finalised).message)
            assertEquals("Finalised as SIE/QD/2025-26/010", (second as GateOutcome.Finalised).message)
            assertEquals(2, store.quotations().size)
            assertEquals(11, store.docs.getValue(NUMBERING)["next"])
        }

    @Test
    fun `a press after a lost acknowledgement is answered with the same number`() = runBlocking {
        // The path the press-again line exists for.
        val store = FakeQuotationStore(mutableMapOf(NUMBERING to counter)).apply { loseNextResponse = true }
        val repository = QuotationWriteRepository(store, now = { 1_760_000_000_000L }, pause = {})
        val gate = QuoteFinaliser(
            online = { true },
            finalise = { draft -> repository.finalise(manager, draft, customers = emptyList(), snap = null) },
            retire = { retired += it },
            confirmZeroRates = { true },
            describe = { it.message.orEmpty() }
        )

        val lost = gate.ensureFinalised(ready, capPercent = null)
        assertTrue((lost as GateOutcome.NotFinalised).message.endsWith(QuoteFinaliser.PRESS_AGAIN))
        assertTrue(retired.isEmpty())

        val again = gate.ensureFinalised(ready, capPercent = null)
        assertEquals("Finalised as SIE/QD/2025-26/009", (again as GateOutcome.Finalised).message)
        assertEquals(listOf("qd_1"), retired)
        assertEquals(10, store.docs.getValue(NUMBERING)["next"])
    }
}
