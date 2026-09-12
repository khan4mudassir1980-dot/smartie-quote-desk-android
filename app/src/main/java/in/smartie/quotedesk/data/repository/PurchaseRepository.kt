package in.smartie.quotedesk.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import in.smartie.quotedesk.data.model.PurchaseRequirement
import in.smartie.quotedesk.data.model.Urgency
import in.smartie.quotedesk.data.model.toPurchaseRequirement
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class PurchaseRepository(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
) {
    fun observeRequirements(): Flow<List<PurchaseRequirement>> = callbackFlow {
        val registration = firestore.collection("purchase").addSnapshotListener { snapshot, error ->
            if (error != null) close(error)
            else trySend(snapshot?.documents.orEmpty().map { it.toPurchaseRequirement() }
                .sortedByDescending { it.updatedAt })
        }
        awaitClose { registration.remove() }
    }

    suspend fun addRequirement(name: String, quantity: Double, urgency: Urgency, note: String) {
        require(name.isNotBlank()) { "Product or item name is required." }
        require(quantity > 0) { "Quantity must be more than zero." }
        val caller = requireNotNull(auth.currentUser)
        val ref = firestore.collection("purchase").document()
        ref.set(mapOf(
            "id" to ref.id,
            "name" to name.trim(),
            "qty" to quantity,
            "urgency" to urgency.wireValue,
            "status" to "Needed",
            "note" to note.trim(),
            "by" to (caller.displayName ?: caller.email.orEmpty()),
            "byUid" to caller.uid,
            "updated" to System.currentTimeMillis(),
            "received" to false,
        )).await()
    }
}
