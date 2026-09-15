package `in`.smartie.quotedesk.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import `in`.smartie.quotedesk.ui.theme.TextSizePreference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.preferencesStore: DataStore<Preferences> by preferencesDataStore("smartie_device_prefs")

/**
 * Per-device settings that never leave the phone, mirroring the PWA's
 * `state.prefs`. Text size is the only one N0 needs; open shelves and the
 * draft store follow in their own phases.
 */
class DevicePreferences(private val context: Context) {

    private val textSizeKey = intPreferencesKey("text_size_percent")

    val textSize: Flow<TextSizePreference> = context.preferencesStore.data
        .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
        .map { TextSizePreference.fromPercent(it[textSizeKey]) }

    suspend fun setTextSize(preference: TextSizePreference) {
        context.preferencesStore.edit { it[textSizeKey] = preference.percent }
    }
}
