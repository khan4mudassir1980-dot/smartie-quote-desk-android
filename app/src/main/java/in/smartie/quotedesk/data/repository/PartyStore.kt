package `in`.smartie.quotedesk.data.repository

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import `in`.smartie.quotedesk.data.mapping.DocData
import `in`.smartie.quotedesk.data.mapping.toDocData
import kotlinx.coroutines.tasks.await

/**
 * The slice of Firestore the party writer uses.
 *
 * It exists for the same reason [PurchaseStore] does: the create path has to
 * read the document id before it writes to it, and the interesting case — a
 * **retry** landing on an id that is now taken — is one a real emulator will
 * not reproduce on demand.
 */
interface PartyStore {
    /**
     * Runs [body] inside one Firestore transaction and returns its result.
     *
     * Firestore may run [body] more than once under contention, so nothing
     * that has to stay stable across attempts is generated inside it.
     */
    suspend fun <T> transaction(body: (PartyTransaction) -> T): T
}

/**
 * Reads and writes available inside one party transaction.
 *
 * **No delete.** The rules allow an Administrator to delete a customer, but
 * nothing in the app offers it: a quotation's `partyId` points at the document
 * for as long as the quotation exists, and deleting one would leave issued
 * paperwork pointing at nothing. Archiving is the answer, and leaving delete
 * out makes that structural rather than a comment.
 */
interface PartyTransaction {
    fun read(docId: String): DocData?
    fun write(docId: String, data: Map<String, Any?>, merge: Boolean)
}

/** The real thing. */
class FirestorePartyStore(private val firestore: FirebaseFirestore) : PartyStore {

    override suspend fun <T> transaction(body: (PartyTransaction) -> T): T =
        firestore.runTransaction<T> { transaction ->
            body(
                object : PartyTransaction {
                    override fun read(docId: String): DocData? {
                        val snapshot =
                            transaction.get(firestore.collection(CUSTOMERS).document(docId))
                        return if (snapshot.exists()) snapshot.toDocData() else null
                    }

                    override fun write(docId: String, data: Map<String, Any?>, merge: Boolean) {
                        val reference = firestore.collection(CUSTOMERS).document(docId)
                        val resolved = data.filterValues { it != null }.mapValues { it.value!! }
                        if (merge) transaction.set(reference, resolved, SetOptions.merge())
                        else transaction.set(reference, resolved)
                    }
                }
            )
        }.await()

    private companion object {
        /** V8C4's collection name. The app calls them parties; the wire does not. */
        const val CUSTOMERS = "customers"
    }
}
