package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.core.AccountStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An account's drafts, and the keys that decide whose they are.
 *
 * **The defect behind all of this is a rate leak.** The draft and the
 * uncommitted stock counts were stored under one global key each, and signing
 * out clears Firebase's credentials but no device preference — so the next
 * account to sign in on that phone opened the last one's quotation, customer
 * and rates. A draft carries rates and a Staff account may not see rates
 * anywhere in this app, which makes it a permission failure that happens to
 * live on disk.
 *
 * Keying by account rather than clearing on sign-out is the deliberate half:
 * a draft must survive sign-out and sign-in as the **same** account, because
 * on a shared phone signing out is the ordinary way to hand over.
 */
class QuoteDraftsTest {

    private fun draft(id: String, at: Long = 0L, title: String = "Motor") =
        QuoteDraft(id = id, updatedAt = at).addManual(id = "ln_$id", title = title, rate = 100.0)

    // --- whose keys are whose -------------------------------------------------------

    @Test
    fun `two accounts on one phone do not share a key`() {
        assertNotEquals(AccountStorage.draftsKey("uid_a"), AccountStorage.draftsKey("uid_b"))
        assertNotEquals(AccountStorage.pendingKey("uid_a"), AccountStorage.pendingKey("uid_b"))
        // And neither is the old ownerless key that caused this.
        assertNotEquals(AccountStorage.LEGACY_DRAFT_KEY, AccountStorage.draftsKey("uid_a"))
        assertNotEquals(AccountStorage.LEGACY_PENDING_KEY, AccountStorage.pendingKey("uid_a"))
    }

    @Test
    fun `the same account signing back in gets the same key, so its work survives`() {
        assertEquals(AccountStorage.draftsKey("uid_a"), AccountStorage.draftsKey("uid_a"))
        assertEquals(AccountStorage.pendingKey("uid_a"), AccountStorage.pendingKey("uid_a"))
    }

    // --- the one-time move ------------------------------------------------------------

    @Test
    fun `an ownerless draft is adopted by the account that signs in`() {
        assertEquals(
            AccountStorage.Move.Adopt("stored"),
            AccountStorage.moveFor(alreadyMoved = false, legacy = "stored", existing = null)
        )
    }

    @Test
    fun `it runs once and not twice`() {
        assertEquals(
            AccountStorage.Move.None,
            AccountStorage.moveFor(alreadyMoved = true, legacy = "stored", existing = null)
        )
    }

    @Test
    fun `an account that already has its own value keeps it`() {
        assertEquals(
            AccountStorage.Move.DropOnly,
            AccountStorage.moveFor(alreadyMoved = false, legacy = "stored", existing = "mine")
        )
    }

    @Test
    fun `there is nothing to move when the old key was empty`() {
        assertEquals(
            AccountStorage.Move.None,
            AccountStorage.moveFor(alreadyMoved = false, legacy = null, existing = null)
        )
        assertEquals(
            AccountStorage.Move.None,
            AccountStorage.moveFor(alreadyMoved = false, legacy = "", existing = null)
        )
    }

    // --- the collection ----------------------------------------------------------------

    @Test
    fun `saving the same draft twice replaces it rather than storing a twin`() {
        val first = draft("qd_1", at = 1L)
        val second = first.copy(updatedAt = 2L)
        val drafts = QuoteDrafts().save(first).save(second)

        assertEquals(1, drafts.drafts.size)
        assertEquals(2L, drafts.drafts.single().updatedAt)
        assertEquals("qd_1", drafts.currentId)
    }

    @Test
    fun `the current draft is the named one`() {
        val drafts = QuoteDrafts().save(draft("qd_1", at = 9L)).save(draft("qd_2", at = 1L))
        assertEquals("qd_2", drafts.current!!.id)
    }

    @Test
    fun `and falls back to the most recently touched when the name is gone`() {
        // A finalised draft is removed by N5.9, so the name it left behind
        // must not leave the builder with nothing.
        val drafts = QuoteDrafts()
            .save(draft("qd_1", at = 5L))
            .save(draft("qd_2", at = 9L))
            .remove("qd_2")

        assertEquals("", drafts.currentId)
        assertEquals("qd_1", drafts.current!!.id)
    }

    @Test
    fun `an empty collection has no current draft, rather than an empty one`() {
        assertNull(QuoteDrafts().current)
        assertTrue(QuoteDrafts().isEmpty)
    }

    @Test
    fun `a runaway is refused, never pruned`() {
        // Nothing prunes: quietly discarding somebody's quotation is worse
        // than an unbounded store, so the guard refuses instead.
        val full = QuoteDrafts(drafts = (1..QuoteDrafts.MAX).map { draft("qd_$it") })
        assertEquals(QuoteDrafts.TOO_MANY, full.refusalToAdd())
        assertNull(QuoteDrafts(drafts = (1..3).map { draft("qd_$it") }).refusalToAdd())
    }

    // --- storing the collection ----------------------------------------------------------

    @Test
    fun `a collection survives a round trip, with its current draft named`() {
        val drafts = QuoteDrafts().save(draft("qd_1", at = 1L)).save(draft("qd_2", at = 2L))
        val restored = QuoteDraftsCodec.decode(QuoteDraftsCodec.encode(drafts))

        assertEquals(drafts, restored)
        assertEquals("qd_2", restored.currentId)
        assertEquals(2, restored.drafts.size)
    }

    @Test
    fun `a draft carrying the collection's own separator survives`() {
        // The collection escapes each encoded draft whole, so it never has to
        // know which separators a draft uses.
        val odd = QuoteDraft(id = "qd_1")
            .addManual(id = "ln_1", title = "Odd\u001dname", rate = 10.0)
        val restored = QuoteDraftsCodec.decode(
            QuoteDraftsCodec.encode(QuoteDrafts().save(odd))
        )
        assertEquals("Odd\u001dname", restored.drafts.single().lines.single().title)
    }

    @Test
    fun `an empty or unreadable store decodes to no drafts rather than throwing`() {
        assertEquals(QuoteDrafts(), QuoteDraftsCodec.decode(null))
        assertEquals(QuoteDrafts(), QuoteDraftsCodec.decode(""))
        assertEquals(QuoteDrafts(), QuoteDraftsCodec.decode("rubbish"))
        assertEquals(QuoteDrafts(), QuoteDraftsCodec.decode("c9\u001dqd_1"))
    }

    @Test
    fun `a draft with no id is dropped, because nothing could name it again`() {
        val nameless = QuoteDrafts(drafts = listOf(QuoteDraft().addManual("ln_1", "X", rate = 1.0)))
        assertTrue(QuoteDraftsCodec.decode(QuoteDraftsCodec.encode(nameless)).isEmpty)
    }

    @Test
    fun `an empty collection round trips as empty`() {
        assertEquals(QuoteDrafts(), QuoteDraftsCodec.decode(QuoteDraftsCodec.encode(QuoteDrafts())))
    }

    // --- resuming a draft, which is where two drafts used to come from ----------

    @Test
    fun `an emptied draft keeps its id, so the next line does not start a second one`() {
        // The first of the two paths. `persist` runs when the last line is
        // removed, so an EMPTY draft with an id is stored. Skipping the
        // assignment because it is empty left the screen holding no id, and
        // the next add minted a new one — one quotation, two drafts.
        val stored = QuoteDraft(id = "qd_1", updatedAt = 7L)
        assertTrue(stored.isEmpty)

        val resumed = QuoteDrafts.resume(edited = QuoteDraft(), stored = stored, id = "qd_1")

        assertEquals("qd_1", resumed.id)
        assertTrue(resumed.isEmpty)

        // And the line added next lands on that same draft, not a second.
        val after = QuoteDrafts().save(stored).save(resumed.addManual("ln_1", "Motor", rate = 10.0))
        assertEquals(1, after.drafts.size)
        assertEquals("qd_1", after.drafts.single().id)
    }

    @Test
    fun `a line added while the store was still loading is not lost`() {
        // The second path, and the one that mattered more: it was silent data
        // loss. A straight assignment replaced what the person had just typed
        // with the stored draft, and left an orphan behind.
        val typedMeanwhile = QuoteDraft().addManual("ln_new", "Site visit", rate = 2000.0)
        val stored = QuoteDraft(id = "qd_1")
            .addManual("ln_old", "Motor", rate = 100.0)

        val resumed = QuoteDrafts.resume(typedMeanwhile, stored, "qd_1")

        assertEquals("qd_1", resumed.id)
        assertEquals(2, resumed.lineCount)
        assertTrue(resumed.lines.any { it.id == "ln_old" })
        assertTrue(resumed.lines.any { it.id == "ln_new" })
    }

    @Test
    fun `with nothing stored, what the person typed is kept under the resolved id`() {
        val typed = QuoteDraft().addManual("ln_1", "Site visit", rate = 2000.0)
        val resumed = QuoteDrafts.resume(typed, stored = null, id = "qd_9")

        assertEquals("qd_9", resumed.id)
        assertEquals(1, resumed.lineCount)
    }

    @Test
    fun `resuming never invents an id of its own`() {
        // Whatever the inputs, the id is the one it was handed. Nothing here
        // can mint, which is the whole point of moving the decision out of
        // the view model.
        listOf(
            QuoteDrafts.resume(QuoteDraft(), null, "qd_x"),
            QuoteDrafts.resume(QuoteDraft(id = "stale"), QuoteDraft(id = "other"), "qd_x"),
            QuoteDrafts.resume(
                QuoteDraft().addManual("a", "A", rate = 1.0),
                QuoteDraft(id = "other").addManual("b", "B", rate = 1.0),
                "qd_x"
            )
        ).forEach { assertEquals("qd_x", it.id) }
    }

    @Test
    fun `an empty stored draft is still the current one`() {
        // What `currentDraftId` relies on: a draft with no lines is a real
        // draft with a real id, and must be found rather than replaced.
        val drafts = QuoteDrafts().save(QuoteDraft(id = "qd_1"))
        assertEquals("qd_1", drafts.current!!.id)
        assertEquals("qd_1", drafts.currentId)
    }

    @Test
    fun `selecting pins the fallback, so the same id comes back twice running`() {
        // Without this, a collection whose named draft had been finalised
        // would fall back on every read, and two reads could disagree.
        val drafts = QuoteDrafts()
            .save(draft("qd_1", at = 5L))
            .save(draft("qd_2", at = 9L))
            .remove("qd_2")
        assertEquals("", drafts.currentId)

        val pinned = drafts.select(drafts.current!!.id)
        assertEquals("qd_1", pinned.currentId)
        assertEquals("qd_1", pinned.current!!.id)
    }
}
