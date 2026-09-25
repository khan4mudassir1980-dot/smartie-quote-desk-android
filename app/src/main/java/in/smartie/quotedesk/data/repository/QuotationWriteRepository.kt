package `in`.smartie.quotedesk.data.repository

import `in`.smartie.quotedesk.data.mapping.DocData
import `in`.smartie.quotedesk.data.mapping.toNumberingRecord
import `in`.smartie.quotedesk.data.mapping.toQuotationRecord
import `in`.smartie.quotedesk.data.mapping.toQuotingRecord
import `in`.smartie.quotedesk.data.model.PartyRecord
import `in`.smartie.quotedesk.data.model.QuotationRecord
import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.domain.Permissions
import `in`.smartie.quotedesk.domain.QuotationPlan
import `in`.smartie.quotedesk.domain.QuotationWrite
import `in`.smartie.quotedesk.domain.QuoteDraft
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * What finalising came to.
 *
 * [Issued] and [AlreadyIssued] both mean **the quotation exists**, and both
 * hand back its **record** — not only its number — as V8C4's
 * `fbFinaliseAtomic` hands back `doc` so its caller can adopt the stored
 * quotation whole. The caller may remove the draft on either (never clear
 * it: see `docs/PROJECT-STATUS.md`). They are told apart only so the screen
 * can say honestly which happened.
 */
sealed interface FinaliseOutcome {
    /**
     * Written by this call. [record] is the written document read back
     * through the same reader every quotation in the app goes through, so
     * the screen shows what is stored rather than what was intended.
     */
    data class Issued(val record: QuotationRecord) : FinaliseOutcome {
        val quotationId: String get() = record.id
        val number: String get() = record.number
    }

    /**
     * This draft had already been issued — most often by an earlier attempt
     * whose acknowledgement never arrived. Nothing was written this time, and
     * [record] is the stored quotation.
     */
    data class AlreadyIssued(val record: QuotationRecord) : FinaliseOutcome {
        val quotationId: String get() = record.id
        val number: String get() = record.number
    }

    /**
     * Nothing was written; [message] is for the person. [cause] is the last
     * failure when the refusal came from the server rather than from a check
     * made here.
     */
    data class Refused(val message: String, val cause: Throwable? = null) : FinaliseOutcome
}

/**
 * Finalising a quotation: one transaction that writes it and takes its number.
 *
 * ## Read first, and only then the counter
 *
 * The transaction reads **this draft's own quotation document** before
 * anything else. If it is there, the draft was already issued — typically by
 * an attempt that committed and lost its response — and the stored number is
 * the answer: the counter is not read, not moved, and nothing is written.
 * Not reading the counter on that path matters as well as not writing it: a
 * document in the read set is one whose change makes Firestore re-run the
 * body, and a retry that is only fetching a number it already has should not
 * queue behind every other issue in the business.
 *
 * Only when there is no such document are the counter and the Owner's
 * discount limit read, and [QuotationWrite.plan] decides from those fresh
 * reads — not from what the screen was showing when it loaded. The cap in
 * particular is read here so that a limit the Owner lowered a minute ago
 * refuses **locally**, with the figure named, rather than reaching the rule
 * and coming back as an unexplained `permission-denied`.
 *
 * The party link is the exception, and deliberately: it is derived from the
 * customers the screen holds, as V8C4 derives it from `state.customers`,
 * because it is metadata — a list a moment old costs at most a
 * cross-reference.
 *
 * ## The bounded retry — an inference, not a discrimination
 *
 * N5.1 measured that a contended issue can come back as `permission-denied`:
 * the rules check `next == resource.data.next + 1` against the stored counter,
 * so a transaction built on a stale read is refused by the rules, and the SDK
 * does not retry a refusal. So [finalise] re-runs the whole transaction on a
 * refusal, up to [MAX_ATTEMPTS] times with a short jittered backoff, and on
 * nothing else.
 *
 * **It cannot tell which write was refused.** A transaction surfaces one
 * exception, and `PERMISSION_DENIED` names no document. Everything refusable
 * locally is refused locally first — role, identity, cap, GST, lines, a
 * party name — so what reaches the server is, as nearly as this app can
 * arrange, only the race for the counter. That a refusal *is* the counter is
 * therefore **an inference, not a discrimination**, and nothing here claims
 * more. A refusal that is not contention — a role changed on the server, a
 * rule this app does not know about — is retried to the bound and then
 * reported, which costs a second or so and issues nothing.
 *
 * Each retry re-reads everything, so it takes whatever number is free *now*,
 * and a discount limit lowered between the screen and the commit is caught
 * locally on the retry. The read-first still leads every attempt, so a retry
 * can never issue a second number for one draft.
 *
 * **Mirrored step for step by `firestore/tests/finalise-contention.test.js`**,
 * which names this file back. The Kotlin cannot run against the emulator, so
 * that Node test is the only place this protocol meets the real rules under
 * real contention; a change to one not made to the other breaks the only
 * thing joining them.
 *
 * ## [MAX_ATTEMPTS] is 6, and here is the measurement
 *
 * N5.9a commits 6 and 6b, in that Node test, against the shipped rules,
 * fifteen runs of `npx firebase emulators:exec --project smartie-rules-test
 * --only firestore "node --test --test-concurrency=1
 * tests/finalise-contention.test.js"`:
 *
 * - **Contention surfaces as `permission-denied`, never `aborted`, and the SDK
 *   re-runs nothing** — every transaction body ran once per attempt. With no
 *   self-retry, which is V8C4's behaviour, one contender per round won: 20 of
 *   60 issued at 3 simultaneous finalises, 10 or 11 of 100 at 10.
 * - **Retrying immediately, the herd stays together:** at 10 contenders the
 *   worst case was 9 or 10 attempts in every run — one winner per wave, as
 *   "one winner per round" predicts — and 136 of 500 (27%) would have
 *   exhausted a bound of 6.
 * - **With the jittered backoff this class ships:** at 10 the worst case was
 *   3 or 4, and **5 once**, in fifteen runs; at 3, never more than 3. None of
 *   1,600 finalises at the bound of 6, in ten runs, exhausted it.
 *
 * **So the bound rests on timing dispersion, not on any guarantee.** The
 * backoff — 150 ms × attempt plus up to 150 ms at random, the same shape as
 * the SDK's own retry — is what spreads the losers' retries out so that
 * several get through per wave; without it the worst case at ten is about
 * ten. 6 is the dispersed worst case plus one. **The emulator is one process,
 * and all of this is an indication, not a production measurement** — never to
 * be quoted as measured against real Firestore.
 *
 * **Exhausting the bound is safe, and that is why it need not be provably
 * sufficient.** Nothing is written; [finalise] returns [COUNTER_REFUSED]; the
 * screen says "Not finalised — … Your quotation is untouched"; the draft and
 * its id survive, and the next press picks up exactly where this one left
 * off, the read-first answering it if an earlier attempt did land. The bound
 * has one job — to make that outcome rare. Do not over-engineer it. The
 * headroom costs only a refusal that is not contention: five pauses, 2.25 to
 * 3.0 seconds, before it is reported — which is why 9b's control must be
 * visibly busy, saying it is taking a number, for that long.
 *
 * ## Refused before a transaction opens
 *
 * A Staff account, and a draft with no identity. Staff cannot read
 * `/quotations` at all, so the transaction's first read would be denied —
 * and a denial is exactly what N5.9a's retry has to read as contention. Every
 * refusal that can be made locally is made locally, so that what reaches the
 * server is, as nearly as this app can arrange, only the race for the counter.
 *
 * The clock is read **once, outside** the transaction, so a body Firestore
 * re-runs builds the same document it built the first time.
 *
 * Writing is online-only: a Firestore transaction needs a round trip, so
 * there is no offline queue and no optimistic local change.
 */
class QuotationWriteRepository(
    private val store: QuotationStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val jitter: () -> Double = { Random.nextDouble() }
) {

    /**
     * Issues [draft] under the next number, or answers with the number it was
     * already issued under.
     *
     * [customers] is the saved-customer list the screen holds; the party
     * link is derived against it (`QuoteParty.linkFor`). [snap] is frozen
     * into the quotation exactly as given — see `QuotationWrite`. Until N6
     * builds company settings the caller has nothing to put in it.
     */
    suspend fun finalise(
        member: Member,
        draft: QuoteDraft,
        customers: List<PartyRecord>,
        snap: Map<String, Any?>
    ): FinaliseOutcome {
        if (!Permissions.canQuote(member)) return FinaliseOutcome.Refused(QuotationWrite.NOT_ALLOWED)
        if (draft.id.isBlank()) return FinaliseOutcome.Refused(QuotationWrite.NO_IDENTITY)

        val at = now()
        var attempt = 1
        while (true) {
            try {
                return runOnce(member, draft, customers, snap, at)
            } catch (refused: Throwable) {
                if (!store.isRefusal(refused)) throw refused
                if (attempt >= MAX_ATTEMPTS) {
                    return FinaliseOutcome.Refused(COUNTER_REFUSED, cause = refused)
                }
                pause(backoffFor(attempt, jitter()))
                attempt++
            }
        }
    }

    /** One whole transaction: read first, then the counter, then both writes. */
    private suspend fun runOnce(
        member: Member,
        draft: QuoteDraft,
        customers: List<PartyRecord>,
        snap: Map<String, Any?>,
        at: Long
    ): FinaliseOutcome =
        store.transaction { transaction ->
            val existing = transaction.readQuotation(draft.id)?.toQuotationRecord()
            val fresh = existing == null
            val counter = if (fresh) transaction.readNumbering()?.toNumberingRecord() else null
            val quoting = if (fresh && draft.discount != null) {
                transaction.readQuoting()?.toQuotingRecord()
            } else {
                null
            }
            val plan = QuotationWrite.plan(
                draft = draft,
                member = member,
                quoting = quoting,
                counter = counter,
                existing = existing,
                customers = customers,
                snap = snap,
                at = at
            )
            when (plan) {
                is QuotationPlan.Write -> {
                    transaction.writeQuotation(plan.quotationId, plan.quotation)
                    transaction.writeNumbering(plan.counter)
                    FinaliseOutcome.Issued(DocData(plan.quotationId, plan.quotation).toQuotationRecord())
                }
                is QuotationPlan.AlreadyIssued -> FinaliseOutcome.AlreadyIssued(plan.record)
                is QuotationPlan.Refused -> FinaliseOutcome.Refused(plan.message)
            }
        }

    companion object {
        /**
         * Tries at one number before [finalise] gives up and says so — the
         * emulator's pessimistic worst case (5) plus one. See the class KDoc
         * and `firestore/tests/finalise-contention.test.js`, which carries the
         * same number; change both or neither.
         */
        const val MAX_ATTEMPTS = 6

        /** The first pause; each later one grows by the same step. */
        const val BACKOFF_STEP_MS = 150L

        /** Up to this much more, at random, so racing devices spread out. */
        const val BACKOFF_JITTER_MS = 150L

        /**
         * Every attempt was refused. Worded as the inference it is: most
         * likely the counter was busy, but a refusal names no cause.
         */
        const val COUNTER_REFUSED =
            "The server refused the number each time it was tried - most likely somebody " +
                "else was issuing at the same moment. Nothing was issued"

        /** How long to wait after failed attempt [attempt], with [jitter] in `0.0..1.0`. */
        fun backoffFor(attempt: Int, jitter: Double): Long =
            BACKOFF_STEP_MS * attempt + (BACKOFF_JITTER_MS * jitter.coerceIn(0.0, 1.0)).toLong()
    }
}
