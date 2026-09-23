package `in`.smartie.quotedesk.data.repository

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import `in`.smartie.quotedesk.data.mapping.DocData
import `in`.smartie.quotedesk.data.mapping.toDocData
import kotlinx.coroutines.tasks.await

/**
 * The slice of Firestore the product editor uses.
 *
 * **A transaction, not a plain write, and not for atomicity's sake.** The save
 * has to send every field the rule names, so any field the person did not edit
 * is one this code is about to write back. Taking those from the screen would
 * mean a sheet left open while somebody else corrected a rate would quietly
 * undo them. Reading them here, inside the transaction, means only a field two
 * people edited can collide.
 *
 * It may also read *two* documents. Seeding once wrote products at the legacy
 * `group|model` id and the migration left those in place, so the record on
 * screen may have been read from one while the write has to land on
 * `group__model` — the only id V8C4 computes for a product. Both are read so
 * that a canonical document being created from a legacy one carries what the
 * legacy one held.
 */
interface ProductStore {
    /**
     * Runs [body] inside one Firestore transaction and returns its result.
     *
     * Firestore may run [body] more than once under contention, so nothing
     * that has to stay stable across attempts is generated inside it.
     */
    suspend fun <T> transaction(body: (ProductTransaction) -> T): T
}

/** Reads and writes available inside one product transaction. */
interface ProductTransaction {
    fun read(docId: String): DocData?

    /**
     * Always a merge, and **never a filtered map**.
     *
     * `PartyStore` drops null values before writing, because for a party a
     * null means "do not write this key". For a product it means the opposite:
     * `contractor: null` is V8C4's deliberate "Price not set", which
     * `applyProductDoc` reads as one rather than falling back to the seed
     * figure. Filtering it out would take the key off the document and hand
     * the PWA back its seed fallback — and the rule refuses an absent price
     * key anyway, because it reads the key bare.
     */
    fun write(docId: String, data: Map<String, Any?>)
}

/** The real thing. */
class FirestoreProductStore(private val firestore: FirebaseFirestore) : ProductStore {

    override suspend fun <T> transaction(body: (ProductTransaction) -> T): T =
        firestore.runTransaction<T> { transaction ->
            body(
                object : ProductTransaction {
                    override fun read(docId: String): DocData? {
                        val snapshot =
                            transaction.get(firestore.collection(PRODUCTS).document(docId))
                        return if (snapshot.exists()) snapshot.toDocData() else null
                    }

                    override fun write(docId: String, data: Map<String, Any?>) {
                        val reference = firestore.collection(PRODUCTS).document(docId)
                        // The nulls are the payload, not noise: Firestore
                        // stores a null value, which is exactly what a
                        // "Price not set" tier has to be.
                        @Suppress("UNCHECKED_CAST")
                        transaction.set(reference, data as Map<String, Any>, SetOptions.merge())
                    }
                }
            )
        }.await()

    private companion object {
        const val PRODUCTS = "products"
    }
}
