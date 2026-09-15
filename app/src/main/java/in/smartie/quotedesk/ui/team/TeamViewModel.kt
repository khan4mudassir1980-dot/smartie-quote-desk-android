package `in`.smartie.quotedesk.ui.team

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import `in`.smartie.quotedesk.core.AppContainer
import `in`.smartie.quotedesk.core.toAppError
import `in`.smartie.quotedesk.data.model.TeamAuditEntry
import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.domain.PeopleFilter
import `in`.smartie.quotedesk.domain.PeopleFilters
import `in`.smartie.quotedesk.domain.PeopleGroups
import `in`.smartie.quotedesk.domain.PeopleStatusFilter
import `in`.smartie.quotedesk.domain.Permissions
import `in`.smartie.quotedesk.domain.Role
import `in`.smartie.quotedesk.domain.TeamRoles
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TeamViewModel(
    private val container: AppContainer,
    private val viewer: Member,
) : ViewModel() {

    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    private val _filter = MutableStateFlow(PeopleFilter())
    val filter: StateFlow<PeopleFilter> = _filter.asStateFlow()

    /** Live, so a role change reaches the affected person within seconds. */
    private val members: StateFlow<List<Member>> = combine(
        container.peopleRepository.observeMembers(),
        container.authRepository.observeAccess(),
    ) { people, access ->
        people.map { TeamRoles.resolve(it, access, container.authRepository.primaryOwnerEmail) }
    }
        .reportErrors(emptyList())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val groups: StateFlow<PeopleGroups> = combine(members, _filter) { people, filter ->
        PeopleFilters.group(people, viewer, filter)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PeopleGroups())

    val ownerCount: StateFlow<Int> = members
        .map { TeamRoles.ownerCount(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val audit: StateFlow<List<TeamAuditEntry>> = (
        if (Permissions.canViewTeamActivity(viewer)) container.peopleRepository.observeAudit()
        else flowOf(emptyList())
        )
        .reportErrors(emptyList<TeamAuditEntry>())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setQuery(query: String) {
        _filter.value = _filter.value.copy(query = query)
    }

    fun setStatus(status: PeopleStatusFilter) {
        _filter.value = _filter.value.copy(status = status)
    }

    fun roleOptionsFor(target: Member): List<Role> =
        Permissions.roleOptionsFor(viewer, target, ownerCount.value)

    fun changeRole(target: Member, role: Role) = act("${target.name} is now ${label(role)}") {
        container.peopleRepository.changeRole(viewer, target, role, ownerCount.value)
    }

    fun setActive(target: Member, active: Boolean) = act(
        if (active) "Account switched on" else "Account switched off"
    ) {
        container.peopleRepository.setActive(viewer, target, active)
    }

    fun remove(target: Member) = act("Member removed") {
        container.peopleRepository.removeMember(viewer, target)
    }

    fun emergencyRevoke(target: Member) = act("Owner access revoked; account switched off") {
        container.peopleRepository.emergencyRevoke(viewer, target)
    }

    private fun label(role: Role): String = when (role) {
        Role.OWNER -> "Owner / Administrator"
        Role.ADMIN -> "Administrator"
        Role.STAFF -> "Staff"
        Role.WORKER -> "Worker"
    }

    private fun act(success: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }
                .onSuccess { messages.emit(success) }
                .onFailure {
                    val error = it.toAppError()
                    container.errorReporter.report(error)
                    messages.emit(error.message)
                }
        }
    }

    private fun <T> kotlinx.coroutines.flow.Flow<T>.reportErrors(fallback: T) = catch { throwable ->
        val error = throwable.toAppError()
        container.errorReporter.report(error)
        if (!error.isBenign) messages.emit(error.message)
        emit(fallback)
    }

    class Factory(
        private val container: AppContainer,
        private val viewer: Member,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            TeamViewModel(container, viewer) as T
    }
}
