package `in`.smartie.quotedesk.domain

/**
 * Every quotation an account has in progress, and which one is on screen.
 *
 * **A collection from the first commit that stores one, deliberately.** N5.8b
 * ships no drafts list and shows one draft at a time, so in practice this
 * holds exactly one — the builder always reuses the current draft. Storing a
 * collection anyway is what makes adding that list later a screen-only change
 * that never touches persistence again.
 *
 * **Growth is therefore zero until a list exists**, which is why there is no
 * age-out and nothing is ever dropped to make room. [MAX] is a guard against a
 * bug, not a policy: going past it is **refused**, never silently pruned,
 * because quietly discarding somebody's quotation is worse than an unbounded
 * store.
 */
data class QuoteDrafts(
    val drafts: List<QuoteDraft> = emptyList(),
    /** Which draft the builder is on. May name one that is gone. */
    val currentId: String = ""
) {
    /**
     * The draft the builder shows.
     *
     * Named first; then, when the name is missing or points at a draft that
     * has been finalised or cleared, the most recently touched one. Null only
     * when there are none at all, and the builder makes one lazily on the
     * first line rather than keeping an empty draft around.
     */
    val current: QuoteDraft?
        get() = drafts.firstOrNull { it.id == currentId && it.id.isNotBlank() }
            ?: drafts.maxByOrNull { it.updatedAt }

    val isEmpty: Boolean get() = drafts.isEmpty()

    operator fun get(id: String): QuoteDraft? = drafts.firstOrNull { it.id == id }

    /**
     * Adds or replaces one draft and makes it current.
     *
     * Matched on [QuoteDraft.id], so saving the same draft twice replaces it
     * rather than storing a twin — the N4.4 B2 shape once more, this time a
     * level up from the lines.
     */
    fun save(draft: QuoteDraft): QuoteDrafts {
        val existing = drafts.indexOfFirst { it.id == draft.id }
        val updated = if (existing >= 0) {
            drafts.toMutableList().also { it[existing] = draft }
        } else {
            drafts + draft
        }
        return copy(drafts = updated, currentId = draft.id)
    }

    fun remove(id: String): QuoteDrafts = copy(
        drafts = drafts.filterNot { it.id == id },
        currentId = if (currentId == id) "" else currentId
    )

    fun select(id: String): QuoteDrafts = copy(currentId = id)

    /**
     * A draft that has been **finalised**, taken out of the collection so the
     * next quotation starts under a **new id**.
     *
     * **Never `QuoteDraft.clear()` for this.** A cleared draft keeps its id,
     * and finalise's read-first looks the id up: the next quotation built on
     * it would be answered with the previous one's number and never issued.
     * V8C4 gets the same result by clearing `state.draftId` on success
     * ("this record is closed") before it clears the draft; here the id and
     * the draft go together, in one call, so there is no moment when one has
     * gone and the other has not.
     *
     * If another draft remains, the builder moves to it by the same fallback
     * [current] already uses; otherwise an empty draft under [freshId] becomes
     * current. [freshId] is the caller's, minted before any store transform
     * that runs this — the rule `currentDraftId` states — and is unused when
     * a draft remains. So retiring the same id twice mints nothing the second
     * time: a finalise that is answered `AlreadyIssued` after a crash cannot
     * pile up empty drafts.
     */
    fun retire(finalisedId: String, freshId: String): QuoteDrafts {
        require(freshId.isNotBlank() && freshId != finalisedId) {
            "A retired draft's successor needs an id of its own"
        }
        val without = remove(finalisedId)
        val remaining = without.current
        return if (remaining != null) without.select(remaining.id)
        else without.save(QuoteDraft(id = freshId))
    }

    /**
     * Why another draft cannot be started, or null when one can.
     *
     * **NOT CALLED BY ANYTHING, so the cap is not enforced at runtime today.**
     * Nothing can create a second draft: the builder always reuses the one
     * `AccountPreferences.currentDraftId()` resolves, so there is no honest
     * call site to give this yet and wiring one would mean inventing a caller.
     *
     * It becomes live in the batch that ships a **drafts list** — the first
     * thing that can start a second quotation. That batch is not scheduled;
     * it is recorded as owed in `docs/PROJECT-STATUS.md`. Whoever builds it
     * calls this before [save] and surfaces [TOO_MANY].
     */
    fun refusalToAdd(): String? =
        if (drafts.size >= MAX) TOO_MANY else null

    companion object {
        /** Draft ids read as `qd_…`, the same shape as every other id. */
        const val DRAFT_PREFIX = "qd_"

        /**
         * Reconciling what was stored with what somebody typed while it was
         * still loading.
         *
         * Reading the store is asynchronous, so a screen can be tapped before
         * it answers. [edited] is whatever the screen has built meanwhile and
         * [stored] is what came back. **Neither set of lines is ever
         * discarded**, and the resolved [id] is always adopted — never minted
         * — so an in-progress quotation cannot become two drafts.
         *
         * **The caller must reprice after this, with
         * [QuoteDraft.alignLinesToTier] and not with `withTier`.** The resolved
         * draft takes the *stored* tier, while any line typed meanwhile was
         * priced at whatever tier the screen was showing before the store
         * answered — the default Client. So a Dealer quotation can come out of
         * here holding a Client-priced line. That is the overquote direction,
         * and so the safe one, but it is still wrong.
         *
         * `withTier(resolved.tier, priceOf)` looks like the fix and **is a
         * no-op**: it returns early when the tier is not changing, and here
         * the tier is already right — it is the lines that are not.
         * [QuoteDraft.alignLinesToTier] is the one that hangs the reprice on
         * the line's own tier instead, and [QuoteDraft.hasLinesOutOfStep] says
         * whether there is anything to do.
         *
         * Pure, because the view model that needs it cannot be unit-tested:
         * it takes an `AppContainer`. Putting the decision here is what makes
         * the two failure paths testable at all.
         */
        fun resume(edited: QuoteDraft, stored: QuoteDraft?, id: String): QuoteDraft = when {
            // Nothing was stored: keep what the person has, under the
            // resolved id.
            stored == null -> edited.copy(id = id)
            // Nothing was typed meanwhile: the stored draft stands, empty or
            // not. Adopting it **even when empty** is the half that stops a
            // second draft being minted on the next edit.
            edited.isEmpty -> stored.copy(id = id)
            // Both hold lines, which means the person added one while this
            // was loading. Keeping only one side is silent data loss, so
            // neither is dropped — but a blind concatenation is not a merge.
            else -> stored.copy(id = id, lines = mergedLines(stored, edited))
        }

        /**
         * Two sets of lines joined by **the same rule `QuoteDraft.add` uses**.
         *
         * A blind `stored.lines + edited.lines` broke the rule that adding the
         * same catalogue product twice never produces two of it: a product in
         * both sets arrived as two lines with the same title, unit and rate —
         * and the same quantity when both were one. Indistinguishable on
         * screen, and different ids underneath.
         *
         * So a catalogue product found in both has its quantities summed, and
         * manual and area lines are never merged, because two openings of one
         * product are two lines and two hand-typed lines are two lines. One
         * rule with two callers, rather than two rules that disagree.
         */
        private fun mergedLines(stored: QuoteDraft, edited: QuoteDraft): List<DraftLine> {
            var lines = stored.lines
            for (line in edited.lines) {
                val existing = lines.firstOrNull {
                    it.key.isNotBlank() && it.key == line.key && !it.manual && it.area == null
                }
                lines = if (existing != null && !line.manual && line.area == null) {
                    lines.map {
                        if (it.id == existing.id) {
                            it.copy(quantity = it.quantity + line.quantity)
                        } else {
                            it
                        }
                    }
                } else {
                    lines + line
                }
            }
            return lines
        }

        /**
         * A guard against a bug, not a policy. Nothing creates drafts in bulk,
         * and nothing prunes them: somebody's quotation is never deleted to
         * make room.
         *
         * **The refusal this bounds is not wired up yet** — see
         * [refusalToAdd], which nothing calls. An earlier version of this
         * comment said going past it "is refused", which was true of the
         * design and false of the running app. The distinction matters: today
         * a bug that created drafts in bulk would not be stopped here, it
         * would simply store them.
         */
        const val MAX = 50

        const val TOO_MANY = "Finish or clear a quotation before starting another"
    }
}
