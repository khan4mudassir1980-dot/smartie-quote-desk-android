package `in`.smartie.quotedesk.ui.products

import `in`.smartie.quotedesk.data.repository.FinaliseOutcome
import `in`.smartie.quotedesk.domain.PartyFormat
import `in`.smartie.quotedesk.domain.QuoteDraft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/** Where the gate is, for the control that opened it. */
enum class GatePhase {
    /** Nothing running; the control may be pressed. */
    IDLE,

    /** The checks, and the ₹0 question while it waits for an answer. */
    CHECKING,

    /** The finalise transaction is out: the control says it is taking a number. */
    TAKING_NUMBER
}

/** What one press of the gate came to. */
sealed interface GateOutcome {
    /** Issued — now or earlier — and the draft retired. [message] is "Finalised as X". */
    data class Finalised(val number: String, val message: String) : GateOutcome

    /** Stopped, and why, for the panel. Nothing was retired. */
    data class NotFinalised(val message: String) : GateOutcome

    /** The ₹0 question was answered "Cancel". Nothing is said, as V8C4's `return false`. */
    data object Cancelled : GateOutcome

    /** A press arrived while the gate was already open; it did nothing. */
    data object AlreadyRunning : GateOutcome
}

/**
 * **V8C4's `ensureFinalised` — "one gate in front of every action that issues
 * a quotation."**
 *
 * V8C4 has no Finalise button. `finaliseQuote` has exactly one caller, this
 * gate, and the gate's callers are PDF, Print and WhatsApp (the Owner's
 * reading, 2026-09-26). So N5.9b builds the gate, with every check in it, and
 * a stand-alone Finalise control as its **first** caller — because N5.11 has
 * not built the other three and nothing could issue otherwise. N5.11 adds
 * callers and changes nothing here.
 *
 * ## In order
 *
 * 1. **`QuoteDraft.refusal`** — called, never rebuilt: the one rule that
 *    `QuotationWrite.plan` also calls. It covers V8C4's no-lines check and
 *    this app's refusal of an unpriced line, and its message is shown as it
 *    stands.
 * 2. *(V8C4's `if(isFinalised()) return true` has no counterpart. A
 *    finalised draft here is **retired**, so there is never an
 *    already-finalised draft to short-circuit; a press after a lost
 *    acknowledgement goes to the read-first instead, which answers it.)*
 * 3. **The ₹0 question** — [zeroRateQuestion]. Lines with `!(rate > 0)`,
 *    V8C4's predicate; after step 1 only lines priced at exactly zero can
 *    reach it. The sentence is **ours**, not V8C4's "have no rate", which is
 *    false for these lines; the structure is V8C4's. The answers, "Continue
 *    anyway" and "Cancel", are **a choice, not a port** — V8C4's
 *    `confirmAction` is `window.confirm`, the browser's OK / Cancel, so there
 *    was no label to port. Do not "correct" them to match V8C4.
 * 4. **"Client GSTIN: "** + `PartyFormat.gstinProblem`.
 * 5. **"Client phone: "** + `PartyFormat.phoneProblem`.
 * 6. **Offline** → [OFFLINE]. **A courtesy that removes the common case, not
 *    a guard.** The system reports a connection it cannot prove carries
 *    anything, so the transaction failing is the real protection — never
 *    remove the failure handling below on the strength of this check.
 * 7. **Finalise, capped at [CAP_MILLIS] of wall-clock.** The repository's
 *    retry is bounded in pauses, not in round trips: on a network that is
 *    connected but carries nothing, each attempt can hang as long as the SDK
 *    allows, the control busy and edits refused. `withTimeoutOrNull`, never a
 *    throwing timeout — that is a `CancellationException`, which the friendly
 *    mapper would report as "Sign-in was cancelled". The cap covers the
 *    network only, never the time spent answering step 3.
 * 8. **Success, V8C4's order:** the number in hand → [retire] (the draft and
 *    its id together) → no local upsert (the Quotations tab reads only the
 *    listener, keyed by document id, so V8C4's "the listener may have beaten
 *    us to it" holds by construction) → "Finalised as X", for `Issued` and
 *    `AlreadyIssued` alike, as V8C4's `reused` path continues into the same
 *    order.
 *
 * ## Failing
 *
 * Steps 1 and 3 to 6 say their own sentence, as V8C4's toasts do. Anything
 * from the finalise itself reads V8C4's catch: "Not finalised — <reason>.
 * Your quotation is untouched." Where the number **may have been taken** —
 * a thrown failure, or the cap — [PRESS_AGAIN] follows: on a lost
 * acknowledgement the draft is untouched but the number is gone, and a
 * second press is answered by the read-first with the same number, where
 * Clear would leave an orphan numbered quotation. It is **not** added to a
 * refusal decided locally or by the rules, which a second press cannot
 * change.
 *
 * **One gate at a time:** a press while the gate is open does nothing, across
 * the question and the network alike. Pure — the view model supplies what
 * touches Android and Firebase — so every step is unit-tested.
 */
class QuoteFinaliser(
    /** Whether the system reports a connection. See step 6. */
    private val online: () -> Boolean,
    /** `QuotationWriteRepository.finalise` for this member. */
    private val finalise: suspend (QuoteDraft) -> FinaliseOutcome,
    /** Takes the finalised draft out and moves on — `DraftWrites.replace`. */
    private val retire: suspend (finalisedId: String) -> Unit,
    /** Asks the ₹0 question and answers true for "Continue anyway". */
    private val confirmZeroRates: suspend (question: String) -> Boolean,
    /** A thrown failure in words — the port of `friendlyAuthError`. */
    private val describe: (Throwable) -> String,
    /** Where failures are logged; never shown through here. */
    private val log: (Throwable) -> Unit = {},
    private val capMillis: Long = CAP_MILLIS
) {

    private val _phase = MutableStateFlow(GatePhase.IDLE)
    val phase: StateFlow<GatePhase> = _phase.asStateFlow()

    /**
     * One press. [capPercent] is the Manager's discount limit as the screen
     * holds it (`QuoteDiscount.capFor`), null when none is configured; the
     * transaction re-reads it before anything is written.
     */
    suspend fun ensureFinalised(draft: QuoteDraft, capPercent: Double?): GateOutcome {
        if (!_phase.compareAndSet(GatePhase.IDLE, GatePhase.CHECKING)) return GateOutcome.AlreadyRunning
        try {
            draft.refusal(capPercent)?.let { return GateOutcome.NotFinalised(it) }

            zeroRateQuestion(draft)?.let { question ->
                if (!confirmZeroRates(question)) return GateOutcome.Cancelled
            }

            PartyFormat.gstinProblem(draft.party.gstin)?.let {
                return GateOutcome.NotFinalised(CLIENT_GSTIN + it)
            }
            PartyFormat.phoneProblem(draft.party.phone)?.let {
                return GateOutcome.NotFinalised(CLIENT_PHONE + it)
            }

            if (!online()) return GateOutcome.NotFinalised(OFFLINE)

            _phase.value = GatePhase.TAKING_NUMBER
            val outcome = try {
                withTimeoutOrNull(capMillis) { finalise(draft) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                log(failure)
                return GateOutcome.NotFinalised(notFinalised(describe(failure), numberMayBeTaken = true))
            }
            if (outcome == null) {
                return GateOutcome.NotFinalised(notFinalised(TIMED_OUT, numberMayBeTaken = true))
            }

            return when (outcome) {
                is FinaliseOutcome.Issued -> finished(draft, outcome.number)
                is FinaliseOutcome.AlreadyIssued -> finished(draft, outcome.number)
                is FinaliseOutcome.Refused -> {
                    outcome.cause?.let(log)
                    GateOutcome.NotFinalised(notFinalised(outcome.message, numberMayBeTaken = false))
                }
            }
        } finally {
            _phase.value = GatePhase.IDLE
        }
    }

    private suspend fun finished(draft: QuoteDraft, number: String): GateOutcome {
        // V8C4 checks the number before its success order runs; nothing is
        // retired without one.
        if (number.isBlank()) {
            return GateOutcome.NotFinalised(notFinalised(COUNTER_SILENT, numberMayBeTaken = false))
        }
        try {
            retire(draft.id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            // The quotation exists, so this is still a success. The draft
            // stays, and the next press is answered `AlreadyIssued` and
            // retires it then.
            log(failure)
        }
        return GateOutcome.Finalised(number, finalisedAs(number))
    }

    companion object {
        /** Thirty seconds: ample against 2.25-3.0 s of measured pauses. */
        const val CAP_MILLIS = 30_000L

        /** V8C4's own sentence (6203). */
        const val OFFLINE = "Finalising needs an internet connection — the number is shared with the team"

        /** V8C4's own sentence, for a transaction that came back without a number. */
        const val COUNTER_SILENT = "The shared counter did not respond"

        /**
         * The ported `friendlyAuthError` text for `deadline-exceeded`
         * (`FriendlyMessages`); `FriendlyMessagesTest` keeps the two equal.
         */
        const val TIMED_OUT = "The connection timed out. Please try again."

        const val PRESS_AGAIN = "Press Finalise again — if a number was taken, the same one comes back."

        const val CLIENT_GSTIN = "Client GSTIN: "
        const val CLIENT_PHONE = "Client phone: "

        /** V8C4's toast, `Finalised as ${no}`. */
        fun finalisedAs(number: String): String = "Finalised as $number"

        /**
         * V8C4's catch: `"Not finalised — " + friendlyAuthError(e) + " Your
         * quotation is untouched."`, the reason ending in one full stop.
         */
        fun notFinalised(reason: String, numberMayBeTaken: Boolean): String = buildString {
            append("Not finalised — ")
            append(reason.trim().trimEnd('.'))
            append(". Your quotation is untouched.")
            if (numberMayBeTaken) append(' ').append(PRESS_AGAIN)
        }

        /**
         * The question before issuing lines priced at ₹0, or null when there
         * are none. V8C4's predicate and structure — the count, up to four
         * titles, "• …" beyond, the closing question — with **our** sentence,
         * because V8C4's "have no rate" is false of a line priced at ₹0.
         */
        fun zeroRateQuestion(draft: QuoteDraft): String? {
            val zero = draft.lines.filter { line -> line.rate?.let { it > 0.0 } != true }
            if (zero.isEmpty()) return null
            val count = zero.size
            val heading = if (count == 1) "1 line is priced at ₹0:" else "$count lines are priced at ₹0:"
            val titles = zero.take(4).joinToString(separator = "\n• ", prefix = "• ") { it.title }
            val more = if (count > 4) "\n• …" else ""
            return "$heading\n\n$titles$more\n\nContinue anyway?"
        }
    }
}
