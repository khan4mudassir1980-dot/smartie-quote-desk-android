package `in`.smartie.quotedesk.data

import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import `in`.smartie.quotedesk.data.mapping.DocData
import `in`.smartie.quotedesk.data.mapping.toDocData
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Snapshot listeners that cannot crash the app.
 *
 * The beta closed the flow with the Firestore error, which then surfaced
 * inside an unguarded `viewModelScope.launch` and took the process down when
 * a listener lost permission during sign-out (audit C2). Here a listener that
 * loses access simply completes, and the registration is always removed when
 * the collector goes away.
 */
private val benignCodes = setOf(
    FirebaseFirestoreException.Code.PERMISSION_DENIED,
    FirebaseFirestoreException.Code.CANCELLED,
    FirebaseFirestoreException.Code.UNAUTHENTICATED
)

fun Query.docDataFlow(): Flow<List<DocData>> = callbackFlow {
    val registration = addSnapshotListener { snapshot, error ->
        when {
            error != null -> if (error.code in benignCodes) close() else close(error)
            snapshot != null -> trySend(snapshot.documents.map { it.toDocData() })
        }
    }
    awaitClose { registration.remove() }
}

fun DocumentReference.docDataFlow(): Flow<DocData?> = callbackFlow {
    val registration = addSnapshotListener { snapshot, error ->
        when {
            error != null -> if (error.code in benignCodes) close() else close(error)
            // A removed document emits null so callers can self-heal rather
            // than sitting on Loading for ever (audit A7/C4).
            snapshot != null -> trySend(if (snapshot.exists()) snapshot.toDocData() else null)
        }
    }
    awaitClose { registration.remove() }
}
