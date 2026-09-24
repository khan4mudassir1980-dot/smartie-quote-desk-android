package `in`.smartie.quotedesk.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import `in`.smartie.quotedesk.data.mapping.Keys
import `in`.smartie.quotedesk.domain.QuoteDraft
import `in`.smartie.quotedesk.domain.QuoteDraftCodec
import `in`.smartie.quotedesk.domain.QuoteDrafts
import `in`.smartie.quotedesk.domain.QuoteDraftsCodec
import `in`.smartie.quotedesk.domain.StockPendingCodec
import `in`.smartie.quotedesk.ui.theme.TextSizePreference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.preferencesStore: DataStore<Preferences> by preferencesDataStore("smartie_device_prefs")

/**
 * Per-device settings that never leave the phone, mirroring the PWA's
 * `state.prefs`: the text size, which catalogue shelves are open, and the
 * quotation being built. None of it is shared, and none of it is Firestore's.
 */
class DevicePreferences(private val context: Context) {

    private val textSizeKey = intPreferencesKey("text_size_percent")
    private val openShelvesKey = stringSetPreferencesKey("catalogue_open_shelves")

    private val preferences: Flow<Preferences> = context.preferencesStore.data
        .catch { emit(emptyPreferences()) }

    val textSize: Flow<TextSizePreference> =
        preferences.map { TextSizePreference.fromPercent(it[textSizeKey]) }

    suspend fun setTextSize(preference: TextSizePreference) {
        context.preferencesStore.edit { it[textSizeKey] = preference.percent }
    }

    /** Which catalogue shelves the person left open (audit P5). */
    val openShelves: Flow<Set<String>> =
        preferences.map { it[openShelvesKey].orEmpty() }

    suspend fun setShelfOpen(categoryId: String, open: Boolean) {
        context.preferencesStore.edit { stored ->
            val current = stored[openShelvesKey].orEmpty()
            stored[openShelvesKey] = if (open) current + categoryId else current - categoryId
        }
    }

    /**
     * The part of this that belongs to one account rather than to the phone.
     *
     * The text size and the open shelves are the phone's: they carry nothing
     * about anybody's business. The drafts and the uncommitted counts are
     * not, and used to be stored as though they were — see [AccountStorage].
     */
    fun forAccount(uid: String): AccountPreferences =
        AccountPreferences(context.preferencesStore, uid)
}

/**
 * One account's own device storage.
 *
 * Obtained from [DevicePreferences.forAccount], so it can only be built for a
 * signed-in account. That is what makes "never delete data with no owner"
 * structural rather than a rule somebody has to remember: with nobody signed
 * in, none of this code runs and the old ownerless value stays where it is.
 */
class AccountPreferences internal constructor(
    private val store: DataStore<Preferences>,
    private val uid: String
) : StockPendingStore {

    private val draftsKey = stringPreferencesKey(AccountStorage.draftsKey(uid))
    private val pendingKey = stringPreferencesKey(AccountStorage.pendingKey(uid))
    private val legacyDraftKey = stringPreferencesKey(AccountStorage.LEGACY_DRAFT_KEY)
    private val legacyPendingKey = stringPreferencesKey(AccountStorage.LEGACY_PENDING_KEY)
    private val migratedKey = booleanPreferencesKey(AccountStorage.MIGRATED_KEY)

    private val preferences: Flow<Preferences> = store.data.catch { emit(emptyPreferences()) }

    /** The quotations being built, so killing the app does not lose them (C8). */
    val drafts: Flow<QuoteDrafts> =
        preferences.map { QuoteDraftsCodec.decode(it[draftsKey]) }

    suspend fun setDrafts(drafts: QuoteDrafts) {
        store.edit { it[draftsKey] = QuoteDraftsCodec.encode(drafts) }
    }

    /**
     * Uncommitted `+`/`−` counts, so a long shelf count survives the app being
     * killed. **Never a queue**: nothing here commits itself, on reconnect or
     * on restart. The person presses Done.
     */
    override val pending: Flow<Map<String, Double>> =
        preferences.map { StockPendingCodec.decode(it[pendingKey]) }

    override suspend fun setPending(pending: Map<String, Double>) {
        store.edit { it[pendingKey] = StockPendingCodec.encode(pending) }
    }

    /**
     * The draft the builder is on, creating one only if this account has
     * none.
     *
     * **This is the only place a draft id comes into existence**, and that is
     * the point of it. The same mistake had already been made three times in
     * one batch — a line identified by its product key, an id minted inside a
     * DataStore transform that `edit` may re-run, and an id minted per view
     * model, which is per *process*, so a process death turned one quotation
     * into two. Each was fixed where it was found, and the shape came back.
     *
     * A caller that cannot mint cannot mint at the wrong moment. Callers ask
     * for the current id; they never make one. The one remaining hazard —
     * minting before a transform rather than inside it — now exists in
     * exactly one function instead of in every caller, where it is stated and
     * tested once.
     */
    suspend fun currentDraftId(): String {
        // Minted **before** the transform, because `edit` may run it more
        // than once under contention and an id made inside would differ
        // between attempts. Unused when a draft already exists.
        val candidate = Keys.generateId(QuoteDrafts.DRAFT_PREFIX)
        val after = store.edit { stored ->
            val drafts = QuoteDraftsCodec.decode(stored[draftsKey])
            val resolved = drafts.current
            stored[draftsKey] = QuoteDraftsCodec.encode(
                // `select` pins the resolved draft, so a collection whose
                // named draft had been finalised stops falling back on every
                // read and answers the same id twice running.
                if (resolved == null) drafts.save(QuoteDraft(id = candidate))
                else drafts.select(resolved.id)
            )
        }
        return QuoteDraftsCodec.decode(after[draftsKey]).currentId
    }

    /**
     * Moves what the ownerless keys held into this account, once.
     *
     * The old draft was a single one, so it is adopted as this account's first
     * draft and becomes the current one. Anything the account already has is
     * never overwritten. Afterwards the ownerless keys are removed, so the
     * next account to sign in on this phone finds nothing of the last one's.
     */
    suspend fun adoptOwnerlessValues() {
        // Minted **before** the edit, and once. DataStore may run the
        // transform below more than once under contention, and an id
        // generated inside it would differ between attempts — the N4.4 B2
        // shape, where a fresh id on a retry wrote a second row.
        val adoptedId = Keys.generateId(QuoteDrafts.DRAFT_PREFIX)
        store.edit { stored ->
            val alreadyMoved = stored[migratedKey] == true
            if (alreadyMoved) return@edit

            when (val move = AccountStorage.moveFor(
                alreadyMoved = false,
                legacy = stored[legacyDraftKey],
                existing = stored[draftsKey]
            )) {
                is AccountStorage.Move.Adopt -> {
                    val old = QuoteDraftCodec.decode(move.value)
                    if (!old.isEmpty) {
                        val adopted = old.copy(id = adoptedId)
                        stored[draftsKey] = QuoteDraftsCodec.encode(QuoteDrafts().save(adopted))
                    }
                }
                else -> Unit
            }

            when (val move = AccountStorage.moveFor(
                alreadyMoved = false,
                legacy = stored[legacyPendingKey],
                existing = stored[pendingKey]
            )) {
                is AccountStorage.Move.Adopt -> stored[pendingKey] = move.value
                else -> Unit
            }

            stored.remove(legacyDraftKey)
            stored.remove(legacyPendingKey)
            stored[migratedKey] = true
        }
    }
}
