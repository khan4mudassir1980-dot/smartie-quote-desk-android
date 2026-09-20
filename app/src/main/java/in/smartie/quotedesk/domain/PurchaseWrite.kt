package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.model.PurchaseRecord
import `in`.smartie.quotedesk.data.model.UrgencyV2

/**
 * Stand-in for `FieldValue.delete()`, the mirror of [ServerTimestamp].
 *
 * Reopening a requirement has to **remove** `rcvQty`, `rcvBy`, `rcvUid` and
 * `rcvAt`, not neutralise them. A null will not do it — the Firestore store
 * drops nulls rather than writing them — and a zero is worse than useless:
 * `optionalDouble("rcvQty")` would read `0.0` back and the card would say
 * "0 in" about a requirement nobody has received.
 *
 * The planner below is ordinary Kotlin with no Firebase in it, so the store
 * swaps this marker for the real sentinel on its way out, exactly as it does
 * for the timestamp. A test asserts the marker, which is stronger than
 * asserting an absence.
 */
object DeleteField

/** Who a write is attributed to; the rules demand `byUid == request.auth.uid`. */
data class PurchaseAuthor(val name: String, val uid: String)

/** What a purchase write comes to, decided before anything reaches Firestore. */
sealed interface PurchasePlan {
    /** The document to write. */
    data class Write(
        val docId: String,
        val data: Map<String, Any?>,
        val merge: Boolean = true
    ) : PurchasePlan

    /** Nothing changed. Write nothing at all, and say nothing happened. */
    data object NoChange : PurchasePlan

    /** The write must not be attempted; [message] is for the person. */
    data class Refused(val message: String) : PurchasePlan
}

/**
 * A requirement somebody has typed but not yet saved.
 *
 * [key] is the logical stock key a requirement was raised from, and it is
 * **empty for every requirement N4 creates**. It exists now so that raising a
 * requirement from an out-of-stock row — deferred to N4.1 — is a factory
 * function and one button rather than a schema change on live data.
 */
data class PurchaseDraft(
    val name: String,
    val quantity: Double,
    val urgency: UrgencyV2 = UrgencyV2.NORMAL,
    val note: String = "",
    val key: String = ""
) {
    /** Why this requirement may not be created, or null when it may. */
    fun refusal(): String? = when {
        name.isBlank() -> PurchaseWrite.NO_NAME
        quantity <= 0.0 -> PurchaseWrite.NOT_POSITIVE
        else -> null
    }
}

/**
 * What each purchase write puts on the wire.
 *
 * Every field name here is the PWA's own, taken from the reader that has
 * always parsed them (`OperationsReaders.toPurchaseRecord`). Nothing is
 * invented: a field the V8C4 PWA does not know would be a field it cannot
 * show.
 *
 * Pure on purpose. The **stored** record arrives as a parameter, read inside
 * the caller's transaction, so every branch below is unit-tested without
 * Firebase — and so no decision is ever taken against the figure that
 * happened to be on the screen.
 *
 * ## The five ways the v9 rules silently refuse a write
 *
 * All five are solved in [base], which every update is built from. They are
 * written out because each is a way to produce a refusal that looks like a
 * permission problem and is not:
 *
 * 1. **`qty` must be a number greater than zero.** On an update
 *    `request.resource.data` is the *merged post-state*, so the check lands on
 *    the **stored** value. A V8C4 row holding `"10"` as a string is therefore
 *    unupdatable until a write rewrites it numerically — which also heals it
 *    for good.
 * 2. **`id` must equal the document id**, and that is merged post-state too.
 *    A row with no `id` field at all exists — the reader defaults it to the
 *    document id — and is equally unupdatable until one is written.
 * 3. **No update may carry a `del` key.** `touched()` reports keys *added*, so
 *    writing `del: false` onto a row that has no `del` field adds it and trips
 *    the guard that keeps soft delete an Administrator's alone. `del` appears
 *    only in [create] and in [softDelete].
 * 4. **`updated` is epoch milliseconds, never a server timestamp.** The rules
 *    want `updated is number`; the sentinel is not one. `serverAt` carries the
 *    server's own clock alongside it.
 * 5. **`rev` is mandatory the moment a row has one**, though `revOk()` reads
 *    as though it were optional. Its `keys().hasAny(['rev'])` test sees the
 *    merged post-state too, so a row already holding `rev: 1` still holds it
 *    after an update that omits `rev`, and `1 == 1 + 1` refuses the write. The
 *    tolerance covers only a row that has **never** carried a `rev`. Every
 *    update here sends `stored.rev + 1` — which the rule requires, and which
 *    is what stops two devices completing the same requirement.
 *
 * ## What is deliberately absent
 *
 * There is **no generic status setter and no cancel**. A status is moved only
 * by the operation that owns it — [markReceived] sets `Received`, [reopen]
 * returns `Needed` — so no caller can invent a state nobody designed. A
 * `Cancelled` requirement written by the PWA still reads and displays
 * correctly; nothing here writes one.
 */
object PurchaseWrite {

    /** The prefix on a requirement's id, as `mv_` is on a movement. */
    const val ID_PREFIX: String = "pr_"

    /** The only status a requirement may be created in; the rules insist. */
    const val STATUS_NEEDED: String = "Needed"

    /** Set by [markReceived], and by nothing else. */
    const val STATUS_RECEIVED: String = "Received"

    const val NO_NAME: String = "Type what is needed"
    const val NOT_POSITIVE: String = "The quantity must be more than zero"
    const val ALREADY_EXISTS: String = "That requirement was already added"
    const val ALREADY_DELETED: String = "This requirement has been removed"

    /**
     * A requirement whose stored `qty` is missing or unreadable.
     *
     * The rules refuse every update to such a row until the quantity is a
     * number, so saying so is better than letting a permission error surface.
     * [edit] is the way out, and is deliberately the one path that does not
     * refuse here.
     */
    const val UNUSABLE_QUANTITY: String =
        "This requirement has no usable quantity — edit it and set one first"

    /** Named, because "already received" without the name invites a second try. */
    fun alreadyReceived(by: String): String =
        if (by.isBlank()) "This requirement has already been received"
        else "Already received by $by"

    // --- creating -----------------------------------------------------------

    /**
     * A new requirement, in the only status the rules allow one to start in.
     *
     * [alreadyExists] is not paranoia about the id generator. A `set` on an id
     * that is already taken is evaluated by the rules as an **update**, which
     * anyone above a Worker may do — so a collision would quietly overwrite
     * somebody else's requirement instead of failing.
     */
    fun create(
        id: String,
        draft: PurchaseDraft,
        author: PurchaseAuthor,
        at: Long,
        alreadyExists: Boolean = false
    ): PurchasePlan {
        draft.refusal()?.let { return PurchasePlan.Refused(it) }
        if (alreadyExists) return PurchasePlan.Refused(ALREADY_EXISTS)
        return PurchasePlan.Write(
            docId = id,
            // Written whole rather than merged: a new requirement has nothing
            // underneath it to preserve.
            merge = false,
            data = buildMap {
                put("id", id)
                put("name", draft.name.trim())
                put("qty", draft.quantity)
                put("urgency", draft.urgency.wireValue)
                put("status", STATUS_NEEDED)
                put("note", draft.note.trim())
                put("by", author.name)
                put("byUid", author.uid)
                put("t", at)
                put("updated", at)
                put("rev", 1)
                // The create rule allows both as `false`, and writing them
                // makes a native requirement unambiguous to every reader.
                put("received", false)
                put("del", false)
                put("serverAt", ServerTimestamp)
                if (draft.key.isNotBlank()) put("key", draft.key)
            }
        )
    }

    // --- changing one ---------------------------------------------------------

    /**
     * The Edit sheet: what is needed, how many, how urgently, and why.
     *
     * **The rescue path.** Every other operation refuses a requirement whose
     * stored quantity is unusable; this one accepts it, because writing a
     * usable quantity is the only way to make the row writable again.
     */
    fun edit(
        stored: PurchaseRecord,
        name: String,
        quantity: Double,
        urgency: UrgencyV2,
        note: String,
        author: PurchaseAuthor,
        at: Long
    ): PurchasePlan {
        if (stored.deleted) return PurchasePlan.Refused(ALREADY_DELETED)
        val trimmedName = name.trim()
        val trimmedNote = note.trim()
        if (trimmedName.isBlank()) return PurchasePlan.Refused(NO_NAME)
        if (quantity <= 0.0) return PurchasePlan.Refused(NOT_POSITIVE)
        val unchanged = trimmedName == stored.name &&
            quantity == stored.quantity &&
            urgency == stored.urgency &&
            trimmedNote == stored.note
        if (unchanged) return PurchasePlan.NoChange
        return PurchasePlan.Write(
            docId = stored.id,
            data = base(stored, quantity, author, at) + mapOf(
                "name" to trimmedName,
                "urgency" to urgency.wireValue,
                "note" to trimmedNote
            )
        )
    }

    /** Just the urgency, from the card, without opening Edit. */
    fun setUrgency(
        stored: PurchaseRecord,
        urgency: UrgencyV2,
        author: PurchaseAuthor,
        at: Long
    ): PurchasePlan {
        if (stored.deleted) return PurchasePlan.Refused(ALREADY_DELETED)
        if (urgency == stored.urgency) return PurchasePlan.NoChange
        usableQuantity(stored)?.let { return it }
        return PurchasePlan.Write(
            docId = stored.id,
            data = base(stored, stored.quantity, author, at) +
                mapOf("urgency" to urgency.wireValue)
        )
    }

    // --- closing and reopening -----------------------------------------------

    /**
     * It arrived. The requirement leaves the active list and keeps who
     * received it, how many, and when.
     */
    fun markReceived(
        stored: PurchaseRecord,
        receivedQuantity: Double,
        author: PurchaseAuthor,
        at: Long
    ): PurchasePlan {
        if (stored.deleted) return PurchasePlan.Refused(ALREADY_DELETED)
        // A second device that was still showing it open gets a sentence
        // rather than a stale-revision error it cannot act on.
        if (stored.isClosed) return PurchasePlan.Refused(alreadyReceived(stored.receivedBy))
        if (receivedQuantity <= 0.0) return PurchasePlan.Refused(NOT_POSITIVE)
        usableQuantity(stored)?.let { return it }
        return PurchasePlan.Write(
            docId = stored.id,
            data = base(stored, stored.quantity, author, at) + mapOf(
                "status" to STATUS_RECEIVED,
                "received" to true,
                "rcvQty" to receivedQuantity,
                "rcvBy" to author.name,
                "rcvUid" to author.uid,
                "rcvAt" to at
            )
        )
    }

    /**
     * Back to the active list, as though it had never been received.
     *
     * The four `rcv*` fields are **removed**, not blanked — see [DeleteField].
     * A requirement that is already open is left alone rather than rewritten,
     * so a double tap cannot burn a revision.
     *
     * Who may do this is an Owner-and-Administrator decision, and it is the
     * one purchase restriction the rules cannot express: a reopen is an
     * ordinary update, which Staff may perform. `Permissions` holds it.
     */
    fun reopen(
        stored: PurchaseRecord,
        author: PurchaseAuthor,
        at: Long
    ): PurchasePlan {
        if (stored.deleted) return PurchasePlan.Refused(ALREADY_DELETED)
        if (stored.isOpen) return PurchasePlan.NoChange
        usableQuantity(stored)?.let { return it }
        return PurchasePlan.Write(
            docId = stored.id,
            data = base(stored, stored.quantity, author, at) + mapOf(
                "status" to STATUS_NEEDED,
                "received" to false,
                "rcvQty" to DeleteField,
                "rcvBy" to DeleteField,
                "rcvUid" to DeleteField,
                "rcvAt" to DeleteField
            )
        )
    }

    // --- removing -------------------------------------------------------------

    /**
     * A soft delete, and the only place `del` is ever written outside
     * [create].
     *
     * There is no hard delete anywhere in this app, and the rules refuse one
     * to everybody: a document removed outright reappears the moment a PWA
     * device syncs.
     */
    fun softDelete(
        stored: PurchaseRecord,
        author: PurchaseAuthor,
        at: Long
    ): PurchasePlan {
        if (stored.deleted) return PurchasePlan.NoChange
        usableQuantity(stored)?.let { return it }
        return PurchasePlan.Write(
            docId = stored.id,
            data = base(stored, stored.quantity, author, at) + mapOf(
                "del" to true,
                "deletedBy" to author.uid
            )
        )
    }

    // --- the shape every update shares ----------------------------------------

    /**
     * The fields on **every** update, and the whole of traps 1, 2, 4 and 5.
     *
     * `id` and `qty` are re-asserted rather than left to the stored document,
     * because the rules check the merged post-state and a V8C4 row may hold
     * neither in a usable form. `updated` is epoch milliseconds because the
     * rules want a number; `serverAt` carries the server's clock beside it.
     *
     * **No `del` key appears here**, which is trap 3: adding one to a row that
     * lacks it would trip the guard reserving soft delete for an
     * Administrator, and refuse an ordinary Manager's save.
     */
    private fun base(
        stored: PurchaseRecord,
        quantity: Double,
        author: PurchaseAuthor,
        at: Long
    ): Map<String, Any?> = mapOf(
        "id" to stored.id,
        "qty" to quantity,
        "updated" to at,
        "rev" to stored.revision + 1,
        "upBy" to author.name,
        "upUid" to author.uid,
        "serverAt" to ServerTimestamp
    )

    /** [UNUSABLE_QUANTITY] when the stored `qty` cannot satisfy the rules. */
    private fun usableQuantity(stored: PurchaseRecord): PurchasePlan.Refused? =
        if (stored.quantity > 0.0) null else PurchasePlan.Refused(UNUSABLE_QUANTITY)
}
