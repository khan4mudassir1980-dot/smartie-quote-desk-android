package `in`.smartie.quotedesk.data.repository

import com.google.firebase.firestore.FirebaseFirestore
import `in`.smartie.quotedesk.data.mapping.toDocData
import `in`.smartie.quotedesk.data.mapping.toStockPhotoRecord
import `in`.smartie.quotedesk.data.model.StockPhotoRecord
import `in`.smartie.quotedesk.data.model.StockRecord
import `in`.smartie.quotedesk.domain.PhotoAction
import `in`.smartie.quotedesk.domain.StockPhoto
import `in`.smartie.quotedesk.domain.StockPhotoCache
import kotlinx.coroutines.tasks.await

/** Reading one photo document. Small enough to fake in a test. */
interface StockPhotoStore {
    suspend fun read(documentId: String): StockPhotoRecord?
}

/**
 * The real thing.
 *
 * An ordinary `get()`, not a cache-first one: the only time this is called at
 * all is when the revision has moved, and at that point the local copy is the
 * one thing that is certainly wrong. Offline, Firestore's own persistence
 * answers from its cache, which is what keeps a photo viewable on a phone
 * with no signal.
 */
class FirestoreStockPhotoStore(private val firestore: FirebaseFirestore) : StockPhotoStore {
    override suspend fun read(documentId: String): StockPhotoRecord? =
        firestore.collection(PHOTOS).document(documentId).get().await()
            .toDocData().toStockPhotoRecord()

    private companion object {
        const val PHOTOS = "stockPhotos"
    }
}

/**
 * Photo bytes for a row, spending a read only when the revision has moved.
 *
 * Nothing here writes. Photos are written by [StockWriteRepository], in the
 * same transaction as the stock row they belong to.
 */
class StockPhotoRepository(
    private val photos: StockPhotoStore,
    private val cache: StockPhotoCache = StockPhotoCache()
) {

    /**
     * The photo to show for [record], or null when there is none to show.
     *
     * Three outcomes and one read at most:
     *
     * - the row has no photo — the cached bytes are dropped and nothing is
     *   fetched;
     * - the cached revision matches — those bytes, and **no read**;
     * - otherwise one read, and the result is kept against its revision.
     *
     * A fetched document whose revision is **behind** the row is not shown.
     * That can only happen when a read races a listener update, and showing
     * it would be showing a picture the row has already replaced.
     */
    suspend fun load(record: StockRecord): ByteArray? = when (cache.actionFor(record)) {
        PhotoAction.NONE -> null
        PhotoAction.USE_CACHE -> cache.bytesFor(record)
        PhotoAction.FETCH -> fetch(record)
    }

    private suspend fun fetch(record: StockRecord): ByteArray? {
        val fetched = photos.read(StockPhoto.documentId(record.key)) ?: return null
        if (fetched.rev < record.photoRev) return null
        cache.put(record.documentId, fetched.rev, fetched.bytes)
        return fetched.bytes
    }

    /** Drops what is held for a row, for a caller that knows it has changed. */
    fun forget(record: StockRecord) = cache.forget(record.documentId)
}
