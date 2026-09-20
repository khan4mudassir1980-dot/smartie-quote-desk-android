package `in`.smartie.quotedesk.data.repository

import `in`.smartie.quotedesk.data.mapping.Keys
import `in`.smartie.quotedesk.data.mapping.toPurchaseRecord
import `in`.smartie.quotedesk.data.model.PurchaseRecord
import `in`.smartie.quotedesk.data.model.UrgencyV2
import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.domain.Permissions
import `in`.smartie.quotedesk.domain.PurchaseAuthor
import `in`.smartie.quotedesk.domain.PurchaseDraft
import `in`.smartie.quotedesk.domain.PurchasePlan
import `in`.smartie.quotedesk.domain.PurchaseWrite
import `in`.smartie.quotedesk.domain.Role
import `in`.smartie.quotedesk.domain.RoleTitles

/** Whether a call actually put anything on the wire. */
enum class PurchaseWriteResult { WRITTEN, NO_CHANGE }

/**
 * The only writer N4 adds.
 *
 * **The transaction contract**, binding on every call below:
 *
 * 1. the requirement's id and `at` are generated **once, before** the
 *    transaction;
 * 2. both are **reused** if Firestore replays the body, so one action can
 *    never create two requirements or drift its own timestamp;
 * 3. the stored document is read **inside** the transaction and the plan is
 *    built from *that* — never from the record the screen was showing;
 * 4. `rev` is therefore the **stored** revision plus one, which is what makes
 *    two devices completing the same requirement resolve instead of both
 *    appearing to succeed;
 * 5. a refusal is a sentence, decided against the stored document, rather
 *    than a permission error nobody can act on.
 *
 * Point 5 is why this reads before it writes even where the rules would have
 * caught the write anyway. A second device still showing a requirement as
 * open gets "Already received by Asha" instead of a stale-revision failure.
 *
 * Writing is **online-only**. A Firestore transaction needs a round trip, so
 * there is no offline queue here and no optimistic local mutation.
 *
 * Permission is checked before the transaction opens, so a write the rules
 * would refuse never leaves the device. The rules refuse it as well — except
 * for [reopen], which is the one purchase restriction they cannot express.
 */
class PurchaseWriteRepository(
    private val store: PurchaseStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { Keys.generateId(PurchaseWrite.ID_PREFIX) }
) {

    /**
     * A new requirement. Everyone active may add one, Workers included.
     *
     * The id is read back inside the transaction before it is used: a `set`
     * on an id that is already taken is evaluated by the rules as an
     * **update**, which anyone above a Worker may do, so a collision would
     * overwrite somebody else's requirement rather than failing.
     */
    suspend fun create(member: Member, draft: PurchaseDraft): PurchaseWriteResult {
        require(Permissions.canAddPurchase(member)) { NOT_ALLOWED_ADD }
        val author = authorOf(member)
        val at = now()
        val id = newId()
        return store.transaction { transaction ->
            commit(
                transaction,
                PurchaseWrite.create(
                    id = id,
                    draft = draft,
                    author = author,
                    at = at,
                    alreadyExists = transaction.read(id) != null
                )
            )
        }
    }

    /** The Edit sheet, and the way out of an unusable stored quantity. */
    suspend fun edit(
        member: Member,
        record: PurchaseRecord,
        name: String,
        quantity: Double,
        urgency: UrgencyV2,
        note: String
    ): PurchaseWriteResult {
        require(Permissions.canEditPurchase(member)) { NOT_ALLOWED_EDIT }
        return update(member, record) { stored, author, at ->
            PurchaseWrite.edit(stored, name, quantity, urgency, note, author, at)
        }
    }

    /** Just the urgency, from the card. */
    suspend fun setUrgency(
        member: Member,
        record: PurchaseRecord,
        urgency: UrgencyV2
    ): PurchaseWriteResult {
        require(Permissions.canEditPurchase(member)) { NOT_ALLOWED_EDIT }
        return update(member, record) { stored, author, at ->
            PurchaseWrite.setUrgency(stored, urgency, author, at)
        }
    }

    /** It arrived. Owner, Administrator and Staff; the rules agree. */
    suspend fun markReceived(
        member: Member,
        record: PurchaseRecord,
        receivedQuantity: Double
    ): PurchaseWriteResult {
        require(Permissions.canSetPurchaseStatus(member)) { NOT_ALLOWED_RECEIVE }
        return update(member, record) { stored, author, at ->
            PurchaseWrite.markReceived(stored, receivedQuantity, author, at)
        }
    }

    /**
     * Back to the active list. **Owner and Administrator only.**
     *
     * This `require` is the whole of the enforcement: the v9 rules allow any
     * non-Worker to update a requirement, and a reopen is an ordinary update.
     * See `Permissions.canReopenPurchase` and `docs/N4-plan.md`.
     */
    suspend fun reopen(member: Member, record: PurchaseRecord): PurchaseWriteResult {
        require(Permissions.canReopenPurchase(member)) { NOT_ALLOWED_REOPEN }
        return update(member, record) { stored, author, at ->
            PurchaseWrite.reopen(stored, author, at)
        }
    }

    /** A soft delete, and never a hard one. Administrator only. */
    suspend fun softDelete(member: Member, record: PurchaseRecord): PurchaseWriteResult {
        require(Permissions.canDeletePurchase(member)) { NOT_ALLOWED_DELETE }
        return update(member, record) { stored, author, at ->
            PurchaseWrite.softDelete(stored, author, at)
        }
    }

    // --- plumbing -------------------------------------------------------------

    /**
     * Reads the stored document inside the transaction and plans from it.
     *
     * `at` is taken **here**, outside [PurchaseStore.transaction], which is
     * contract points 1 and 2: the body may run several times and will reuse
     * it every time. The record the caller passed is used for one thing only —
     * finding the document — and never for a value.
     */
    private suspend fun update(
        member: Member,
        record: PurchaseRecord,
        plan: (PurchaseRecord, PurchaseAuthor, Long) -> PurchasePlan
    ): PurchaseWriteResult {
        val author = authorOf(member)
        val at = now()
        return store.transaction { transaction ->
            // Gone since the board last saw it. Nothing to write, and nothing
            // to complain about.
            val stored = transaction.read(record.id)?.toPurchaseRecord()
                ?: return@transaction PurchaseWriteResult.NO_CHANGE
            commit(transaction, plan(stored, author, at))
        }
    }

    private fun commit(
        transaction: PurchaseTransaction,
        plan: PurchasePlan
    ): PurchaseWriteResult = when (plan) {
        is PurchasePlan.Refused -> throw IllegalStateException(plan.message)
        PurchasePlan.NoChange -> PurchaseWriteResult.NO_CHANGE
        is PurchasePlan.Write -> {
            transaction.write(plan.docId, plan.data, plan.merge)
            PurchaseWriteResult.WRITTEN
        }
    }

    private fun authorOf(member: Member): PurchaseAuthor =
        PurchaseAuthor(name = member.name.ifBlank { member.email }, uid = member.uid)

    private companion object {
        /**
         * The titles come from [RoleTitles], built from the roles the
         * permission actually allows, so a message cannot drift from the rule
         * it describes. `Role.STAFF` reads **Manager**; the stored value is
         * untouched.
         */
        private val PURCHASE_EDITORS = RoleTitles.anyOf(Role.OWNER, Role.ADMIN, Role.STAFF)
        private val ADMINS = RoleTitles.anyOf(Role.OWNER, Role.ADMIN)

        /**
         * Adding is open to everyone active, Workers included, so naming
         * roles here would describe a rule that does not exist.
         */
        const val NOT_ALLOWED_ADD = "Your account cannot add a requirement"

        val NOT_ALLOWED_EDIT = "Only $PURCHASE_EDITORS can change a requirement"
        val NOT_ALLOWED_RECEIVE = "Only $PURCHASE_EDITORS can mark a requirement received"
        val NOT_ALLOWED_REOPEN = "Only $ADMINS can reopen a requirement"
        val NOT_ALLOWED_DELETE = "Only $ADMINS can remove a requirement"
    }
}
