package `in`.smartie.quotedesk.data.repository

import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Transaction
import `in`.smartie.quotedesk.data.mapping.DocData
import `in`.smartie.quotedesk.data.mapping.toDocData
import kotlinx.coroutines.tasks.await

/** The real [QuotationStore]. */
class FirestoreQuotationStore(private val firestore: FirebaseFirestore) : QuotationStore {

    override suspend fun <T> transaction(body: (QuotationTransaction) -> T): T =
        firestore.runTransaction<T> { transaction ->
            body(
                object : QuotationTransaction {
                    override fun readQuotation(id: String): DocData? =
                        transaction.read(firestore.collection(QUOTATIONS).document(id))

                    override fun readNumbering(): DocData? = transaction.read(numbering)

                    override fun readQuoting(): DocData? =
                        transaction.read(firestore.collection(TEAM_SETTINGS).document(QUOTING))

                    override fun readCustomer(id: String): DocData? =
                        transaction.read(firestore.collection(CUSTOMERS).document(id))

                    override fun writeQuotation(id: String, data: Map<String, Any?>) {
                        transaction.set(
                            firestore.collection(QUOTATIONS).document(id),
                            data.filterValues { it != null }.mapValues { it.value!! }
                        )
                    }

                    override fun writeNumbering(fields: Map<String, Any?>) {
                        transaction.update(
                            numbering,
                            fields.filterValues { it != null }.mapValues { it.value!! }
                        )
                    }
                }
            )
        }.await()

    private val numbering: DocumentReference
        get() = firestore.collection(TEAM_SETTINGS).document(NUMBERING)

    private fun Transaction.read(reference: DocumentReference): DocData? {
        val snapshot = get(reference)
        return if (snapshot.exists()) snapshot.toDocData() else null
    }

    private companion object {
        const val QUOTATIONS = "quotations"
        /** V8C4's collection name. The app calls them parties; the wire does not. */
        const val CUSTOMERS = "customers"
        const val TEAM_SETTINGS = "teamSettings"
        const val NUMBERING = "numbering"
        const val QUOTING = "quoting"
    }
}
