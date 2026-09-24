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

    /** Why another draft cannot be started, or null when one can. */
    fun refusalToAdd(): String? =
        if (drafts.size >= MAX) TOO_MANY else null

    companion object {
        /** Draft ids read as `qd_…`, the same shape as every other id. */
        const val DRAFT_PREFIX = "qd_"

        /**
         * A guard against a bug, not a policy. Nothing creates drafts in bulk,
         * and nothing prunes them: going past this is refused so a runaway is
         * visible, and somebody's quotation is never deleted to make room.
         */
        const val MAX = 50

        const val TOO_MANY = "Finish or clear a quotation before starting another"
    }
}
