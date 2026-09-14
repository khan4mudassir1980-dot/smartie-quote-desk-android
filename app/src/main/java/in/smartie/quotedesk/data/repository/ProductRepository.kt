package `in`.smartie.quotedesk.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import `in`.smartie.quotedesk.data.model.Product
import `in`.smartie.quotedesk.data.model.ProductCategory
import `in`.smartie.quotedesk.data.model.toProduct
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class ProductRepository(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
) {
    fun observeProducts(): Flow<List<Product>> = callbackFlow {
        val registration = firestore.collection("products").addSnapshotListener { snapshot, error ->
            if (error != null) close(error)
            else trySend(snapshot?.documents.orEmpty().map { it.toProduct() }
                .filter { it.active }.sortedWith(compareBy(Product::group, Product::model)))
        }
        awaitClose { registration.remove() }
    }

    fun observePins(): Flow<Set<String>> = callbackFlow {
        val registration = firestore.collection("teamSettings").document("productPins")
            .addSnapshotListener { snapshot, error ->
                if (error != null) close(error)
                else trySend((snapshot?.get("keys") as? List<*>)?.filterIsInstance<String>()?.toSet().orEmpty())
            }
        awaitClose { registration.remove() }
    }

    fun observeCategories(): Flow<List<ProductCategory>> = callbackFlow {
        val registration = firestore.collection("teamSettings").document("categories")
            .addSnapshotListener { snapshot, error ->
                if (error != null) close(error)
                else {
                    val raw = snapshot?.get("map") as? Map<*, *> ?: emptyMap<Any, Any>()
                    val categories = raw.mapNotNull { (key, value) ->
                        val id = key as? String ?: return@mapNotNull null
                        val data = value as? Map<*, *> ?: return@mapNotNull null
                        ProductCategory(
                            id = id,
                            name = data["name"] as? String ?: id,
                            order = (data["order"] as? Number)?.toDouble() ?: 0.0,
                            archived = data["archived"] == true,
                        )
                    }.filterNot { it.archived }.sortedWith(compareBy(ProductCategory::order, ProductCategory::name))
                    trySend(categories)
                }
            }
        awaitClose { registration.remove() }
    }

    suspend fun togglePin(productId: String, currentlyPinned: Boolean) {
        requireNotNull(auth.currentUser)
        val ref = firestore.collection("teamSettings").document("productPins")
        firestore.runTransaction { transaction ->
            val pins = (transaction.get(ref).get("keys") as? List<*>)
                ?.filterIsInstance<String>()?.toMutableList() ?: mutableListOf()
            if (currentlyPinned) pins.remove(productId)
            else if (productId !in pins) {
                require(pins.size < 15) { "You can pin up to 15 products." }
                pins.add(0, productId)
            }
            transaction.set(ref, mapOf("keys" to pins, "updatedAt" to System.currentTimeMillis()))
        }.await()
    }

    suspend fun saveProduct(product: Product) {
        val caller = requireNotNull(auth.currentUser)
        require(product.model.isNotBlank()) { "Product model is required." }
        require(product.group.isNotBlank()) { "Price group is required." }
        require(product.gst in 0.0..28.0) { "GST must be between 0 and 28." }
        val seedModel = product.seedModel.ifBlank { product.model.trim() }
        val logicalId = product.id.ifBlank { "${product.group}|$seedModel" }
        val documentId = product.documentId.ifBlank {
            "${product.group}__${seedModel}".map { if (it in "/.#\$[]") '_' else it }.joinToString("")
        }
        firestore.collection("products").document(documentId).set(mapOf(
            "id" to logicalId,
            "key" to logicalId,
            "group" to product.group.trim(),
            "seedModel" to seedModel,
            "categoryId" to product.categoryId,
            "model" to product.model.trim(),
            "name" to product.name.trim(),
            "unit" to product.unit.trim().ifBlank { "each" },
            "spec" to product.specification.trim(),
            "gst" to product.gst,
            "dealer" to product.dealer,
            "contractor" to product.contractor,
            "client" to product.client,
            "active" to product.active,
            "updated" to System.currentTimeMillis(),
            "by" to (caller.displayName ?: caller.email.orEmpty()),
            "byUid" to caller.uid,
        )).await()
    }

    suspend fun saveCategory(name: String) {
        val caller = requireNotNull(auth.currentUser)
        val cleanName = name.trim()
        require(cleanName.isNotBlank()) { "Category name is required." }
        val id = cleanName.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
            .ifBlank { "category-${System.currentTimeMillis()}" }
        val ref = firestore.collection("teamSettings").document("categories")
        firestore.runTransaction { transaction ->
            val snapshot = transaction.get(ref)
            @Suppress("UNCHECKED_CAST")
            val existing = (snapshot.get("map") as? Map<String, Any?>).orEmpty().toMutableMap()
            require(id !in existing) { "This category already exists." }
            val maxOrder = existing.values.mapNotNull { (it as? Map<*, *>)?.get("order") as? Number }
                .maxOfOrNull { it.toDouble() } ?: 0.0
            existing[id] = mapOf("id" to id, "name" to cleanName, "order" to maxOrder + 10.0)
            transaction.set(ref, mapOf(
                "map" to existing,
                "updated" to System.currentTimeMillis(),
                "by" to (caller.displayName ?: caller.email.orEmpty()),
            ))
        }.await()
    }
}
