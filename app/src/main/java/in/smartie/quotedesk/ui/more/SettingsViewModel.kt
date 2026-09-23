package `in`.smartie.quotedesk.ui.more

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import `in`.smartie.quotedesk.core.AppContainer
import `in`.smartie.quotedesk.data.model.NumberingRecord
import `in`.smartie.quotedesk.data.model.QuotingRecord
import `in`.smartie.quotedesk.data.repository.FirestoreFailures
import `in`.smartie.quotedesk.data.repository.SettingsRepository
import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.domain.NumberingDraft
import `in`.smartie.quotedesk.domain.Permissions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * What this person may change on the Settings screen, decided once from their
 * role.
 *
 * Both are the Owner's. They are two fields rather than one because they are
 * two documents with two rules, and a later change to either must not silently
 * move the other.
 */
data class SettingsCapabilities(
    val canConfigureNumbering: Boolean = false,
    val canSetDiscountCap: Boolean = false
) {
    companion object {
        fun of(member: Member): SettingsCapabilities = SettingsCapabilities(
            canConfigureNumbering = Permissions.canConfigureNumbering(member),
            canSetDiscountCap = Permissions.canSetDiscountCap(member)
        )
    }
}

/** What the screen asks for. */
data class SettingsActions(
    val onSaveNumbering: (NumberingDraft) -> Unit = {},
    val onSaveDiscountCap: (Double) -> Unit = {}
)

/**
 * The Settings screen's reader and writer.
 *
 * Thin, like the parties one: [in.smartie.quotedesk.domain.Numbering] and
 * [in.smartie.quotedesk.domain.DiscountCap] decide what may be written, the
 * rules decide who may, and what is here is a coroutine and a sentence to show
 * when a write comes back refused.
 *
 * A read that is refused surfaces as `null` rather than a crash: a Staff
 * account never reaches this screen, but a listener that is denied mid-session
 * — a role changed under somebody — must leave the screen empty instead of
 * taking the app down.
 */
class SettingsViewModel(
    private val settings: SettingsRepository,
    private val member: Member
) : ViewModel() {

    val capabilities: SettingsCapabilities = SettingsCapabilities.of(member)

    val numbering: StateFlow<NumberingRecord?> = settings.observeNumbering()
        .catch { emit(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val quoting: StateFlow<QuotingRecord?> = settings.observeQuoting()
        .catch { emit(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun saveNumbering(draft: NumberingDraft) = write {
        settings.saveNumbering(member, numbering.value ?: NumberingRecord(), draft)
    }

    fun saveDiscountCap(percent: Double) = write {
        settings.saveDiscountCap(member, percent)
    }

    private fun write(body: suspend () -> Unit) {
        if (_saving.value) return
        _saving.value = true
        _error.value = null
        viewModelScope.launch {
            runCatching { body() }.onFailure { _error.value = failureOf(it) }
            _saving.value = false
        }
    }

    private fun failureOf(throwable: Throwable): String {
        FirestoreFailures.message(throwable)?.let { return it }
        if (FirestoreFailures.isWriteConflict(throwable)) return FirestoreFailures.WRITE_CONFLICT
        if (FirestoreFailures.isRefused(throwable)) return FirestoreFailures.REFUSED
        return throwable.message?.takeIf { it.isNotBlank() } ?: SAVE_FAILED
    }

    internal companion object {
        const val SAVE_FAILED = "That setting could not be saved. Try again."
    }

    class Factory(
        private val container: AppContainer,
        private val member: Member
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(container.settingsRepository, member) as T
    }
}
