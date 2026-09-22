package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.model.PurchaseRecord

/**
 * Who may do what to **one particular requirement**.
 *
 * `Permissions` answers what a role may do at all; this answers what this
 * person may do to the document in front of them, which is not the same
 * question and cannot be answered without the document. Two things make it
 * different:
 *
 * 1. **The person who raised a requirement may correct it.** A Manager or a
 *    Staff account may fix the name, the note, the quantity or the urgency of
 *    their own requirement, or take it off the list altogether, for as long as
 *    nothing has been delivered against it. A Staff account can do nothing to
 *    anybody else's.
 * 2. **A delivery closes the record to corrections.** The moment any quantity
 *    arrives the requirement stops being anybody's to tidy up: nobody below
 *    an Administrator may rename it, change its quantity or take it off the
 *    list. What it does **not** close is the delivery itself — whoever could
 *    receive against it still can, including its creator, because a
 *    requirement you raised is one you should be able to finish.
 *
 * Pure, and taking the **stored** record rather than a member and a role, so
 * the same function decides what a card offers and what the repository allows
 * against the document it read inside its transaction. A screen's copy of a
 * requirement can be two deliveries old; that is the whole reason this is
 * evaluated twice.
 *
 * ## Ownership is a uid and nothing else
 *
 * [isCreator] compares `member.uid` with `record.byUid`. Never a name, never
 * an email — two people called Ravi must not inherit each other's
 * permissions, and a display name is not an identity. A requirement whose
 * `byUid` is blank belongs to nobody here: the PWA wrote rows without one,
 * and a row whose author cannot be proved grants no creator rights to
 * anybody. It fails closed.
 *
 * Titles, for reading this file: `Role.STAFF` is displayed **Manager** and
 * `Role.WORKER` is displayed **Staff**. The stored values are the PWA's and
 * are not this batch's to change. See `RoleTitles`.
 */
object PurchaseAccess {

    /**
     * Nothing has been delivered against this requirement, so it is still
     * the sort of thing somebody can tidy up.
     *
     * The four `rcv*` fields are checked by **presence**, not by value: the
     * app writes all four together on a receipt and removes all four together
     * on a reopen, so a row carrying any of them has a delivery recorded
     * against it whatever the number says.
     *
     * A reopened requirement is untouched again, deliberately. Reopen is an
     * Owner-and-Administrator action that removes the receipt outright, and a
     * row that has been reopened is meant to be as good as new. Recording
     * "was ever received" would need a field nobody stores; see
     * `docs/N4.2-plan.md`.
     */
    fun isUntouched(record: PurchaseRecord): Boolean =
        !record.deleted &&
            !record.isClosed &&
            record.status == PurchaseWrite.STATUS_NEEDED &&
            record.receivedTotal == 0.0 &&
            !record.hasReceipt

    /**
     * This person raised this requirement.
     *
     * Both sides must be a real uid. A blank `member.uid` is a session that
     * has not resolved, and a blank `byUid` is a row from the PWA; neither
     * may match the other, and `"" == ""` would hand every legacy requirement
     * to whoever happened to be signed in.
     */
    fun isCreator(member: Member, record: PurchaseRecord): Boolean =
        member.active &&
            member.uid.isNotBlank() &&
            record.byUid.isNotBlank() &&
            member.uid == record.byUid

    /** The creator's own window: their requirement, before any delivery. */
    fun canSelfServe(member: Member, record: PurchaseRecord): Boolean =
        Permissions.canAddPurchase(member) && isCreator(member, record) && isUntouched(record)

    // --- the six operations, per record ---------------------------------------

    /**
     * Correct the name, the note or the required quantity.
     *
     * An Owner or Administrator always may — including on a received row,
     * which is the privileged correction the lock leaves them. A Manager may
     * while the row is untouched, on anybody's requirement, which is the
     * permission they have always had minus the part that reached a delivery.
     * A Staff account may only on their own untouched row.
     */
    fun canEdit(member: Member, record: PurchaseRecord): Boolean = when {
        record.deleted -> false
        Permissions.isAdmin(member) -> true
        Permissions.canEditPurchase(member) -> isUntouched(record)
        else -> canSelfServe(member, record)
    }

    /** The urgency follows the edit exactly; it is an edit of one field. */
    fun canSetUrgency(member: Member, record: PurchaseRecord): Boolean = canEdit(member, record)

    /**
     * Take it off the list. Always the soft delete, never a hard one.
     *
     * An Owner or Administrator may remove any requirement. Everybody else
     * may remove **their own**, and only while nothing has arrived — which is
     * new for a Manager, who could not remove anything before.
     */
    fun canRemove(member: Member, record: PurchaseRecord): Boolean = when {
        record.deleted -> false
        Permissions.isAdmin(member) -> true
        else -> canSelfServe(member, record)
    }

    /**
     * Record a delivery.
     *
     * Owner, Administrator and Manager on anybody's requirement — and **the
     * person who raised it**, on theirs. That last part is new: a Staff
     * account could raise a requirement and then watch somebody else record
     * the delivery against it, which is the wrong way round on a shop floor.
     * The person who noticed the shortage is usually the person standing in
     * front of the van.
     *
     * Still nothing on anybody else's requirement, and still nothing on a row
     * whose author cannot be proved.
     */
    fun canReceive(member: Member, record: PurchaseRecord): Boolean =
        canDeliver(member, record) && record.isOpen && record.remaining > 0.0

    /** Owner and Administrator alone, as before, and now the rules agree. */
    fun canReopen(member: Member, record: PurchaseRecord): Boolean =
        Permissions.canReopenPurchase(member) && !record.deleted && record.isClosed

    /**
     * Write off what is not coming.
     *
     * Only on a requirement that is **partly** delivered: something has
     * arrived, and not all of it. A requirement nobody has delivered against
     * is cancelled by removing it, and one that is complete closes itself.
     *
     * The same people who may receive, for the same reason: whoever can
     * record the last delivery should be able to say there will not be one.
     * Otherwise the lock leaves somebody looking at a finished requirement
     * they cannot clear.
     */
    fun canCloseShortfall(member: Member, record: PurchaseRecord): Boolean =
        canDeliver(member, record) &&
            record.isOpen &&
            record.isPartlyReceived &&
            record.remaining > 0.0

    /**
     * Who may record what arrives against [record] at all.
     *
     * The role floor, **or** ownership — and ownership is checked against the
     * record, so it cannot be widened by a role. One definition shared by
     * [canReceive] and [canCloseShortfall], so the two cannot drift.
     *
     * Note what this does **not** consult: whether the row is untouched. The
     * post-receipt lock takes away editing, renaming and removing, and
     * deliberately leaves delivery alone — a creator must be able to finish
     * the requirement they raised, they just may not rewrite it afterwards.
     */
    private fun canDeliver(member: Member, record: PurchaseRecord): Boolean =
        !record.deleted &&
            (Permissions.canSetPurchaseStatus(member) || isCreator(member, record))

    // --- why not, in words ----------------------------------------------------

    /**
     * Why this person may not change this requirement.
     *
     * Named by role title, because "not allowed" tells somebody standing on a
     * shop floor nothing about what to do next — and the answer is usually
     * "ask an Administrator" or "it has already started arriving".
     */
    fun refusalFor(member: Member, record: PurchaseRecord): String = when {
        record.deleted -> ALREADY_REMOVED
        !isUntouched(record) -> LOCKED_BY_RECEIPT
        record.byUid.isBlank() -> NO_KNOWN_CREATOR
        !isCreator(member, record) -> SOMEBODY_ELSES
        else -> NOT_ALLOWED
    }

    /**
     * Why this person may not record what arrives against this requirement.
     *
     * Separate from [refusalFor] on purpose. That one is the creator rule's
     * wording — "only an Owner or Administrator can change it now" — which is
     * right about editing a received row and **false** about delivering
     * against one, where a Manager may and its creator may.
     */
    fun deliveryRefusalFor(member: Member, record: PurchaseRecord): String = when {
        record.deleted -> ALREADY_REMOVED
        record.byUid.isBlank() -> NO_KNOWN_CREATOR
        else -> NOT_YOURS_TO_DELIVER
    }

    val ADMINS: String = RoleTitles.anyOf(Role.OWNER, Role.ADMIN)

    private val DELIVERERS: String = RoleTitles.anyOf(Role.OWNER, Role.ADMIN, Role.STAFF)

    val NOT_YOURS_TO_DELIVER: String =
        "Only $DELIVERERS, or the person who raised it, can record what arrives " +
            "against this requirement"

    /**
     * The planner's own sentence, not a second copy of it. A person who tries
     * to change a removed requirement should read the same thing whether the
     * refusal came from here or from `PurchaseWrite`.
     */
    const val ALREADY_REMOVED: String = PurchaseWrite.ALREADY_DELETED

    val LOCKED_BY_RECEIPT: String =
        "Something has already arrived against this — only $ADMINS can change it now"

    const val NO_KNOWN_CREATOR: String =
        "This requirement does not record who raised it, so it cannot be corrected here"

    const val SOMEBODY_ELSES: String =
        "Only the person who raised this requirement can change it"

    const val NOT_ALLOWED: String = "Your account cannot change this requirement"
}
