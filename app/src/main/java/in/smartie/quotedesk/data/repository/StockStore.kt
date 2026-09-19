package `in`.smartie.quotedesk.data.repository

import com.google.firebase.firestore.Blob
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

    /**
     * The photo document, written in the **same** transaction as its stock
     * row. The two must never be written apart: the rules refuse a commit
     * that leaves them disagreeing.
     */
    fun writePhoto(docId: String, data: Map<String, Any?>)

    /** Removing a photo. Paired with the stock write that clears `hasPhoto`. */
    fun deletePhoto(docId: String)

    /**
     * Deleting the stock row itself — a **permanent** removal, paired in the
     * same commit with the history document below and with the photo above.
     * The rules refuse a history entry whose row is still there afterwards.
     */
    fun deleteStock(docId: String)

    /** The immutable `/stoppedStock` record that outlives the removed row. */
    fun writeStopped(docId: String, data: Map<String, Any?>)

    /** Whether a `/stoppedStock` entry already exists, for an idempotent retry. */
    fun stoppedExists(docId: String): Boolean
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
        firestore.runTransaction<T> { transaction ->
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

                    override fun writePhoto(docId: String, data: Map<String, Any?>) {
                        // Written whole rather than merged: a photo document
                        // is replaced outright, never patched, so no field of
                        // a previous image can survive under a new one.
                        transaction.set(firestore.collection(PHOTOS).document(docId), resolve(data))
                    }

                    override fun deletePhoto(docId: String) {
                        transaction.delete(firestore.collection(PHOTOS).document(docId))
                    }

                    override fun deleteStock(docId: String) {
                        transaction.delete(firestore.collection(STOCK).document(docId))
                    }

                    override fun writeStopped(docId: String, data: Map<String, Any?>) {
                        transaction.set(firestore.collection(STOPPED).document(docId), resolve(data))
                    }

                    override fun stoppedExists(docId: String): Boolean =
                        transaction.get(firestore.collection(STOPPED).document(docId)).exists()
                }
            )
        }.await()

    /**
     * Swaps the marker, and drops a null rather than writing one.
     *
     * A `ByteArray` is wrapped as a Firestore [Blob] here, for the same reason
     * the timestamp marker is swapped here: nothing above this file imports
     * Firebase.
     */
    private fun resolve(data: Map<String, Any?>): Map<String, Any> = buildMap {
        for ((field, value) in data) {
            val resolved = when {
                value === ServerTimestamp -> FieldValue.serverTimestamp()
                value is ByteArray -> Blob.fromBytes(value)
                else -> value
            }
            if (resolved != null) put(field, resolved)
        }
    }

    private companion object {
        const val STOCK = "stock"
        const val MOVES = "stockMoves"
        const val PHOTOS = "stockPhotos"
        const val STOPPED = "stoppedStock"
    }
}
