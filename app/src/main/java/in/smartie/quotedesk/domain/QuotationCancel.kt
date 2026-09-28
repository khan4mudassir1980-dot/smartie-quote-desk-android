package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.model.QuotationRecord

/** What cancelling comes to, decided before anything reaches Firestore. */
sealed interface CancelPlan {
    /** V8C4's three keys, and nothing else. */
    data class Write(val quotationId: String, val number: String, val fields: Map<String, Any?>) : CancelPlan

    /** Nothing is written; [message] is for the person. */
    data class Refused(val message: String) : CancelPlan
}

/**
 * Cancelling a finalised quotation.
 *
 * **Who: the creator, and an Owner or Administrator on anyone's** — the
 * Owner's decision of 2026-09-28, which replaced the 2026-09-25 ruling that
 * only an Owner or Administrator might (V8C4's `if(!admin)`). Creator cancel
 * is therefore **new behaviour**, not a port.
 *
 * **What: V8C4's write, exactly** — `{status: "Cancelled", cancelledBy,
 * cancelledAt}`, with `cancelledBy` a **name** and `cancelledAt` a
 * **millisecond number**, as V8C4's `currentUserName()` and `Date.now()` fill
 * them (the advisor's reading, Q4). The rule accepts those three keys and no
 * other, and never a second cancel.
 *
 * **How: remote-first, never V8C4's local-first.** V8C4 marks the quotation
 * cancelled on the device, then writes, and on failure says "Cancelled here,
 * but not for the team". Here the write is a transaction — which reaches no
 * listener, this device's included, until the server accepts it — and the
 * list turns Cancelled only when the listener brings it. There is no state in
 * which this device believes something the team does not.
 *
 * The texts the person sees on the way are V8C4's: [confirmText] and
 * [cancelledText]. The refusals are this app's own.
 */
object QuotationCancel {

    const val STATUS = "Cancelled"

    /** V8C4's `currentUserName()` falls back to this when there is no name. */
    const val UNNAMED = "unnamed"

    /** V8C4's confirm, word for word (6834-6840). */
    fun confirmText(number: String): String =
        "Cancel $number?\n\nThe record is kept and marked cancelled. The number is never released or re-used."

    /** V8C4's toast, word for word. */
    fun cancelledText(number: String): String = "$number cancelled"

    fun alreadyCancelled(number: String, by: String): String =
        "$number was already cancelled by ${by.ifBlank { "somebody else" }}"

    fun gone(number: String): String = "$number could not be found"

    const val NOT_YOURS =
        "Only the person who issued this quotation, an Owner or an Administrator can cancel it"

    /**
     * Cancelling [stored] — the quotation read **inside the transaction**,
     * null when it has gone — as [member], at [at].
     */
    fun plan(member: Member, number: String, stored: QuotationRecord?, at: Long): CancelPlan = when {
        stored == null -> CancelPlan.Refused(gone(number))
        stored.status.equals(STATUS, ignoreCase = true) ->
            CancelPlan.Refused(alreadyCancelled(stored.number, stored.cancelledBy))
        !Permissions.canCancelQuotation(member, stored) -> CancelPlan.Refused(NOT_YOURS)
        else -> CancelPlan.Write(
            quotationId = stored.id,
            number = stored.number,
            fields = mapOf(
                "status" to STATUS,
                "cancelledBy" to member.name.ifBlank { UNNAMED },
                "cancelledAt" to at
            )
        )
    }
}
