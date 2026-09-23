package `in`.smartie.quotedesk.ui.more

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import `in`.smartie.quotedesk.core.AppContainer
import `in`.smartie.quotedesk.data.mapping.Keys
import `in`.smartie.quotedesk.data.model.PartyRecord
import `in`.smartie.quotedesk.data.repository.FirestoreFailures
import `in`.smartie.quotedesk.data.repository.PartyWriteRepository
import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.domain.PartyDraft
import `in`.smartie.quotedesk.domain.PartyWrite
import `in`.smartie.quotedesk.domain.Permissions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What this person may do to a party, decided once from their role. */
data class PartyCapabilities(
    val canAdd: Boolean = false,
    val canRename: Boolean = false,
    val canArchive: Boolean = false
) {
    companion object {
        fun of(member: Member): PartyCapabilities = PartyCapabilities(
            canAdd = Permissions.canUseParties(member),
            // One permission, two controls: the rules gate renaming and
            // archiving with the same `admin()` branch.
            canRename = Permissions.canRenameOrArchiveParty(member),
            canArchive = Permissions.canRenameOrArchiveParty(member)
        )
    }
}

/**
 * What the screen asks for.
 *
 * `onCreate` takes the id the screen minted, not one the repository makes up,
 * so a retry after an ambiguous failure lands on the same document.
 */
data class PartyActions(
    val onCreate: (String, PartyDraft) -> Unit = { _, _ -> },
    val onEdit: (PartyRecord, PartyDraft) -> Unit = { _, _ -> },
    val onArchive: (PartyRecord, Boolean) -> Unit = { _, _ -> }
)

/**
 * The writer behind the Parties screen.
 *
 * Thin on purpose: every decision about *what* to write is `PartyWrite`'s, and
 * every decision about *who may* is `Permissions`' and the rules'. What is
 * here is the part that needs a coroutine and a message to show when a write
 * comes back refused.
 */
class PartiesViewModel(
    private val writes: PartyWriteRepository,
    private val member: Member,
    private val newId: () -> String = { Keys.generateId(PartyWrite.ID_PREFIX) }
) : ViewModel() {

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val capabilities: PartyCapabilities = PartyCapabilities.of(member)

    fun mintId(): String = newId()

    fun create(id: String, draft: PartyDraft) = write { writes.create(member, draft, id) }

    fun edit(record: PartyRecord, draft: PartyDraft) = write { writes.edit(member, record, draft) }

    fun setArchived(record: PartyRecord, archived: Boolean) =
        write { writes.setArchived(member, record, archived) }

    private fun write(body: suspend () -> Unit) {
        if (_saving.value) return
        _saving.value = true
        _error.value = null
        viewModelScope.launch {
            runCatching { body() }
                .onFailure { _error.value = failureOf(it) }
            _saving.value = false
        }
    }

    /**
     * The same translation the purchase writer uses: a refusal the planner
     * decided says what it decided, and a Firestore code becomes a sentence
     * rather than a stack trace.
     */
    private fun failureOf(throwable: Throwable): String {
        FirestoreFailures.message(throwable)?.let { return it }
        if (FirestoreFailures.isWriteConflict(throwable)) return FirestoreFailures.WRITE_CONFLICT
        if (FirestoreFailures.isRefused(throwable)) return FirestoreFailures.REFUSED
        return throwable.message?.takeIf { it.isNotBlank() } ?: SAVE_FAILED
    }

    internal companion object {
        const val SAVE_FAILED = "That party could not be saved. Try again."
    }

    class Factory(
        private val container: AppContainer,
        private val member: Member
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PartiesViewModel(container.partyWriteRepository, member) as T
    }
}
