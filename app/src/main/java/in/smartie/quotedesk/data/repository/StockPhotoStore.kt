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
 * The real thing: one `get()` on one document.
 *
 * **Every call here is a billed read.** Firestore's own persistence may
 * answer it from the device, and offline it is the only thing that can, but
 * that is a availability property and not a billing one — a `get()` that the
 * local cache serves is still a document read against the daily quota. So
 * nothing in this feature treats Firestore's cache as free. Not spending the
 * read at all is [DiskStockPhotoFiles]'s job, and this is called only once
 * that has already missed.
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
 * Photo bytes for a row, spending a Firestore read only when neither cache
 * can answer.
 *
 * Three layers, narrowest first:
 *
 * 1. **memory**, which is free and dies with the process;
 * 2. **disk**, which is nearly free and survives a restart — this is what
 *    makes "cache-first" true across app launches rather than only across
 *    scrolls;
 * 3. **Firestore**, which costs a read.
 *
 * Every layer answers only on a revision that matches or exceeds the row's,
 * so no arrangement of them can put a superseded picture on a shelf.
 *
 * Nothing here writes a photo. Photos are written by [StockWriteRepository],
 * in the same transaction as the stock row they belong to.
 */
class StockPhotoRepository(
    private val photos: StockPhotoStore,
    private val cache: StockPhotoCache = StockPhotoCache(),
    private val files: StockPhotoFiles = NoStockPhotoFiles
) {

    /**
     * The photo to show for [record], or null when there is none to show.
     *
     * - the row has no photo — both caches drop it and **nothing is read**,
     *   from disk or from Firestore;
     * - memory holds a matching revision — those bytes, and no read;
     * - disk holds one — those bytes, no Firestore read, and memory is
     *   refilled so the next scroll does not touch the disk either;
     * - otherwise one Firestore read, kept in both caches.
     *
     * A fetched document whose revision is **behind** the row is not shown.
     * That can only happen when a read races a listener update, and showing
     * it would be showing a picture the row has already replaced.
     *
     * A read that fails — offline with nothing cached, a refused rule, a
     * broken connection — is null, not an exception. The card shows its
     * ordinary placeholder, which is the honest thing for "no picture to
     * show here right now".
     */
    suspend fun load(record: StockRecord): ByteArray? = when (cache.actionFor(record)) {
        PhotoAction.NONE -> {
            // The row has lost its photo, so the file has to go as well —
            // otherwise a removal would survive on disk until eviction, and
            // re-photographing the row would find a stale revision waiting.
            files.forget(record.documentId)
            null
        }
        PhotoAction.USE_CACHE -> cache.bytesFor(record)
        PhotoAction.FETCH -> fromDisk(record) ?: fetch(record)
    }

    private fun fromDisk(record: StockRecord): ByteArray? {
        val cached = files.read(record.documentId, record.photoRev) ?: return null
        cache.put(record.documentId, cached.rev, cached.bytes)
        return cached.bytes
    }

    private suspend fun fetch(record: StockRecord): ByteArray? {
        val fetched = runCatching { photos.read(StockPhoto.documentId(record.key)) }
            .getOrNull() ?: return null
        if (fetched.rev < record.photoRev) return null
        cache.put(record.documentId, fetched.rev, fetched.bytes)
        files.put(record.documentId, fetched.rev, fetched.bytes)
        return fetched.bytes
    }

    /**
     * Drops what is held for a row, in **both** caches.
     *
     * Called after this device replaces or removes a photo, so the next load
     * cannot be answered by the picture that was just superseded.
     */
    fun forget(record: StockRecord) {
        cache.forget(record.documentId)
        files.forget(record.documentId)
    }
}
