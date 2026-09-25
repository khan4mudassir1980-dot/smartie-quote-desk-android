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

    /** Nothing was written; [message] is for the person. */
    data class Refused(val message: String) : FinaliseOutcome
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
 * reads — not from what the screen was showing when it loaded. The party link
 * is the exception, and deliberately: it is derived from the customers the
 * screen holds, as V8C4 derives it from `state.customers`, because it is
 * metadata — a list a moment old costs at most a cross-reference.
 * The cap in particular is read here so that a limit the Owner lowered a
 * minute ago refuses **locally**, with the figure named, rather than reaching
 * the rule and coming back as an unexplained `permission-denied`.
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
    private val now: () -> Long = System::currentTimeMillis
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
        return store.transaction { transaction ->
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
    }
}
