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

    /**
     * Every requirement, **removed ones included**.
     *
     * The removed rows used to be dropped here. Purchase History needs them,
     * and one unconstrained listener is still the only read — a second query
     * would double the tab's cost against a shared daily quota to fetch rows
     * this one already holds.
     *
     * Dropping them here made the board's own filter redundant, and it is
     * load-bearing again: `PurchaseBoard.active` filters `isOpen`
     * (`!deleted && !isClosed`) and `closed` filters `!deleted && isClosed`,
     * so a removed requirement is in neither list, exactly as before. Nothing
     * else consumes this flow — there is no badge and no count — and a test
     * pins the board's behaviour rather than trusting the arithmetic.
     */
    fun observeRequirements(): Flow<List<PurchaseRecord>> =
        firestore.collection("purchase").docDataFlow().map { documents ->
            documents
                .map { it.toPurchaseRecord() }
                // One row per document, so a list and any count of it can
                // never disagree. A snapshot is unique by document id
                // already, so this cannot drop a real row — it is here so
                // that "the list and the count come from one de-duplicated
                // source" is a property of the code and not of an argument
                // about Firestore's behaviour.
                .distinctBy { it.id }
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
