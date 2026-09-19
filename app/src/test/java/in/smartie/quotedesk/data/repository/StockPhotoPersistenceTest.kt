package `in`.smartie.quotedesk.data.repository

import `in`.smartie.quotedesk.data.mapping.Keys
import `in`.smartie.quotedesk.data.model.StockPhotoRecord
import `in`.smartie.quotedesk.data.model.StockRecord
import `in`.smartie.quotedesk.domain.StockPhotoCache
import `in`.smartie.quotedesk.domain.StockPhotoDisk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/**
 * The three layers together: memory, then disk, then Firestore.
 *
 * The question every test here asks is the one the quota argument turns on —
 * **was a Firestore read spent?** `FakeStore.reads` is the answer, and a
 * restart is modelled the only honest way: a new repository, a new memory
 * cache, and the same directory on disk.
 */
class StockPhotoPersistenceTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** Offline, or a refused rule: the store raises rather than returning. */
    private class FakeStore(
        private val photo: StockPhotoRecord?,
        var offline: Boolean = false
    ) : StockPhotoStore {
        var reads = 0
        override suspend fun read(documentId: String): StockPhotoRecord? {
            reads++
            if (offline) throw IOException("no connection")
            return photo
        }
    }

    private val key = Keys.productKey("gateMotors", "SIE1000")
    private val documentId = Keys.stockDocId(key)

    private fun webp(marker: Byte = 1, payload: Int = 256): ByteArray =
        "RIFF".toByteArray() + ByteArray(4) + "WEBP".toByteArray() +
            ByteArray(payload) { marker }

    private fun row(hasPhoto: Boolean = true, photoRev: Double = 1.0) = StockRecord(
        documentId = documentId, key = key, hasPhoto = hasPhoto, photoRev = photoRev
    )

    private fun stored(rev: Double, marker: Byte = 1) = StockPhotoRecord(
        documentId = documentId, key = key, bytes = webp(marker), rev = rev
    )

    private fun files(maxBytes: Long = StockPhotoDisk.MAX_BYTES) =
        DiskStockPhotoFiles(folder.root, maxBytes)

    /** A repository with its own memory cache over the shared directory. */
    private fun repository(store: StockPhotoStore, maxBytes: Long = StockPhotoDisk.MAX_BYTES) =
        StockPhotoRepository(store, StockPhotoCache(), files(maxBytes))

    // --- restart ------------------------------------------------------------

    @Test
    fun `after a restart a matching revision costs no Firestore read`() = runTest {
        val first = FakeStore(stored(rev = 1.0))
        assertNotNull(repository(first).load(row(photoRev = 1.0)))
        assertEquals(1, first.reads)

        // Everything in memory is gone. Only the directory survives.
        val afterRestart = FakeStore(stored(rev = 1.0))
        val bytes = repository(afterRestart).load(row(photoRev = 1.0))

        assertTrue(bytes!!.contentEquals(webp()))
        assertEquals("a restart must not re-read what disk already holds", 0, afterRestart.reads)
    }

    @Test
    fun `a whole board survives a restart without a single read`() = runTest {
        val rows = (1..20).map { index ->
            StockRecord(
                documentId = "gateMotors|M$index",
                key = "gateMotors|M$index",
                hasPhoto = true,
                photoRev = 1.0
            )
        }
        val afterRestart = FakeStore(null)
        val seeding = StockPhotoRepository(
            object : StockPhotoStore {
                override suspend fun read(documentId: String) = StockPhotoRecord(
                    documentId = documentId, key = documentId, bytes = webp(), rev = 1.0
                )
            },
            StockPhotoCache(),
            files()
        )
        rows.forEach { assertNotNull(seeding.load(it)) }

        val reopened = repository(afterRestart)
        rows.forEach { assertNotNull(reopened.load(it)) }

        assertEquals("twenty photos, no reads", 0, afterRestart.reads)
    }

    @Test
    fun `the repository being recreated mid-session costs no read either`() = runTest {
        val store = FakeStore(stored(rev = 1.0))
        repository(store).load(row(photoRev = 1.0))

        // A ViewModel recreated on rotation builds a new repository.
        repository(store).load(row(photoRev = 1.0))

        assertEquals(1, store.reads)
    }

    // --- revisions ----------------------------------------------------------

    @Test
    fun `a replaced photo is not served from disk after a restart`() = runTest {
        repository(FakeStore(stored(rev = 1.0, marker = 1))).load(row(photoRev = 1.0))

        val afterRestart = FakeStore(stored(rev = 2.0, marker = 2))
        val bytes = repository(afterRestart).load(row(photoRev = 2.0))

        assertTrue("the new picture, not the one on disk", bytes!!.contentEquals(webp(2)))
        assertEquals(1, afterRestart.reads)
    }

    @Test
    fun `a stale disk file is never shown, even when the fetch fails`() = runTest {
        repository(FakeStore(stored(rev = 1.0, marker = 1))).load(row(photoRev = 1.0))

        // Offline, with a superseded picture sitting on disk. The placeholder
        // is the honest answer; the old picture is not.
        val offline = FakeStore(stored(rev = 2.0, marker = 2), offline = true)
        assertNull(repository(offline).load(row(photoRev = 2.0)))
    }

    @Test
    fun `replacing purges the superseded file rather than leaving it to eviction`() = runTest {
        repository(FakeStore(stored(rev = 1.0))).load(row(photoRev = 1.0))
        repository(FakeStore(stored(rev = 2.0, marker = 2))).load(row(photoRev = 2.0))

        assertEquals(
            "one row keeps one file",
            1,
            folder.root.listFiles()!!.count { it.isFile }
        )
    }

    // --- removal ------------------------------------------------------------

    @Test
    fun `a row that lost its photo has its file deleted, without a read`() = runTest {
        val store = FakeStore(stored(rev = 1.0))
        repository(store).load(row(photoRev = 1.0))
        assertEquals(1, folder.root.listFiles()!!.count { it.isFile })

        val afterRemoval = FakeStore(null)
        assertNull(repository(afterRemoval).load(row(hasPhoto = false, photoRev = 2.0)))

        assertEquals("the file goes with the photo", 0, folder.root.listFiles()!!.count { it.isFile })
        assertEquals("and noticing costs nothing", 0, afterRemoval.reads)
    }

    @Test
    fun `forgetting a row clears it from disk as well as memory`() = runTest {
        val store = FakeStore(stored(rev = 1.0))
        val live = repository(store)
        val record = row(photoRev = 1.0)
        live.load(record)

        live.forget(record)

        assertEquals(0, folder.root.listFiles()!!.count { it.isFile })
        // A fresh process would have nothing to serve, so it fetches.
        val afterRestart = FakeStore(stored(rev = 1.0))
        repository(afterRestart).load(record)
        assertEquals(1, afterRestart.reads)
    }

    // --- damage -------------------------------------------------------------

    @Test
    fun `a corrupted file is refetched when online`() = runTest {
        repository(FakeStore(stored(rev = 1.0))).load(row(photoRev = 1.0))
        folder.root.listFiles()!!.single { it.isFile }.writeBytes(ByteArray(5))

        val afterRestart = FakeStore(stored(rev = 1.0))
        val bytes = repository(afterRestart).load(row(photoRev = 1.0))

        assertTrue(bytes!!.contentEquals(webp()))
        assertEquals("damage costs one read, not a broken picture", 1, afterRestart.reads)
    }

    @Test
    fun `a corrupted file offline shows nothing rather than garbage`() = runTest {
        repository(FakeStore(stored(rev = 1.0))).load(row(photoRev = 1.0))
        folder.root.listFiles()!!.single { it.isFile }.writeBytes("junk".toByteArray())

        val offline = FakeStore(stored(rev = 1.0), offline = true)
        assertNull(repository(offline).load(row(photoRev = 1.0)))
    }

    @Test
    fun `storage cleared by Android is refetched, and the refetch is cached again`() = runTest {
        repository(FakeStore(stored(rev = 1.0))).load(row(photoRev = 1.0))
        folder.root.listFiles()!!.forEach { it.delete() }

        val afterClearing = FakeStore(stored(rev = 1.0))
        assertNotNull(repository(afterClearing).load(row(photoRev = 1.0)))
        assertEquals(1, afterClearing.reads)

        val afterThat = FakeStore(stored(rev = 1.0))
        assertNotNull(repository(afterThat).load(row(photoRev = 1.0)))
        assertEquals("the refetch refilled the disk", 0, afterThat.reads)
    }

    // --- offline ------------------------------------------------------------

    @Test
    fun `offline a cached photo still shows`() = runTest {
        repository(FakeStore(stored(rev = 1.0))).load(row(photoRev = 1.0))

        val offline = FakeStore(stored(rev = 1.0), offline = true)
        assertNotNull(repository(offline).load(row(photoRev = 1.0)))
        assertEquals("nothing was even attempted", 0, offline.reads)
    }

    @Test
    fun `offline with nothing cached is a placeholder, not a crash`() = runTest {
        val offline = FakeStore(stored(rev = 1.0), offline = true)
        assertNull(repository(offline).load(row(photoRev = 1.0)))
        assertEquals(1, offline.reads)
    }

    @Test
    fun `a failed fetch caches nothing, so reconnecting still works`() = runTest {
        val store = FakeStore(stored(rev = 1.0), offline = true)
        assertNull(repository(store).load(row(photoRev = 1.0)))
        assertEquals(0, folder.root.listFiles()!!.count { it.isFile })

        store.offline = false
        assertNotNull(repository(store).load(row(photoRev = 1.0)))
    }

    // --- the bound ----------------------------------------------------------

    @Test
    fun `an evicted photo is refetched rather than shown wrongly`() = runTest {
        // Only one photo fits, so the second write evicts the first.
        val small = 300L
        val one = row(photoRev = 1.0)
        val two = StockRecord(
            documentId = "boomBarriers|BB200", key = "boomBarriers|BB200",
            hasPhoto = true, photoRev = 1.0
        )
        val store = object : StockPhotoStore {
            var reads = 0
            override suspend fun read(documentId: String): StockPhotoRecord {
                reads++
                return StockPhotoRecord(
                    documentId = documentId, key = documentId, bytes = webp(), rev = 1.0
                )
            }
        }
        val live = StockPhotoRepository(store, StockPhotoCache(), files(small))

        live.load(one)
        live.load(two)
        assertEquals(2, store.reads)

        // Memory still holds both, so nothing is re-read here…
        assertNotNull(live.load(one))
        assertEquals(2, store.reads)

        // …but after a restart the evicted one has to come back off the wire.
        val afterRestart = repository(FakeStore(stored(rev = 1.0)), small)
        assertNotNull(afterRestart.load(one))
    }

    @Test
    fun `the directory never grows past the bound`() = runTest {
        val store = object : StockPhotoStore {
            override suspend fun read(documentId: String) = StockPhotoRecord(
                documentId = documentId, key = documentId, bytes = webp(payload = 1_024), rev = 1.0
            )
        }
        val live = StockPhotoRepository(store, StockPhotoCache(), files(maxBytes = 5_000L))

        repeat(30) { index ->
            live.load(
                StockRecord(
                    documentId = "row|$index", key = "row|$index",
                    hasPhoto = true, photoRev = 1.0
                )
            )
        }

        val onDisk = folder.root.listFiles()!!.filter { it.isFile }.sumOf { it.length() }
        assertTrue("disk grew to $onDisk", onDisk <= 5_000)
        assertTrue("and it is being used", folder.root.listFiles()!!.any { it.isFile })
    }
}
