package `in`.smartie.quotedesk.ui

import `in`.smartie.quotedesk.ui.products.DraftWrites
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The id a draft is saved under, and the order saves and a retire run in.
 *
 * The failure this guards against is the one the Owner warned of: a
 * quotation answered with the previous one's number. It needs no race to
 * happen — a save after the retire, holding the old id, is enough — so
 * these tests pin the ordering, not a timing.
 */
class DraftWritesTest {

    /** Lets every coroutine that can make progress do so. */
    private suspend fun settle() = repeat(10) { yield() }

    @Test
    fun `nothing is saved until the store has answered`() = runBlocking {
        val writes = DraftWrites()
        val saved = mutableListOf<String>()

        val save = launch { writes.withCurrent { saved += it } }
        settle()
        assertTrue("saved before the id existed: $saved", saved.isEmpty())
        assertNull(writes.current)

        writes.resolve("qd_1")
        save.join()
        assertEquals(listOf("qd_1"), saved)
    }

    @Test
    fun `a save queued behind a retire writes the new id, never the finalised one`() = runBlocking {
        val writes = DraftWrites().apply { resolve("qd_1") }
        val saved = mutableListOf<String>()
        val retireMayFinish = CompletableDeferred<Unit>()

        val retire = launch {
            writes.replace { retiring ->
                assertEquals("qd_1", retiring)
                retireMayFinish.await()
                "qd_2"
            }
        }
        settle()
        val save = launch { writes.withCurrent { saved += it } }
        settle()
        assertTrue("a save ran while the retire held the lock: $saved", saved.isEmpty())

        retireMayFinish.complete(Unit)
        retire.join()
        save.join()
        assertEquals(listOf("qd_2"), saved)
        assertEquals("qd_2", writes.current)
    }

    @Test
    fun `a save already in flight finishes before the retire starts`() = runBlocking {
        // The second hazard: a save holding the finalised id when the draft is
        // retired would write it straight back. Here it finishes first, so the
        // retire removes what it wrote.
        val writes = DraftWrites().apply { resolve("qd_1") }
        val order = mutableListOf<String>()
        val saveMayFinish = CompletableDeferred<Unit>()

        val save = launch {
            writes.withCurrent { id ->
                order += "save started on $id"
                saveMayFinish.await()
                order += "save finished on $id"
            }
        }
        settle()
        val retire = launch {
            writes.replace { retiring ->
                order += "retire of $retiring"
                "qd_2"
            }
        }
        settle()
        assertEquals(listOf("save started on qd_1"), order)

        saveMayFinish.complete(Unit)
        save.join()
        retire.join()
        assertEquals(
            listOf("save started on qd_1", "save finished on qd_1", "retire of qd_1"),
            order
        )
    }

    @Test
    fun `the first answer wins, so a late one never undoes a retire`() = runBlocking {
        val writes = DraftWrites()
        writes.resolve("qd_1")
        writes.replace { "qd_2" }
        writes.resolve("qd_1")

        assertEquals("qd_2", writes.current)
        assertEquals("qd_2", writes.withCurrent { it })
    }

    @Test
    fun `an unreadable store resolves to blank, and a save is told so`() = runBlocking {
        // The caller skips saving under a blank id, as it always has; this
        // class never invents one to carry on with.
        val writes = DraftWrites().apply { resolve("") }
        assertEquals("", writes.withCurrent { it })
    }
}
