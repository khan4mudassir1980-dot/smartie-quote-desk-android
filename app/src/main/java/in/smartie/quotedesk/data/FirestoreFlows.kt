package `in`.smartie.quotedesk.data

import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.Query
import `in`.smartie.quotedesk.data.mapping.DocData
import `in`.smartie.quotedesk.data.mapping.toDocData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.retryWhen
import kotlin.math.min
import kotlin.math.pow

/**
 * Snapshot listeners that cannot crash the app — and cannot quietly die
 * either.
 *
 * The beta closed the flow with the Firestore error, which surfaced inside an
 * unguarded `viewModelScope.launch` and took the process down when a listener
 * lost permission during sign-out (audit C2). The fix was to complete the
 * flow instead, silently, for `PERMISSION_DENIED`, `CANCELLED` and
 * `UNAUTHENTICATED`.
 *
 * **That fix was worse than the problem it solved.** A `stateIn` whose
 * upstream *completes* is never collected again — `SharingStarted` decides
 * when to start a flow, not when to restart one that finished — so one
 * transient refusal froze a screen's data at its last value for the life of
 * the process, with nothing reported and nothing retried. On the Purchase tab
 * that looked like a requirement being added successfully and never
 * appearing until the app was closed and reopened.
 *
 * So an error is an **error** here now, always, and never a quiet ending.
 * Surviving it is [retryingListener]'s job, where it can be seen, delayed and
 * reported rather than hidden.
 */
fun Query.docDataFlow(): Flow<List<DocData>> = callbackFlow {
    val registration = addSnapshotListener { snapshot, error ->
        when {
            error != null -> close(error)
            snapshot != null -> trySend(snapshot.documents.map { it.toDocData() })
        }
    }
    awaitClose { registration.remove() }
}

fun DocumentReference.docDataFlow(): Flow<DocData?> = callbackFlow {
    val registration = addSnapshotListener { snapshot, error ->
        when {
            error != null -> close(error)
            // A removed document emits null so callers can self-heal rather
            // than sitting on Loading for ever (audit A7/C4).
            snapshot != null -> trySend(if (snapshot.exists()) snapshot.toDocData() else null)
        }
    }
    awaitClose { registration.remove() }
}

/**
 * How long to wait before attaching a listener again.
 *
 * Doubling, and capped. A refusal that is going to clear — a token being
 * refreshed, a moment of no signal — clears inside the first second or two;
 * one that is not going to clear must not be asked about every second for the
 * rest of the day, on a plan with a fixed daily quota.
 */
data class ListenerRetry(
    val firstDelayMillis: Long = 1_000L,
    val maxDelayMillis: Long = 60_000L,
    val factor: Double = 2.0
) {
    /** The wait before attempt number [attempt], counting the first as 0. */
    fun delayFor(attempt: Long): Long {
        val grown = firstDelayMillis.toDouble() * factor.pow(attempt.coerceAtLeast(0L).toDouble())
        return min(grown, maxDelayMillis.toDouble()).toLong()
    }
}

/**
 * Attach again when a listener fails, for as long as anybody is watching.
 *
 * **It never gives up and it never swallows.** Every failure is handed to
 * [onRetry] before the wait, so the caller can report it, count it and tell
 * somebody when it stops looking like a blip — and the value that was already
 * on screen is left alone, because a list that failed to refresh is not an
 * empty list.
 *
 * Cancellation is not a failure: a `CancellationException` ends the flow at
 * once rather than being retried, and the [delay] between attempts is itself
 * cancellable, so a cancelled scope leaves nothing running behind it.
 */
fun <T> Flow<T>.retryingListener(
    retry: ListenerRetry = ListenerRetry(),
    onRetry: suspend (cause: Throwable, attempt: Long, waitMillis: Long) -> Unit =
        { _, _, _ -> }
): Flow<T> = retryWhen { cause, attempt ->
    if (cause is CancellationException) return@retryWhen false
    val wait = retry.delayFor(attempt)
    onRetry(cause, attempt, wait)
    delay(wait)
    true
}
