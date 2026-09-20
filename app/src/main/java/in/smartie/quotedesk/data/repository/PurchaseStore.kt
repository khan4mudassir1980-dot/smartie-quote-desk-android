package `in`.smartie.quotedesk.data.repository

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import `in`.smartie.quotedesk.data.mapping.DocData
import `in`.smartie.quotedesk.data.mapping.toDocData
import `in`.smartie.quotedesk.domain.DeleteField
import `in`.smartie.quotedesk.domain.ServerTimestamp
import kotlinx.coroutines.tasks.await

/**
 * The slice of Firestore the purchase writer uses.
 *
 * It exists for the same reason [StockStore] does: the transaction contract in
 * [PurchaseWriteRepository] can then be driven by a test, including the part
 * that only shows up on a **retry**, which a real emulator will not reproduce
 * on demand.
 */
interface PurchaseStore {
    /**
     * Runs [body] inside one Firestore transaction and returns its result.
     *
     * Firestore may run [body] **more than once** under contention.
     * Implementations must preserve that, and callers must not generate
     * anything inside it that has to stay stable across attempts.
     */
    suspend fun <T> transaction(body: (PurchaseTransaction) -> T): T
}

/**
 * Reads and writes available inside one purchase transaction.
 *
 * **There is deliberately no delete.** A requirement is never hard-deleted —
 * a removed document reappears the moment a PWA device syncs, which is why
 * the v9 rules refuse `delete` to everybody. Leaving the method out makes
 * that structural rather than a comment somebody can overlook.
 */
interface PurchaseTransaction {
    /**
     * The document as the board's listener would see it, or null when it is
     * not there.
     *
     * [DocData] rather than a raw field map, so the caller can put it through
     * the same `toPurchaseRecord()` the read path uses — including its
     * tolerance for a string `qty`, a numeric `received: 1` and a missing
     * `id`. Coercing those a second time by hand is how the two paths would
     * drift apart.
     */
    fun read(docId: String): DocData?

    fun write(docId: String, data: Map<String, Any?>, merge: Boolean)
}

/**
 * The real thing.
 *
 * The read happens before the write, which Firestore requires, because the
 * planner reads the stored revision and quantity and only then decides what
 * to write. [ServerTimestamp] and [DeleteField] are swapped for the real
 * sentinels here, so nothing above this file has to import Firebase.
 */
class FirestorePurchaseStore(private val firestore: FirebaseFirestore) : PurchaseStore {

    override suspend fun <T> transaction(body: (PurchaseTransaction) -> T): T =
        firestore.runTransaction<T> { transaction ->
            body(
                object : PurchaseTransaction {
                    override fun read(docId: String): DocData? {
                        val snapshot =
                            transaction.get(firestore.collection(PURCHASE).document(docId))
                        return if (snapshot.exists()) snapshot.toDocData() else null
                    }

                    override fun write(
                        docId: String,
                        data: Map<String, Any?>,
                        merge: Boolean
                    ) {
                        val reference = firestore.collection(PURCHASE).document(docId)
                        val resolved = resolve(data)
                        if (merge) transaction.set(reference, resolved, SetOptions.merge())
                        else transaction.set(reference, resolved)
                    }
                }
            )
        }.await()

    /**
     * Swaps both markers, and drops a null rather than writing one.
     *
     * [DeleteField] becomes `FieldValue.delete()`, which is what actually
     * removes the four `rcv*` fields when a requirement is reopened. It is
     * only legal in a merged write, and every write that carries it is one:
     * the single unmerged path is creating a requirement, which has nothing
     * underneath it and never deletes a field.
     */
    private fun resolve(data: Map<String, Any?>): Map<String, Any> = buildMap {
        for ((field, value) in data) {
            val resolved = when {
                value === ServerTimestamp -> FieldValue.serverTimestamp()
                value === DeleteField -> FieldValue.delete()
                else -> value
            }
            if (resolved != null) put(field, resolved)
        }
    }

    private companion object {
        const val PURCHASE = "purchase"
    }
}
