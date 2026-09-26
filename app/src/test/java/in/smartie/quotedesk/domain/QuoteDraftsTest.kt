package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.core.AccountStorage
import `in`.smartie.quotedesk.data.model.RateTierV2
import `in`.smartie.quotedesk.data.model.ProductRecord
import `in`.smartie.quotedesk.data.mapping.Keys
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

    private fun product(seedModel: String) = ProductRecord(
        documentId = Keys.productDocId("gate", seedModel),
        key = Keys.productKey("gate", seedModel),
        group = "gate",
        seedModel = seedModel,
        model = seedModel,
        dealer = 100.0,
        client = 140.0
    )

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

    // --- retiring a finalised draft (N5.9b) ----------------------------------------

    @Test
    fun `a finalised draft is retired, and the next quotation starts under a new id`() {
        val drafts = QuoteDrafts().save(draft("qd_1", at = 5L))

        val after = drafts.retire(finalisedId = "qd_1", freshId = "qd_2")

        assertEquals(listOf("qd_2"), after.drafts.map { it.id })
        assertEquals("qd_2", after.currentId)
        assertEquals("qd_2", after.current!!.id)
        assertTrue(after.current!!.isEmpty)
        assertNull(after["qd_1"])
    }

    @Test
    fun `clear keeps the id, retire never does - which is why finalise retires`() {
        // The whole reason `retire` exists. Finalise's read-first looks the
        // draft's id up; a cleared draft keeps it, so the next quotation built
        // on it would be answered with the previous one's number and never
        // issued. The Owner's warning, pinned as the difference between the
        // two calls.
        val finalised = draft("qd_1", at = 5L)
        assertEquals("qd_1", finalised.clear().id)
        assertEquals("qd_1", QuoteDrafts().save(finalised.clear()).current!!.id)

        val retired = QuoteDrafts().save(finalised).retire("qd_1", "qd_2")
        assertNotEquals("qd_1", retired.current!!.id)
        assertTrue(retired.drafts.none { it.id == "qd_1" })
    }

    @Test
    fun `retiring the same draft twice mints nothing the second time`() {
        // An `AlreadyIssued` after a crash retires again. It must not leave a
        // trail of empty drafts behind it.
        val once = QuoteDrafts().save(draft("qd_1")).retire("qd_1", "qd_2")
        val twice = once.retire("qd_1", "qd_3")

        assertEquals(listOf("qd_2"), twice.drafts.map { it.id })
        assertEquals("qd_2", twice.currentId)
    }

    @Test
    fun `another draft in progress becomes current rather than a fresh one`() {
        // Not reachable while there is one draft per account, and decided now
        // so the drafts list that makes it reachable inherits a rule.
        val drafts = QuoteDrafts()
            .save(draft("qd_other", at = 3L))
            .save(draft("qd_1", at = 9L))

        val after = drafts.retire("qd_1", "qd_fresh")

        assertEquals(listOf("qd_other"), after.drafts.map { it.id })
        assertEquals("qd_other", after.currentId)
    }

    @Test
    fun `a successor can never share the finalised id`() {
        val drafts = QuoteDrafts().save(draft("qd_1"))
        listOf("qd_1", "").forEach { bad ->
            val refused = runCatching { drafts.retire("qd_1", bad) }.exceptionOrNull()
            assertTrue("$bad was accepted", refused is IllegalArgumentException)
        }
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

    // --- resume follows add's own merge rule ------------------------------------

    @Test
    fun `a product in both sets becomes one line, not two that look identical`() {
        // The hole in the merge. A blind concatenation produced two lines with
        // the same title, unit and rate — and the same quantity when both were
        // one. Indistinguishable on screen, different ids underneath, and the
        // rule that adding the same product twice never makes two of it broken
        // by a path that rule never looked at.
        val motor = product("SIE1000")
        val stored = QuoteDraft(id = "qd_1").add(motor, id = "ln_stored")
        val typedMeanwhile = QuoteDraft().add(motor, id = "ln_typed")

        val resumed = QuoteDrafts.resume(typedMeanwhile, stored, "qd_1")

        assertEquals(1, resumed.lineCount)
        assertEquals(2.0, resumed.quantityOf(motor.key), 0.0)
        assertEquals("ln_stored", resumed.lines.single().id)
    }

    @Test
    fun `but two hand-typed lines stay two, and two openings stay two`() {
        // The other half of the same rule: merging is right for a catalogue
        // tap and wrong for everything else.
        val stored = QuoteDraft(id = "qd_1").addManual("ln_a", "Site visit", rate = 2000.0)
        val typed = QuoteDraft().addManual("ln_b", "Site visit", rate = 2000.0)
        assertEquals(2, QuoteDrafts.resume(typed, stored, "qd_1").lineCount)

        val opening = AreaLine(width = 3000.0, height = 3500.0)
        val storedArea = QuoteDraft(id = "qd_1")
            .addArea("ln_c", opening, 450.0, "Shutter", key = "rs|RS500")
        val typedArea = QuoteDraft()
            .addArea("ln_d", opening, 450.0, "Shutter", key = "rs|RS500")
        assertEquals(2, QuoteDrafts.resume(typedArea, storedArea, "qd_1").lineCount)
    }

    @Test
    fun `a different product typed meanwhile is added rather than merged`() {
        val stored = QuoteDraft(id = "qd_1").add(product("SIE1000"), id = "ln_1")
        val typed = QuoteDraft().add(product("SIE600"), id = "ln_2")

        val resumed = QuoteDrafts.resume(typed, stored, "qd_1")
        assertEquals(2, resumed.lineCount)
    }

    @Test
    fun `the resolved draft keeps the stored tier, so the caller must reprice`() {
        // The second fault. Lines typed before the store answered were priced
        // at whatever the screen showed — the default Client — while the
        // resolved draft takes the stored tier. A Dealer quotation holding a
        // Client-priced line is the overquote direction, so the safe one, but
        // it is still wrong; `withTier` is what puts it right.
        val motor = product("SIE1000")
        val stored = QuoteDraft(id = "qd_1", tier = RateTierV2.DEALER)
            .add(motor, id = "ln_1")
        val typed = QuoteDraft(tier = RateTierV2.CLIENT).add(product("SIE600"), id = "ln_2")

        val resumed = QuoteDrafts.resume(typed, stored, "qd_1")
        assertEquals(RateTierV2.DEALER, resumed.tier)
        assertEquals(RateTierV2.CLIENT, resumed.line("ln_2")!!.tier)

        // The draft says so itself, rather than the caller having to look.
        assertTrue(resumed.hasLinesOutOfStep)
    }

    @Test
    fun `withTier cannot fix it, which is why alignLinesToTier exists`() {
        // The fix this KDoc first prescribed. `withTier` returns early when
        // the tier is not changing — and after `resume` the tier is already
        // right and the LINES are not, so the prescribed fix did nothing at
        // all. Asserted here so nobody reinstates it.
        val motor = product("SIE1000")
        val stored = QuoteDraft(id = "qd_1", tier = RateTierV2.DEALER).add(motor, id = "ln_1")
        val typed = QuoteDraft(tier = RateTierV2.CLIENT).add(product("SIE600"), id = "ln_2")
        val resumed = QuoteDrafts.resume(typed, stored, "qd_1")

        val viaWithTier = resumed.withTier(RateTierV2.DEALER) { 100.0 }
        assertEquals(0, viaWithTier.repriced)
        assertEquals(RateTierV2.CLIENT, viaWithTier.draft.line("ln_2")!!.tier)
        assertTrue(viaWithTier.draft.hasLinesOutOfStep)

        val aligned = resumed.alignLinesToTier { 100.0 }
        assertEquals(1, aligned.repriced)
        assertEquals(RateTierV2.DEALER, aligned.draft.line("ln_2")!!.tier)
        assertEquals(100.0, aligned.draft.line("ln_2")!!.rate!!, 0.0)
        // The line that was already in step is untouched, not repriced twice.
        assertEquals(100.0, aligned.draft.line("ln_1")!!.rate!!, 0.0)
        assertTrue(!aligned.draft.hasLinesOutOfStep)
    }

    @Test
    fun `a hand-typed rate is kept at its own tier, never realigned`() {
        // Its rate was struck at the tier it carries. Recording a different
        // one would misstate what was quoted, so the line is counted as kept
        // and left exactly as it is.
        val stored = QuoteDraft(id = "qd_1", tier = RateTierV2.DEALER)
            .addManual("ln_1", "Site visit", rate = 2000.0)
        val typed = QuoteDraft(tier = RateTierV2.CLIENT).addManual("ln_2", "Crane", rate = 9000.0)
        val resumed = QuoteDrafts.resume(typed, stored, "qd_1")

        val aligned = resumed.alignLinesToTier { 1.0 }
        assertEquals(0, aligned.repriced)
        assertEquals(1, aligned.kept)
        assertEquals(9000.0, aligned.draft.line("ln_2")!!.rate!!, 0.0)
        assertEquals(RateTierV2.CLIENT, aligned.draft.line("ln_2")!!.tier)
        // And it never asks again, because nothing here can be put right.
        assertTrue(!resumed.hasLinesOutOfStep)
    }

    @Test
    fun `a CATALOGUE line whose rate was typed survives both mechanisms`() {
        // **The test that tells `rateEdited` from `manual`.** The one above
        // uses `addManual`, which sets both — so it would still pass if
        // `cataloguePriced` had been written `!manual && key.isNotBlank()`,
        // and a hand-edited catalogue rate would then be silently overwritten
        // by `alignLinesToTier`. This line is a catalogue line: it has a
        // product key, it is not manual, and only `rateEdited` protects it.
        //
        // The Owner's ruling is that a tier change must never silently move a
        // rate somebody typed. `withTier` honoured it; `alignLinesToTier` is a
        // different mechanism, so it has to be shown honouring it too.
        val motor = product("SIE1000")
        val stored = QuoteDraft(id = "qd_1", tier = RateTierV2.DEALER)
            .add(product("SIE600"), id = "ln_1")

        // Tapped in before the store answered, at the default Client tier,
        // and then given a rate by hand.
        val typedMeanwhile = QuoteDraft(tier = RateTierV2.CLIENT)
            .add(motor, id = "ln_2")
            .setRate("ln_2", 999.0)

        val resumed = QuoteDrafts.resume(typedMeanwhile, stored, "qd_1")
        val line = resumed.line("ln_2")!!
        assertTrue("a catalogue line, not a manual one", !line.manual)
        assertTrue("with a product key", line.key.isNotBlank())
        assertTrue("whose rate was typed", line.rateEdited)

        // 1. The realignment after `resume` leaves it alone.
        val aligned = resumed.alignLinesToTier { 100.0 }
        assertEquals(999.0, aligned.draft.line("ln_2")!!.rate!!, 0.0)
        assertEquals(RateTierV2.CLIENT, aligned.draft.line("ln_2")!!.tier)
        assertEquals(1, aligned.kept)

        // 2. And so does an ordinary change of tier, afterwards.
        val retiered = aligned.draft.withTier(RateTierV2.CLIENT) { 100.0 }
        assertEquals(999.0, retiered.draft.line("ln_2")!!.rate!!, 0.0)
        assertTrue("counted as kept, not repriced", retiered.kept >= 1)

        // And it never asks to be realigned, so nothing keeps trying.
        assertTrue(!aligned.draft.hasLinesOutOfStep)
    }

    @Test
    fun `an ordinary draft has nothing to align, so the fix costs nothing`() {
        val draft = QuoteDraft(id = "qd_1", tier = RateTierV2.DEALER).add(product("SIE1000"))
        assertTrue(!draft.hasLinesOutOfStep)
        val aligned = draft.alignLinesToTier { error("nothing should be priced") }
        assertEquals(0, aligned.repriced)
        assertEquals(0, aligned.kept)
        assertEquals(draft, aligned.draft)
    }

    @Test
    fun `a product that has left the catalogue prices to nothing, not to zero`() {
        val stored = QuoteDraft(id = "qd_1", tier = RateTierV2.DEALER)
            .addManual("ln_0", "Motor", rate = 100.0)
        val typed = QuoteDraft(tier = RateTierV2.CLIENT).add(product("SIE600"), id = "ln_2")
        val resumed = QuoteDrafts.resume(typed, stored, "qd_1")

        val aligned = resumed.alignLinesToTier { null }.draft
        assertNull(aligned.line("ln_2")!!.rate)
        assertTrue(aligned.line("ln_2")!!.needsRate)
        assertEquals(QuoteDraft.LINE_NEEDS_RATE, aligned.refusal(QuoteMath.NO_CAP))
    }

    // --- the transport note, which 8a had nowhere to put --------------------------

    @Test
    fun `the transport note survives a round trip`() {
        val drafts = QuoteDrafts().save(
            QuoteDraft(id = "qd_1", transport = 2500.0, transportNote = "Mumbai to Vadodara")
                .addManual("ln_1", "Motor", rate = 100.0)
        )
        val restored = QuoteDraftsCodec.decode(QuoteDraftsCodec.encode(drafts))
        assertEquals("Mumbai to Vadodara", restored.drafts.single().transportNote)
        assertEquals(2500.0, restored.drafts.single().transport, 0.0)
    }

    @Test
    fun `a draft stored before the note existed decodes with an empty one`() {
        // Appended, not inserted, so nothing already on a phone is disturbed.
        // The head is rebuilt one field short, exactly as it was written
        // before `transportNote` existed.
        val before = QuoteDraft(id = "qd_1").addManual("ln_1", "Motor", rate = 100.0)
        val encoded = QuoteDraftCodec.encode(before)
        val head = encoded.substringBefore('\u001e')
        val records = encoded.substring(head.length)
        val shorter = head.split('\u001f').dropLast(1).joinToString("\u001f")

        val restored = QuoteDraftCodec.decode(shorter + records)
        assertEquals("", restored.transportNote)
        assertEquals("qd_1", restored.id)
        assertEquals(1, restored.lineCount)
    }
}
