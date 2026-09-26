package `in`.smartie.quotedesk.data.repository

import `in`.smartie.quotedesk.data.mapping.Keys
import `in`.smartie.quotedesk.data.mapping.toPartyRecord
import `in`.smartie.quotedesk.data.model.PartyRecord
import `in`.smartie.quotedesk.data.model.QuotationPartySnapshot
import `in`.smartie.quotedesk.data.model.RateTierV2
import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.domain.PartyAuthor
import `in`.smartie.quotedesk.domain.PartyDraft
import `in`.smartie.quotedesk.domain.PartyDuplicates
import `in`.smartie.quotedesk.domain.PartyPlan
import `in`.smartie.quotedesk.domain.PartyWrite
import `in`.smartie.quotedesk.domain.Permissions
import `in`.smartie.quotedesk.domain.QuoteParty

/** Whether a call actually put anything on the wire. */
enum class PartyWriteResult { WRITTEN, NO_CHANGE }

/**
 * What "Save this customer" came to: the customer the form now names — found
 * or created — or null when nothing was written because the person declined
 * to update the party found ([declined]) or it had been deleted.
 */
data class SavedParty(val id: String?, val result: PartyWriteResult, val declined: Boolean = false)

/**
 * Creating and correcting a customer.
 *
 * **Permission is decided twice, and the second time is the one that counts.**
 * The `require` on each method is the role floor: it stops a write the
 * caller's role could never make from opening a transaction at all. Whether
 * this person may change *this* party depends on the document — chiefly
 * whether it is archived — so that question is put to `PartyWrite` against the
 * record read **inside** the transaction, not against the copy the screen was
 * showing.
 *
 * **The id is the caller's to keep.** A screen mints one when the Add sheet
 * opens and passes the same one on a retry, so an ambiguous failure — the
 * commit landed, the acknowledgement did not — lands on the same document and
 * `alreadyExists` turns it into an honest refusal instead of a second
 * customer. That is N4.4's B2 lesson, applied before it can happen again.
 *
 * Writing is online-only: a Firestore transaction needs a round trip, so there
 * is no offline queue here and no optimistic local mutation.
 */
class PartyWriteRepository(
    private val store: PartyStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { Keys.generateId(PartyWrite.ID_PREFIX) }
) {

    /**
     * A new customer. Every quoting role may add one; a Staff account may not,
     * and the rules agree.
     */
    suspend fun create(
        member: Member,
        draft: PartyDraft,
        id: String = newId()
    ): PartyWriteResult {
        require(Permissions.canUseParties(member)) { NOT_ALLOWED }
        val author = authorOf(member)
        val at = now()
        return store.transaction { transaction ->
            val plan = PartyWrite.create(
                id = id,
                draft = draft,
                author = author,
                at = at,
                alreadyExists = transaction.read(id) != null
            )
            commit(transaction, plan)
        }
    }

    /**
     * Correcting one.
     *
     * The stored record is re-read inside the transaction and the plan is
     * built from *that*: whether the party is archived, and what its name
     * currently is, are both facts about the document rather than about what
     * was on screen when the sheet opened.
     */
    suspend fun edit(
        member: Member,
        record: PartyRecord,
        draft: PartyDraft
    ): PartyWriteResult {
        require(Permissions.canUseParties(member)) { NOT_ALLOWED }
        return update(member, record) { stored, author, at ->
            PartyWrite.edit(
                stored = stored,
                draft = draft,
                author = author,
                at = at,
                canRename = Permissions.canRenameOrArchiveParty(member),
                canArchive = Permissions.canRenameOrArchiveParty(member)
            )
        }
    }

    /** Archiving one, and bringing it back. Owner and Administrator only. */
    suspend fun setArchived(
        member: Member,
        record: PartyRecord,
        archived: Boolean
    ): PartyWriteResult {
        require(Permissions.canRenameOrArchiveParty(member)) { NOT_ALLOWED_ARCHIVE }
        return update(member, record) { stored, author, at ->
            PartyWrite.setArchived(
                stored = stored,
                archived = archived,
                author = author,
                at = at,
                canArchive = Permissions.canRenameOrArchiveParty(member)
            )
        }
    }

    /**
     * "Save this customer", from the quotation side — V8C4's `#qSaveParty`
     * (8011-8022) and `saveParty` (6404), as the Owner read them.
     *
     * 1. **A name first** — nothing is found or written without one.
     * 2. *(V8C4 then validates the GSTIN, phone and email —
     *    `gstinProblem`, `phoneProblem`, `emailProblem`. This app has no
     *    such validators yet and their text is not in this repository;
     *    recorded in `docs/PROJECT-STATUS.md`.)*
     * 3. **The customer is re-found from the form's own details** —
     *    [PartyDuplicates.matchFor] over the list the screen holds, V8C4's
     *    `findCustomer` with its `matchReason`. The customer picked earlier is
     *    not consulted at all: in V8C4, picking Sunrise, typing Metro Glass's
     *    details and pressing Save creates or updates **Metro Glass**. There
     *    is no held id to pass, so the N5.8b defect — the form merged into the
     *    picked record — cannot come back through a caller (N5.9a commit 8).
     * 4. **Found: [confirmUpdate] is asked first**, naming the party and the
     *    reason, as V8C4 asks — and **nothing is written if the answer is
     *    no**. Without the question, a form typed over a picked customer would
     *    silently update a third company's record (N5.9a commit 8b).
     * 5. **Confirmed:** merged through `PartyWrite.mergeInto` — V8C4's update
     *    branch, re-read inside the transaction: gaps filled, genuine changes
     *    taken, nothing already stored blanked, **the name never written**.
     * 6. **Not found:** created under [newId], with the quotation's [tier] as
     *    its type — V8C4's `type: p.type || state.tier || "client"` where the
     *    caller passes `type: state.tier`. [newId] is held by the screen for
     *    as long as one quotation is being filled in, so a retry lands on the
     *    same document and is refused honestly (N4.4's B2).
     *
     * Archived customers are never found, as V8C4's `findCustomer` skips them.
     * A customer found in the list but deleted since writes nothing.
     */
    suspend fun saveFromQuotation(
        member: Member,
        form: QuotationPartySnapshot,
        tier: RateTierV2,
        customers: List<PartyRecord>,
        newId: String,
        confirmUpdate: suspend (QuoteParty.MergeQuestion) -> Boolean
    ): SavedParty {
        require(Permissions.canUseParties(member)) { NOT_ALLOWED }
        val draft = QuoteParty.draftOf(form)
        draft.refusal()?.let { throw IllegalStateException(it) }

        val match = PartyDuplicates.matchFor(form, customers)
            ?: return SavedParty(newId, create(member, draft.copy(type = tier.wireValue), newId))

        val question = QuoteParty.MergeQuestion(match.party.name, match.on)
        if (!confirmUpdate(question)) return SavedParty(null, PartyWriteResult.NO_CHANGE, declined = true)

        val author = authorOf(member)
        val at = now()
        return store.transaction { transaction ->
            val doc = transaction.read(match.party.id)
                ?: return@transaction SavedParty(null, PartyWriteResult.NO_CHANGE)
            SavedParty(
                match.party.id,
                commit(transaction, PartyWrite.mergeInto(doc.toPartyRecord(), draft, author, at))
            )
        }
    }

    // --- plumbing ---------------------------------------------------------------

    private suspend fun update(
        member: Member,
        record: PartyRecord,
        plan: (PartyRecord, PartyAuthor, Long) -> PartyPlan
    ): PartyWriteResult {
        // Taken here, outside the transaction, because Firestore may run the
        // body more than once and `upBy` must not drift between attempts.
        val author = authorOf(member)
        val at = now()
        return store.transaction { transaction ->
            // Gone since the list last saw it. Nothing to write, and nothing
            // to complain about.
            val doc = transaction.read(record.id) ?: return@transaction PartyWriteResult.NO_CHANGE
            val stored = doc.toPartyRecord()
            commit(transaction, plan(stored, author, at))
        }
    }

    private fun commit(transaction: PartyTransaction, plan: PartyPlan): PartyWriteResult =
        when (plan) {
            is PartyPlan.Refused -> throw IllegalStateException(plan.message)
            PartyPlan.NoChange -> PartyWriteResult.NO_CHANGE
            is PartyPlan.Write -> {
                transaction.write(plan.docId, plan.data, plan.merge)
                PartyWriteResult.WRITTEN
            }
        }

    private fun authorOf(member: Member): PartyAuthor =
        PartyAuthor(name = member.name.ifBlank { member.email }, uid = member.uid)

    internal companion object {
        const val NOT_ALLOWED = "Your account cannot change a party"
        const val NOT_ALLOWED_ARCHIVE = "Only an Owner or Administrator can archive a party"
    }
}
