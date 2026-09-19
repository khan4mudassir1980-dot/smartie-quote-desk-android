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
     * Whether [error] is Firestore refusing because a quota ran out.
     *
     * Unwraps the cause chain: a failure raised inside a transaction reaches
     * the caller wrapped, and the code is on the original.
     */
    fun isQuotaExhausted(error: Throwable?): Boolean {
        var current = error
        var depth = 0
        while (current != null && depth < MAX_DEPTH) {
            if (current is FirebaseFirestoreException &&
                current.code == FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED
            ) {
                return true
            }
            current = current.cause.takeIf { it !== current }
            depth++
        }
        return false
    }

    /** [QUOTA_EXHAUSTED] when that is what happened, else null. */
    fun message(error: Throwable?): String? =
        if (isQuotaExhausted(error)) QUOTA_EXHAUSTED else null

    /** A cause chain should not be deep; this stops a cyclic one dead. */
    private const val MAX_DEPTH = 8
}
