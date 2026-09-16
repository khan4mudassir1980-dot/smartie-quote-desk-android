package `in`.smartie.quotedesk.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import `in`.smartie.quotedesk.domain.QuoteDraft
import `in`.smartie.quotedesk.domain.QuoteDraftCodec
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
    private val quoteDraftKey = stringPreferencesKey("quote_draft")

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

    /** The quotation being built, so killing the app does not lose it (C8). */
    val quoteDraft: Flow<QuoteDraft> =
        preferences.map { QuoteDraftCodec.decode(it[quoteDraftKey]) }

    suspend fun setQuoteDraft(draft: QuoteDraft) {
        context.preferencesStore.edit { it[quoteDraftKey] = QuoteDraftCodec.encode(draft) }
    }
}
