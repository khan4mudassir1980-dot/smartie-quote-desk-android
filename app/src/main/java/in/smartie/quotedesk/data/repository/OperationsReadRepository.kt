package `in`.smartie.quotedesk.data.repository

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import `in`.smartie.quotedesk.data.docDataFlow
import `in`.smartie.quotedesk.data.mapping.toPartyRecord
import `in`.smartie.quotedesk.data.mapping.toPurchaseRecord
import `in`.smartie.quotedesk.data.mapping.toQuotationRecord
import `in`.smartie.quotedesk.data.model.PartyRecord
import `in`.smartie.quotedesk.data.model.PurchaseRecord
import `in`.smartie.quotedesk.data.model.QuotationRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Read-only access to purchase requirements, parties and quotations.
 * Writers arrive with phases N4 and N5.
 */
class OperationsReadRepository(private val firestore: FirebaseFirestore) {

    fun observeRequirements(): Flow<List<PurchaseRecord>> =
        firestore.collection("purchase").docDataFlow().map { documents ->
            documents
                .map { it.toPurchaseRecord() }
                // A PWA soft delete must disappear here too (audit R8/D2).
                .filterNot { it.deleted }
                // Newest first by creation, so editing an item never moves it.
                .sortedByDescending { it.createdAt }
        }

    fun observeParties(): Flow<List<PartyRecord>> =
        firestore.collection("customers").docDataFlow().map { documents ->
            documents.map { it.toPartyRecord() }.sortedBy { it.name.lowercase() }
        }

    fun observeQuotations(limit: Long = 200): Flow<List<QuotationRecord>> =
        firestore.collection("quotations")
            .orderBy("at", Query.Direction.DESCENDING)
            .limit(limit)
            .docDataFlow()
            .map { documents -> documents.map { it.toQuotationRecord() } }
}
