package `in`.smartie.quotedesk.data.repository

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import `in`.smartie.quotedesk.domain.ServerTimestamp
import kotlinx.coroutines.tasks.await

/**
 * The slice of Firestore the stock writer uses.
 *
 * It exists so the transaction contract in [StockWriteRepository] can be
 * driven by a test — including the part that only shows up on a **retry**,
 * which is precisely the part a real emulator will not reproduce on demand.
 */
interface StockStore {
    /**
     * Runs [body] inside one Firestore transaction and returns its result.
     *
     * Firestore may run [body] **more than once** under contention.
     * Implementations must preserve that, and callers must not generate
     * anything inside it that has to stay stable across attempts.
     */
    suspend fun <T> transaction(body: (StockTransaction) -> T): T
}

/** Reads and writes available inside one transaction. */
interface StockTransaction {
    /** The stored fields of `stock/{docId}`, or null when it does not exist. */
    fun readStock(docId: String): Map<String, Any?>?

    fun writeStock(docId: String, data: Map<String, Any?>, merge: Boolean)

    fun writeMovement(docId: String, data: Map<String, Any?>)
}

/**
 * The real thing.
 *
 * All reads happen before all writes, which Firestore requires, because the
 * planner reads the stored quantity and only then decides what to write.
 * [ServerTimestamp] is swapped for the real sentinel here, so nothing above
 * this file has to import Firebase.
 */
class FirestoreStockStore(private val firestore: FirebaseFirestore) : StockStore {

    override suspend fun <T> transaction(body: (StockTransaction) -> T): T =
        firestore.runTransaction { transaction ->
            body(
                object : StockTransaction {
                    override fun readStock(docId: String): Map<String, Any?>? {
                        val snapshot = transaction.get(firestore.collection(STOCK).document(docId))
                        return if (snapshot.exists()) snapshot.data else null
                    }

                    override fun writeStock(docId: String, data: Map<String, Any?>, merge: Boolean) {
                        val reference = firestore.collection(STOCK).document(docId)
                        val resolved = resolve(data)
                        if (merge) transaction.set(reference, resolved, SetOptions.merge())
                        else transaction.set(reference, resolved)
                    }

                    override fun writeMovement(docId: String, data: Map<String, Any?>) {
                        transaction.set(firestore.collection(MOVES).document(docId), resolve(data))
                    }
                }
            )
        }.await()

    private fun resolve(data: Map<String, Any?>): Map<String, Any?> =
        data.mapValues { (_, value) -> if (value === ServerTimestamp) FieldValue.serverTimestamp() else value }

    private companion object {
        const val STOCK = "stock"
        const val MOVES = "stockMoves"
    }
}
