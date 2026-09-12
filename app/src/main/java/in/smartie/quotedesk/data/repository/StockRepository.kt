package in.smartie.quotedesk.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import in.smartie.quotedesk.data.model.StockItem
import in.smartie.quotedesk.data.model.toStockItem
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlin.math.abs
import kotlin.math.max

class StockRepository(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
) {
    fun observeStock(): Flow<List<StockItem>> = callbackFlow {
        val registration = firestore.collection("stock").addSnapshotListener { snapshot, error ->
            if (error != null) close(error)
            else trySend(snapshot?.documents.orEmpty().map { it.toStockItem() }
                .sortedWith(compareByDescending<StockItem> { it.isOut }.thenByDescending { it.isLow }.thenBy { it.key }))
        }
        awaitClose { registration.remove() }
    }

    suspend fun commitDelta(item: StockItem, delta: Double, note: String = "") {
        if (delta == 0.0) return
        val caller = requireNotNull(auth.currentUser)
        val stockRef = firestore.collection("stock").document(item.key)
        val moveRef = firestore.collection("stockMoves").document()
        firestore.runTransaction { transaction ->
            val existing = transaction.get(stockRef)
            val previous = existing.getDouble("q") ?: existing.getLong("q")?.toDouble() ?: item.quantity
            val next = max(0.0, previous + delta)
            val at = System.currentTimeMillis()
            val action = if (delta > 0) "in" else "out"
            transaction.set(stockRef, mapOf(
                "key" to item.key,
                "q" to next,
                "min" to item.reorderLevel,
                "t" to at,
                "by" to (caller.displayName ?: caller.email.orEmpty()),
                "byUid" to caller.uid,
                "lastAction" to action,
                "stockNote" to if (note.isBlank()) item.note else note,
            ))
            transaction.set(moveRef, mapOf(
                "id" to moveRef.id,
                "key" to item.key,
                "action" to action,
                "prev" to previous,
                "next" to next,
                "qty" to kotlin.math.abs(delta),
                "note" to note,
                "at" to at,
                "by" to (caller.displayName ?: caller.email.orEmpty()),
                "byUid" to caller.uid,
            ))
        }.await()
    }

    suspend fun editStock(item: StockItem, quantity: Double, reorderLevel: Double, note: String) {
        require(quantity >= 0) { "Stock quantity cannot be negative." }
        require(reorderLevel >= 0) { "Reorder level cannot be negative." }
        val caller = requireNotNull(auth.currentUser)
        val stockRef = firestore.collection("stock").document(item.key)
        val moveRef = firestore.collection("stockMoves").document()
        firestore.runTransaction { transaction ->
            val existing = transaction.get(stockRef)
            val previous = existing.getDouble("q") ?: existing.getLong("q")?.toDouble() ?: item.quantity
            val at = System.currentTimeMillis()
            val action = if (previous != quantity) "set" else "min"
            transaction.set(stockRef, mapOf(
                "key" to item.key,
                "q" to quantity,
                "min" to reorderLevel,
                "t" to at,
                "by" to (caller.displayName ?: caller.email.orEmpty()),
                "byUid" to caller.uid,
                "lastAction" to action,
                "stockNote" to note.trim(),
            ))
            transaction.set(moveRef, mapOf(
                "id" to moveRef.id,
                "key" to item.key,
                "action" to action,
                "prev" to previous,
                "next" to quantity,
                "qty" to abs(quantity - previous),
                "note" to note.trim(),
                "at" to at,
                "by" to (caller.displayName ?: caller.email.orEmpty()),
                "byUid" to caller.uid,
            ))
        }.await()
    }
}
