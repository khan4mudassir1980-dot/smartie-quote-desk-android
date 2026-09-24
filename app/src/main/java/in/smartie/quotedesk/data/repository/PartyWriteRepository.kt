package `in`.smartie.quotedesk.data.repository

import `in`.smartie.quotedesk.data.mapping.Keys
import `in`.smartie.quotedesk.data.mapping.toPartyRecord
import `in`.smartie.quotedesk.data.model.PartyRecord
import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.domain.PartyAuthor
import `in`.smartie.quotedesk.domain.PartyDraft
import `in`.smartie.quotedesk.domain.PartyPlan
import `in`.smartie.quotedesk.domain.PartyWrite
import `in`.smartie.quotedesk.domain.Permissions

/** Whether a call actually put anything on the wire. */
enum class PartyWriteResult { WRITTEN, NO_CHANGE }

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
     * "Save this customer", from the quotation side (N5.8b).
     *
     * **Two genuinely different writes, decided by whether a customer was
     * chosen.**
     *
     * A customer chosen from the picker is *merged* into, through
     * `PartyWrite.mergeInto`: gaps are filled, real corrections are taken and
     * **nothing already stored is blanked**. The person was quoting, not
     * editing a customer, so a box they left empty is silence rather than an
     * instruction to forget. That is the opposite of [edit], where an empty
     * box *is* the instruction — and it is why the quotation side must never
     * be routed through [edit].
     *
     * A customer typed from scratch is *created*, under [newId], which the
     * screen holds for as long as the person is filling one quotation in. A
     * retry after an ambiguous failure therefore lands on the same document
     * and is refused honestly rather than writing a twin — N4.4's B2 lesson,
     * the same one [create] already carries.
     *
     * A [partyId] naming a customer that has since been deleted writes
     * nothing and says nothing, exactly as [update] does: it is not this
     * person's mistake and there is nothing for them to do about it.
     */
    suspend fun saveFromQuotation(
        member: Member,
        partyId: String,
        draft: PartyDraft,
        newId: String
    ): PartyWriteResult {
        require(Permissions.canUseParties(member)) { NOT_ALLOWED }
        if (partyId.isBlank()) return create(member, draft, newId)

        draft.refusal()?.let { throw IllegalStateException(it) }
        val author = authorOf(member)
        val at = now()
        return store.transaction { transaction ->
            val doc = transaction.read(partyId) ?: return@transaction PartyWriteResult.NO_CHANGE
            commit(transaction, PartyWrite.mergeInto(doc.toPartyRecord(), draft, author, at))
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
