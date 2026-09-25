package `in`.smartie.quotedesk.data.repository

import `in`.smartie.quotedesk.data.mapping.Keys
import `in`.smartie.quotedesk.data.mapping.toPartyRecord
import `in`.smartie.quotedesk.data.model.PartyRecord
import `in`.smartie.quotedesk.data.model.QuotationPartySnapshot
import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.domain.PartyAuthor
import `in`.smartie.quotedesk.domain.PartyDraft
import `in`.smartie.quotedesk.domain.PartyDuplicates
import `in`.smartie.quotedesk.domain.PartyPlan
import `in`.smartie.quotedesk.domain.PartyWrite
import `in`.smartie.quotedesk.domain.Permissions

/** Whether a call actually put anything on the wire. */
enum class PartyWriteResult { WRITTEN, NO_CHANGE }

/**
 * What "Save this customer" came to: the customer the form now names — found
 * or created — or null when the one found had been deleted and nothing was
 * written.
 */
data class SavedParty(val id: String?, val result: PartyWriteResult)

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
     * "Save this customer", from the quotation side — **V8C4's `saveParty`
     * (6404): the customer is re-found from the form's own details, and the
     * customer the person picked earlier is not consulted at all.**
     *
     * `saveParty` opens `if(!p || !p.name) return null; ... let
     * c=findCustomer(p);`. So in V8C4, picking Sunrise, typing Metro Glass's
     * details over the form and pressing Save creates or updates **Metro
     * Glass**, and Sunrise is untouched. Until N5.9a commit 8 this function
     * took the held `partyId` and merged whatever was on the form into that
     * record — a native-only defect that silently wrote one firm's phone and
     * GSTIN onto another's. **There is no held id to pass any more**, so the
     * defect cannot come back through a caller.
     *
     * - The target is [PartyDuplicates.findCustomer] over [customers] — the
     *   list the screen already holds, as V8C4 uses `state.customers`: the
     *   first unarchived saved customer the form is
     *   [PartyDuplicates.sameParty] as. The same predicate the quotation's
     *   party link uses; one definition, not two.
     * - **Found:** merged into through `PartyWrite.mergeInto`, re-read inside
     *   the transaction — gaps filled, real corrections taken, **nothing
     *   already stored blanked**. The person was quoting, not editing a
     *   customer, so an empty box is silence. That is why the quotation side
     *   must never be routed through [edit], where an empty box is the
     *   instruction.
     * - **Not found:** created under [newId], which the screen holds for as
     *   long as one quotation is being filled in, so a retry after an
     *   ambiguous failure lands on the same document and is refused honestly
     *   rather than writing a twin (N4.4's B2).
     * - Found in the list but deleted since: nothing is written and
     *   [SavedParty.id] is null, as [update] says nothing in the same case.
     *
     * Archived customers are never found, as V8C4's `findCustomer` skips
     * them: a form resembling one creates a new customer rather than writing
     * into one somebody archived.
     */
    suspend fun saveFromQuotation(
        member: Member,
        draft: PartyDraft,
        customers: List<PartyRecord>,
        newId: String
    ): SavedParty {
        require(Permissions.canUseParties(member)) { NOT_ALLOWED }
        draft.refusal()?.let { throw IllegalStateException(it) }

        val form = QuotationPartySnapshot(name = draft.name, gstin = draft.gstin, phone = draft.phone)
        val found = PartyDuplicates.findCustomer(form, customers)
            ?: return SavedParty(newId, create(member, draft, newId))

        val author = authorOf(member)
        val at = now()
        return store.transaction { transaction ->
            val doc = transaction.read(found.id)
                ?: return@transaction SavedParty(null, PartyWriteResult.NO_CHANGE)
            SavedParty(found.id, commit(transaction, PartyWrite.mergeInto(doc.toPartyRecord(), draft, author, at)))
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
