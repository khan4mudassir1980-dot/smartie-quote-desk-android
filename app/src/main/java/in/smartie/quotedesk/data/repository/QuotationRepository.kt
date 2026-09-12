package in.smartie.quotedesk.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import in.smartie.quotedesk.data.model.QuotationSummary
import in.smartie.quotedesk.data.model.toQuotationSummary
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class QuotationRepository(
    @Suppress("unused") private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
) {
    fun observeQuotations(): Flow<List<QuotationSummary>> = callbackFlow {
        val registration = firestore.collection("quotations").addSnapshotListener { snapshot, error ->
            if (error != null) close(error)
            else trySend(snapshot?.documents.orEmpty().map { it.toQuotationSummary() }
                .sortedByDescending { it.createdAt })
        }
        awaitClose { registration.remove() }
    }
}
