package `in`.smartie.quotedesk.data.repository

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import `in`.smartie.quotedesk.data.docDataFlow
import `in`.smartie.quotedesk.data.mapping.asStringList
import `in`.smartie.quotedesk.data.mapping.toProductCategories
import `in`.smartie.quotedesk.data.mapping.toProductRecord
import `in`.smartie.quotedesk.data.mapping.toStockMove
import `in`.smartie.quotedesk.data.mapping.toStockRecord
import `in`.smartie.quotedesk.data.model.ProductCategoryRecord
import `in`.smartie.quotedesk.data.model.ProductRecord
import `in`.smartie.quotedesk.data.model.StockMove
import `in`.smartie.quotedesk.data.model.StockRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Read-only access to the catalogue and stock collections.
 *
 * Phase N0 deliberately has no writers: the beta write paths damaged PWA data
 * (audit D1-D3) and are re-introduced, correctly, in phases N2 and N3.
 */
class CatalogueReadRepository(private val firestore: FirebaseFirestore) {

    fun observeProducts(): Flow<List<ProductRecord>> =
        firestore.collection("products").docDataFlow().map { documents ->
            documents
                .map { it.toProductRecord() }
                // Seeding wrote `group|model` and editing wrote `group__model`;
                // keep one document per logical key so the same product cannot
                // appear twice (audit P3/C1).
                .groupBy { it.key }
                .values
                .map(::canonicalProduct)
                .sortedWith(compareBy({ it.group }, { it.model.lowercase() }))
        }

    fun observeCategories(): Flow<List<ProductCategoryRecord>> =
        firestore.collection("teamSettings").document("categories").docDataFlow()
            .map { it?.toProductCategories().orEmpty() }

    fun observePinnedKeys(): Flow<List<String>> =
        firestore.collection("teamSettings").document("productPins").docDataFlow()
            .map { document -> document?.get("keys").asStringList() }

    fun observeStock(): Flow<List<StockRecord>> =
        firestore.collection("stock").docDataFlow().map { documents ->
            documents
                .map { it.toStockRecord() }
                // `off:1` means the item stopped being tracked.
                .filterNot { it.archived }
                .sortedWith(compareByDescending<StockRecord> { it.pinned }.thenBy { it.name.lowercase() })
        }

    fun observeRecentMovements(limit: Long = 300): Flow<List<StockMove>> =
        firestore.collection("stockMoves")
            .orderBy("at", Query.Direction.DESCENDING)
            .limit(limit)
            .docDataFlow()
            .map { documents -> documents.map { it.toStockMove() } }
}

/**
 * Which of a logical key's documents to show. Migration step M2.1 writes the
 * canonical `group__seedModel` document with `schemaVersion: 2` and leaves the
 * legacy one in place for the PWA, so a migrated product is represented by its
 * v2 document however recently the legacy one was touched. Before migration
 * nothing carries a schema version, and the most recently updated document
 * still wins (audit P3).
 *
 * Top level and internal so the rule can be tested without Firebase.
 */
internal fun canonicalProduct(duplicates: List<ProductRecord>): ProductRecord =
    duplicates.filter { it.schemaVersion >= 2 }
        .ifEmpty { duplicates }
        .maxBy { it.updatedAt }
