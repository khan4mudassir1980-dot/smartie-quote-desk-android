package `in`.smartie.quotedesk.domain

/** What a pin change came to, so the screen knows what to say. */
sealed interface PinChange {
    /** The new list, ready to be written. */
    data class Updated(val keys: List<String>) : PinChange

    /** Already at the cap; the PWA refuses the sixteenth. */
    data object Full : PinChange

    /** Nothing to do — already pinned, not pinned, or already at the end. */
    data object Unchanged : PinChange
}

/**
 * The shared pinned shelf, `teamSettings/productPins`.
 *
 * Ordering lives here rather than in the repository so it can be tested
 * without Firebase. Two of the audit's P4 complaints are decided in this file:
 * a new pin is **appended**, as the PWA does, not inserted at the top as the
 * native beta did, and the cap is enforced before the write rather than left
 * to the rules to reject.
 */
object ProductPins {

    /** `teamSettings/productPins` rules: `keys is list && keys.size() <= 15`. */
    const val MAX: Int = Catalogue.MAX_PINS

    /** The PWA's wording when the shelf is full (`index.html:7680`). */
    const val FULL_MESSAGE: String = "You can pin up to 15 products"

    const val PINNED_MESSAGE: String = "Added to Pinned Products"

    const val UNPINNED_MESSAGE: String = "Removed from Pinned Products"

    fun isPinned(keys: List<String>, key: String): Boolean = key in keys

    /** Appended, never inserted at the top (audit P4). */
    fun pin(keys: List<String>, key: String): PinChange = when {
        key.isBlank() || key in keys -> PinChange.Unchanged
        keys.size >= MAX -> PinChange.Full
        else -> PinChange.Updated(keys + key)
    }

    fun unpin(keys: List<String>, key: String): PinChange =
        if (key in keys) PinChange.Updated(keys - key) else PinChange.Unchanged

    fun toggle(keys: List<String>, key: String): PinChange =
        if (key in keys) unpin(keys, key) else pin(keys, key)

    /**
     * Drops the pin at [from] onto index [to].
     *
     * Ordinary "move to index" semantics: everything between the two shuffles
     * by one and nothing is added or lost, so the cap cannot be breached by a
     * reorder. Used by the pinned shelf's drag-and-drop.
     */
    fun reorder(keys: List<String>, from: Int, to: Int): PinChange {
        if (from !in keys.indices || to !in keys.indices || from == to) return PinChange.Unchanged
        val reordered = keys.toMutableList()
        reordered.add(to, reordered.removeAt(from))
        return PinChange.Updated(reordered)
    }

    /**
     * Drops [movedKey] where [targetKey] currently sits.
     *
     * The screen drops onto a **card**, and the pinned shelf drops any pin
     * whose product has gone, so the two lists can differ in length. Working
     * in keys rather than indices keeps a dangling pin exactly where it was
     * instead of silently dropping it from the write.
     */
    fun reorderTo(keys: List<String>, movedKey: String, targetKey: String): PinChange =
        reorder(keys, keys.indexOf(movedKey), keys.indexOf(targetKey))

    /** Moves a pin one place up (-1) or down (+1); the ends do not wrap. */
    fun move(keys: List<String>, key: String, delta: Int): PinChange {
        val from = keys.indexOf(key)
        if (from < 0 || delta == 0) return PinChange.Unchanged
        val to = from + delta
        if (to !in keys.indices) return PinChange.Unchanged
        val reordered = keys.toMutableList()
        reordered.removeAt(from)
        reordered.add(to, key)
        return PinChange.Updated(reordered)
    }
}
