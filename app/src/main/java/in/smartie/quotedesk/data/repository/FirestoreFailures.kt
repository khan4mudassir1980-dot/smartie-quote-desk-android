package `in`.smartie.quotedesk.data.repository

import com.google.firebase.firestore.FirebaseFirestoreException

/**
 * Turning a Firestore failure into something a person on a shop floor can
 * act on.
 *
 * This lives in the data layer because it is the only place that may know
 * what a `FirebaseFirestoreException` is; everything above it asks the
 * question and gets a sentence.
 *
 * Only one code is worth naming. On the Spark plan there is no billing
 * account, so an exhausted quota **fails the request** — it never charges and
 * never upgrades anything — and it fails app-wide rather than for photos
 * alone. Firestore's own wording for that is "Quota exceeded", which reads as
 * though somebody could go and pay for more. Nobody can, and the honest thing
 * is to say what actually happened and that it passes.
 */
object FirestoreFailures {

    const val QUOTA_EXHAUSTED: String =
        "Today's free Firebase limit is used up — this will work again tomorrow"

    /**
     * Two devices reached the same document, and this one lost.
     *
     * The sentence deliberately does **not** offer to retry for them. A
     * purchase write carries `rev = stored.rev + 1`, so a silent retry would
     * be a second opinion about a document somebody else has just changed —
     * exactly the double-completion the revision counter exists to stop. The
     * person looks again and decides.
     */
    const val WRITE_CONFLICT: String =
        "Somebody else changed this at the same moment — check it before trying again"

    /**
     * The rules said no.
     *
     * Reached only when the app believed the write was allowed, so it is
     * either a stale idea of somebody's role or a document in a shape the
     * rules refuse. Either way it is not something to retry blindly.
     */
    const val REFUSED: String =
        "The server refused this change — reload the list and check it"

    /**
     * Whether [error] is Firestore refusing because a quota ran out.
     *
     * Unwraps the cause chain: a failure raised inside a transaction reaches
     * the caller wrapped, and the code is on the original.
     */
    fun isQuotaExhausted(error: Throwable?): Boolean =
        codeOf(error) == FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED

    /**
     * Whether [error] is contention rather than a mistake.
     *
     * `ABORTED` is what a transaction raises when it has retried and still
     * lost the document; `FAILED_PRECONDITION` is the same story told by a
     * check that could not hold. Neither means the person did anything wrong.
     */
    fun isWriteConflict(error: Throwable?): Boolean = when (codeOf(error)) {
        FirebaseFirestoreException.Code.ABORTED,
        FirebaseFirestoreException.Code.FAILED_PRECONDITION -> true
        else -> false
    }

    /** Whether the security rules refused the write outright. */
    fun isRefused(error: Throwable?): Boolean =
        codeOf(error) == FirebaseFirestoreException.Code.PERMISSION_DENIED

    /**
     * [QUOTA_EXHAUSTED] when that is what happened, else null.
     *
     * Left alone deliberately: Our Stock reads this and says its own thing
     * about everything else, and widening it here would change that screen's
     * wording without anybody asking. A caller that wants the conflict and
     * refusal sentences asks for them by name.
     */
    fun message(error: Throwable?): String? =
        if (isQuotaExhausted(error)) QUOTA_EXHAUSTED else null

    /** The first Firestore code in the cause chain, or null if there is none. */
    private fun codeOf(error: Throwable?): FirebaseFirestoreException.Code? {
        var current = error
        var depth = 0
        while (current != null && depth < MAX_DEPTH) {
            if (current is FirebaseFirestoreException) return current.code
            current = current.cause.takeIf { it !== current }
            depth++
        }
        return null
    }

    /** A cause chain should not be deep; this stops a cyclic one dead. */
    private const val MAX_DEPTH = 8
}
