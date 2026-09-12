package in.smartie.quotedesk.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import in.smartie.quotedesk.data.model.Product
import in.smartie.quotedesk.data.model.toProduct
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
}
