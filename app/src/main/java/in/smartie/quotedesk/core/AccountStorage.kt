package `in`.smartie.quotedesk.core

/**
 * Which device key belongs to whom, and the one-time move that fixes the
 * keys that belonged to nobody.
 *
 * **The defect this exists for is a rate leak.** The quotation draft and the
 * uncommitted stock counts were each stored under a single global key, and
 * signing out clears Firebase's credentials but no device preference. So a
 * draft survived being killed (right), survived sign-out, and was opened by
 * **the next account to sign in on that phone** — with its customer and its
 * rates. A draft carries rates, and a Staff account may not see rates
 * anywhere in this app, so this is a permission failure that happens to live
 * on disk rather than housekeeping.
 *
 * Keying by account rather than clearing on sign-out is deliberate: a draft
 * must **survive sign-out and sign-in as the same account**, because on a
 * shared phone signing out is the ordinary way to hand over, and destroying
 * somebody's half-finished quotation to protect it is not a fix.
 *
 * Pure: every decision here is made without touching a store, so it is an
 * ordinary unit test rather than an Android one.
 */
object AccountStorage {

    /** What the old, ownerless keys were called. */
    const val LEGACY_DRAFT_KEY = "quote_draft"
    const val LEGACY_PENDING_KEY = "stock_pending"

    fun draftsKey(uid: String): String = "quote_drafts_$uid"

    fun pendingKey(uid: String): String = "stock_pending_$uid"

    /** Set once the ownerless keys have been dealt with, so it never repeats. */
    const val MIGRATED_KEY = "device_prefs_account_keyed"

    /** What to do with one ownerless value. */
    sealed interface Move {
        /** Nothing to move, or it has been moved already. */
        data object None : Move

        /** Adopt [value] into the signed-in account, then forget the old key. */
        data class Adopt(val value: String) : Move

        /** Nothing to adopt, but the old key is still there and should go. */
        data object DropOnly : Move
    }

    /**
     * The one-time move, decided.
     *
     * [alreadyMoved] is the flag, so this runs once and not twice.
     * [legacy] is what the ownerless key holds. [existing] is what the
     * account's own key already holds — if the account has its own value, the
     * ownerless one is **not** allowed to overwrite it.
     *
     * **Nobody signed in means no decision at all.** The caller only ever has
     * a uid for a signed-in account, so an ownerless value is never deleted
     * while there is no account to give it to — data with no owner is left
     * exactly where it is until somebody can own it.
     */
    fun moveFor(alreadyMoved: Boolean, legacy: String?, existing: String?): Move = when {
        alreadyMoved -> Move.None
        legacy.isNullOrBlank() -> Move.None
        !existing.isNullOrBlank() -> Move.DropOnly
        else -> Move.Adopt(legacy)
    }
}
