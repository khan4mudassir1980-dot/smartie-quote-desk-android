package `in`.smartie.quotedesk.data.repository

import `in`.smartie.quotedesk.data.mapping.DocData
import `in`.smartie.quotedesk.data.mapping.Keys
import `in`.smartie.quotedesk.data.mapping.toPurchaseRecord
import `in`.smartie.quotedesk.data.model.PurchaseRecord
import `in`.smartie.quotedesk.data.model.UrgencyV2
import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.domain.Permissions
import `in`.smartie.quotedesk.domain.PurchaseAccess
import `in`.smartie.quotedesk.domain.PurchaseAuthor
import `in`.smartie.quotedesk.domain.PurchaseDraft
import `in`.smartie.quotedesk.domain.PurchasePlan
import `in`.smartie.quotedesk.domain.PurchaseWrite
import `in`.smartie.quotedesk.domain.Role
import `in`.smartie.quotedesk.domain.RoleTitles

/** Whether a call actually put anything on the wire. */
enum class PurchaseWriteResult { WRITTEN, NO_CHANGE }

/**
 * What a create wrote, as well as whether it wrote.
 *
 * [record] is the document **exactly as it went on the wire**, read back out
 * of the plan rather than rebuilt from the draft, so it cannot drift from
 * what was stored. The board shows it while the transaction's round trip
 * finishes — a Firestore transaction is applied on the server and is not
 * latency-compensated, so nothing appears locally until the snapshot returns.
 */
data class PurchaseCreated(
    val result: PurchaseWriteResult,
    val record: PurchaseRecord?
)

/**
 * What a delivery came to, **decided inside the transaction**.
 *
 * `rcvQty` is a cumulative total, so whether a receipt finished a requirement
 * is not something the screen can work out: the figure it was showing may be
 * two deliveries old. Both totals are read back out of the plan that was
 * written, through the same reader the listener uses, so what the person is
 * told and what Firestore holds are the same document by construction.
 */
data class PurchaseReceipt(
    val result: PurchaseWriteResult,
    /** The cumulative quantity received once this delivery is counted. */
    val receivedTotal: Double = 0.0,
    /** The total required, which a delivery never changes. */
    val requiredTotal: Double = 0.0
) {
    /** Still to come. Never negative. */
    val remaining: Double get() = (requiredTotal - receivedTotal).coerceAtLeast(0.0)

    /** Whether this delivery was the one that finished it. */
    val complete: Boolean
        get() = requiredTotal > 0.0 &&
            receivedTotal >= requiredTotal - PurchaseRecord.QUANTITY_TOLERANCE
}

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
 * **Permission is decided twice, and the second time is the one that counts.**
 * The `require` on the first line of each method is the role floor — it stops
 * a write the caller's role could never make from opening a transaction at
 * all. But whether somebody may change *this* requirement depends on the
 * document: who raised it, and whether anything has been delivered against
 * it. The board's copy can be two deliveries old, so that question is put to
 * `PurchaseAccess` against the record read **inside** the transaction. A
 * screen that is behind refuses with a sentence rather than writing something
 * the rules would have thrown out.
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
    suspend fun create(member: Member, draft: PurchaseDraft): PurchaseCreated {
        require(Permissions.canAddPurchase(member)) { NOT_ALLOWED_ADD }
        val author = authorOf(member)
        val at = now()
        val id = newId()
        return store.transaction { transaction ->
            val plan = PurchaseWrite.create(
                id = id,
                draft = draft,
                author = author,
                at = at,
                alreadyExists = transaction.read(id) != null
            )
            val result = commit(transaction, plan)
            // Read back out of the plan, through the same reader the listener
            // uses, so what the board shows and what Firestore holds are the
            // same document by construction.
            val written = (plan as? PurchasePlan.Write)
                ?.let { DocData(id, it.data).toPurchaseRecord() }
            PurchaseCreated(result, written)
        }
    }

    /**
     * The Edit sheet, and the way out of an unusable stored quantity.
     *
     * Open to a Manager on any untouched requirement, to anybody on their
     * **own** untouched one, and to an Owner or Administrator always. The
     * role floor below only refuses somebody who could never edit anything;
     * the per-record question is settled against the stored document.
     */
    suspend fun edit(
        member: Member,
        record: PurchaseRecord,
        name: String,
        quantity: Double,
        urgency: UrgencyV2,
        note: String
    ): PurchaseWriteResult {
        require(Permissions.canAddPurchase(member)) { NOT_ALLOWED_EDIT }
        return update(member, record, PurchaseAccess::canEdit) { stored, author, at ->
            PurchaseWrite.edit(stored, name, quantity, urgency, note, author, at)
        }
    }

    /** Just the urgency, from the card. It follows the edit exactly. */
    suspend fun setUrgency(
        member: Member,
        record: PurchaseRecord,
        urgency: UrgencyV2
    ): PurchaseWriteResult {
        require(Permissions.canAddPurchase(member)) { NOT_ALLOWED_EDIT }
        return update(member, record, PurchaseAccess::canSetUrgency) { stored, author, at ->
            PurchaseWrite.setUrgency(stored, urgency, author, at)
        }
    }

    /**
     * A delivery arrived. Owner, Administrator and Staff; the rules agree.
     *
     * [receivedNow] is **this delivery**, not the total. The running total is
     * computed against the stored document inside the transaction, so two
     * people receiving parts of the same order resolve to one total rather
     * than to two opinions about it — and the caller is told what the total
     * came to, because the board cannot work that out for itself.
     */
    suspend fun markReceived(
        member: Member,
        record: PurchaseRecord,
        receivedNow: Double
    ): PurchaseReceipt {
        // The floor is "anybody who may add one", because the person who
        // raised a requirement may now receive against it. Which requirement
        // is decided inside the transaction, against the stored document.
        require(Permissions.canAddPurchase(member)) { NOT_ALLOWED_RECEIVE }
        val author = authorOf(member)
        val at = now()
        return store.transaction { transaction ->
            val doc = transaction.read(record.id)
                ?: return@transaction PurchaseReceipt(PurchaseWriteResult.NO_CHANGE)
            // Who may receive is settled by the role floor above; a Staff
            // account never reaches here, not even for their own requirement.
            // What is left is the stored figure's type — see below.
            unreadableReceipt(member, doc)?.let { throw IllegalStateException(it) }
            val stored = doc.toPurchaseRecord()
            // Ownership only. The planner owns every refusal about *state* —
            // "Already received by Omar" says far more than a guard in front
            // of it could — so this asks the one question the planner cannot:
            // is this requirement this person's to receive against?
            if (!mayDeliver(member, stored)) {
                throw IllegalStateException(PurchaseAccess.deliveryRefusalFor(member, stored))
            }
            val plan = PurchaseWrite.markReceived(stored, receivedNow, author, at)
            val result = commit(transaction, plan)
            val written = (plan as? PurchasePlan.Write)
                ?.let { DocData(stored.id, it.data).toPurchaseRecord() }
            PurchaseReceipt(
                result = result,
                receivedTotal = written?.receivedTotal ?: stored.receivedTotal,
                requiredTotal = written?.quantity ?: stored.quantity
            )
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

    /**
     * A soft delete, and never a hard one.
     *
     * An Owner or Administrator may remove any requirement. Everybody else
     * may remove **their own**, and only while nothing has arrived against
     * it — which is new for a Manager, who could not remove anything at all
     * before, and for a Staff account, who now has a way to withdraw a
     * requirement they raised by mistake.
     */
    suspend fun softDelete(member: Member, record: PurchaseRecord): PurchaseWriteResult {
        require(Permissions.canAddPurchase(member)) { NOT_ALLOWED_DELETE }
        return update(member, record, PurchaseAccess::canRemove) { stored, author, at ->
            PurchaseWrite.softDelete(stored, author, at)
        }
    }

    /**
     * The rest is not coming: close the requirement at what actually arrived.
     *
     * Owner, Administrator and Manager, never Staff. The new required total
     * is the **stored** receipt and is not the caller's to choose — there is
     * no quantity parameter — and the receipt fields themselves are left
     * exactly as the delivery recorded them.
     *
     * An `ABORTED` conflict is not retried here any more than anywhere else:
     * somebody else has changed this requirement since it was read, and
     * writing off a shortfall against a figure that has moved is precisely
     * what the revision counter exists to stop.
     */
    suspend fun closeShortfall(member: Member, record: PurchaseRecord): PurchaseWriteResult {
        require(Permissions.canAddPurchase(member)) { NOT_ALLOWED_SHORTFALL }
        return update(
            member = member,
            record = record,
            permits = ::mayDeliver,
            refusal = PurchaseAccess::deliveryRefusalFor
        ) { stored, author, at ->
            PurchaseWrite.closeShortfall(stored, author, at)
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
        // Only the three operations the creator rule governs pass one of
        // these. For the rest the role floor above is the whole of the
        // question of *who*, and `PurchaseWrite` answers *whether* with a
        // sentence of its own — "Already received by Omar" says far more
        // than a generic refusal would.
        permits: (Member, PurchaseRecord) -> Boolean = { _, _ -> true },
        /**
         * What to say when [permits] says no. Defaulted to the creator rule's
         * own wording, which is right for editing and removing and wrong for
         * a delivery: "only an Owner or Administrator can change it now" is
         * false of a Manager closing a shortfall.
         */
        refusal: (Member, PurchaseRecord) -> String = PurchaseAccess::refusalFor,
        plan: (PurchaseRecord, PurchaseAuthor, Long) -> PurchasePlan
    ): PurchaseWriteResult {
        val author = authorOf(member)
        val at = now()
        return store.transaction { transaction ->
            // Gone since the board last saw it. Nothing to write, and nothing
            // to complain about.
            val doc = transaction.read(record.id)
                ?: return@transaction PurchaseWriteResult.NO_CHANGE
            unreadableReceipt(member, doc)?.let { throw IllegalStateException(it) }
            val stored = doc.toPurchaseRecord()
            // Against `stored`, never against `record`. A card can be two
            // deliveries behind, and the whole of the creator rule — who
            // raised this, and has anything arrived — is a fact about the
            // document rather than about what was on screen.
            if (!permits(member, stored)) {
                throw IllegalStateException(refusal(member, stored))
            }
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

    /**
     * A stored receipt total the rules will not look at, for anybody but an
     * Owner or Administrator.
     *
     * `rcvQty` is a number everywhere this app writes it, but the PWA has
     * written strings into this collection before — `pr_received_legacy`
     * holds `"qty": "10"` — so a string receipt total is not impossible. The
     * deployed rules refuse to compare one rather than coerce it, because a
     * mixed-type comparison in a security rule raises an error and the
     * failure mode is not worth relying on.
     *
     * So the app says the same thing in a sentence instead of letting a
     * permission error come back from a write nobody could have known was
     * doomed. An Owner or Administrator rescues such a row through the
     * privileged edit, exactly as they rescue a string `qty`.
     */
    private fun unreadableReceipt(member: Member, doc: DocData): String? {
        if (Permissions.isAdmin(member)) return null
        val stored = doc.fields["rcvQty"] ?: return null
        return if (stored is Number) null else RECEIPT_NOT_NUMERIC
    }

    /**
     * Whether this person may record what arrives against **this** stored
     * requirement: the role floor, or the fact that they raised it.
     *
     * Deliberately not `PurchaseAccess.canReceive`, which also asks whether
     * anything is outstanding. That is a question about state, and state
     * refusals belong to `PurchaseWrite`, which answers them by name.
     */
    private fun mayDeliver(member: Member, stored: PurchaseRecord): Boolean =
        Permissions.canSetPurchaseStatus(member) || PurchaseAccess.isCreator(member, stored)

    private fun authorOf(member: Member): PurchaseAuthor =
        PurchaseAuthor(name = member.name.ifBlank { member.email }, uid = member.uid)

    internal companion object {
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

        /**
         * The role floor for a change, which is now "anybody who may add
         * one": a Staff account may correct the requirement they raised
         * themselves, so the role alone no longer decides. Which requirement
         * they may change is `PurchaseAccess`'s answer, given the document.
         */
        const val NOT_ALLOWED_EDIT = "Your account cannot change a requirement"

        const val NOT_ALLOWED_RECEIVE = "Your account cannot mark a requirement received"
        val NOT_ALLOWED_REOPEN = "Only $ADMINS can reopen a requirement"

        const val NOT_ALLOWED_DELETE = "Your account cannot remove a requirement"

        val RECEIPT_NOT_NUMERIC =
            "The received quantity on this requirement was not stored as a number — " +
                "only $ADMINS can correct it"

        const val NOT_ALLOWED_SHORTFALL =
            "Your account cannot close a requirement short of what was asked for"
    }
}
