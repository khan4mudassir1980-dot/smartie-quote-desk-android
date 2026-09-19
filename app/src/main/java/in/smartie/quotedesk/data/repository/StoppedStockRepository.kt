package `in`.smartie.quotedesk.data.repository

import com.google.firebase.firestore.FirebaseFirestore
import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.domain.Permissions
import `in`.smartie.quotedesk.domain.StockRemoval
import kotlinx.coroutines.tasks.await

/** The one write stopped-item history has: emptying it. */
interface StoppedStockStore {
    /**
     * Deletes [ids] in **one** commit. Never called with more than
     * [StoppedStockRepository.BATCH_LIMIT] ids; the repository does the
     * splitting, so this can stay the thin part.
     */
    suspend fun deleteBatch(ids: List<String>)
}

class FirestoreStoppedStockStore(
    private val firestore: FirebaseFirestore
) : StoppedStockStore {

    override suspend fun deleteBatch(ids: List<String>) {
        val batch = firestore.batch()
        for (id in ids) {
            batch.delete(firestore.collection(COLLECTION).document(id))
        }
        batch.commit().await()
    }

    private companion object {
        const val COLLECTION = "stoppedStock"
    }
}

/**
 * Clearing stopped-item history.
 *
 * Deletes **only** `/stoppedStock` documents, and only the ids it was given:
 * there is no query here, no collection-wide sweep and nothing that could
 * reach `/stock`, `/products` or `/stockMoves` by accident.
 *
 * A Firestore batch caps at 500 writes, and this list has no upper bound —
 * a team that removes an item a week for four years is over it. So the ids
 * are split and committed in order. A batch that fails stops the run and
 * raises: the batches before it are already gone, the rest are untouched,
 * and clearing again finishes the job. That is safe precisely because every
 * entry is independent — no entry means anything in terms of another, so a
 * half-cleared history is a smaller history and nothing more.
 *
 * A removal committing at the same time is likewise safe: its id is not in
 * this list, so this run cannot touch it, and the new entry simply survives
 * the clear.
 */
class StoppedStockRepository(private val store: StoppedStockStore) {

    constructor(firestore: FirebaseFirestore) : this(FirestoreStoppedStockStore(firestore))

    /** Returns how many entries were deleted. */
    suspend fun clear(member: Member, ids: List<String>): Int {
        require(Permissions.canStopTrackingStock(member)) { StockRemoval.NOT_ALLOWED_CLEAR }
        val unique = ids.filter { it.isNotBlank() }.distinct()
        if (unique.isEmpty()) return 0
        for (chunk in unique.chunked(BATCH_LIMIT)) {
            store.deleteBatch(chunk)
        }
        return unique.size
    }

    companion object {
        /** Firestore's own cap on a batched write. */
        const val BATCH_LIMIT = 500
    }
}
