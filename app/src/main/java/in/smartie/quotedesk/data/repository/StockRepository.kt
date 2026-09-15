package `in`.smartie.quotedesk.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import `in`.smartie.quotedesk.data.model.StockItem
import `in`.smartie.quotedesk.data.model.StockMovement
import `in`.smartie.quotedesk.data.model.toStockItem
import `in`.smartie.quotedesk.data.model.toStockMovement
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
            else trySend(snapshot?.documents.orEmpty().mapNotNull {
                runCatching { it.toStockItem() }.getOrNull()
            }
                .sortedWith(compareByDescending<StockItem> { it.isOut }.thenByDescending { it.isLow }.thenBy { it.key }))
        }
        awaitClose { registration.remove() }
    }

    fun observeMovements(): Flow<List<StockMovement>> = callbackFlow {
        val registration = firestore.collection("stockMoves")
            .orderBy("at", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(400)
            .addSnapshotListener { snapshot, error ->
                if (error != null) close(error)
                else trySend(snapshot?.documents.orEmpty().mapNotNull {
                    runCatching { it.toStockMovement() }.getOrNull()
                })
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
            val previous = (existing.get("q") as? Number)?.toDouble() ?: item.quantity
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
            ), SetOptions.merge())
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
            val previous = (existing.get("q") as? Number)?.toDouble() ?: item.quantity
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
            ), SetOptions.merge())
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

    suspend fun addManualStock(
        model: String,
        name: String,
        categoryId: String,
        unit: String,
        quantity: Double,
        reorderLevel: Double,
        note: String,
    ) {
        require(model.isNotBlank() || name.isNotBlank()) { "Product or item name is required." }
        require(quantity >= 0) { "Starting quantity cannot be negative." }
        require(reorderLevel >= 0) { "Reorder level cannot be negative." }
        val caller = requireNotNull(auth.currentUser)
        val displayModel = model.trim().ifBlank { name.trim() }
        val safe = displayModel.lowercase().map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
            .trim('_').ifBlank { System.currentTimeMillis().toString() }
        val key = "manualstock|$safe"
        val stockRef = firestore.collection("stock").document(key.replace('/', '_'))
        val moveRef = firestore.collection("stockMoves").document()
        firestore.runTransaction { transaction ->
            require(!transaction.get(stockRef).exists()) { "This manual stock item already exists." }
            val at = System.currentTimeMillis()
            val by = caller.displayName ?: caller.email.orEmpty()
            transaction.set(stockRef, mapOf(
                "key" to key, "group" to "manualstock", "model" to safe,
                "q" to quantity, "min" to reorderLevel, "t" to at,
                "lastAction" to "add", "manual" to true,
                "manualModel" to displayModel, "manualName" to name.trim(),
                "categoryId" to categoryId, "unit" to unit.trim().ifBlank { "each" },
                "stockNote" to note.trim(), "pinned" to false, "pinOrder" to 0,
                "by" to by, "byUid" to caller.uid,
            ))
            transaction.set(moveRef, mapOf(
                "id" to moveRef.id, "key" to key, "action" to "add",
                "prev" to 0, "next" to quantity, "qty" to quantity,
                "note" to "Manual stock item created", "at" to at,
                "by" to by, "byUid" to caller.uid,
            ))
        }.await()
    }

    suspend fun togglePinned(item: StockItem) {
        val caller = requireNotNull(auth.currentUser)
        val ref = firestore.collection("stock").document(item.key.replace('/', '_'))
        val next = !item.pinned
        ref.set(mapOf(
            "pinned" to next,
            "pinOrder" to if (next) System.currentTimeMillis() else 0,
            "lastAction" to "pin",
            "t" to System.currentTimeMillis(),
            "by" to (caller.displayName ?: caller.email.orEmpty()),
            "byUid" to caller.uid,
        ), SetOptions.merge()).await()
    }
}
