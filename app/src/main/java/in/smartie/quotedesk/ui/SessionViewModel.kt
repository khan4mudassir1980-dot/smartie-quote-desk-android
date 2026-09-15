package `in`.smartie.quotedesk.ui

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseUser
import `in`.smartie.quotedesk.core.ErrorReporter
import `in`.smartie.quotedesk.core.toAppError
import `in`.smartie.quotedesk.data.model.TeamAccess
import `in`.smartie.quotedesk.data.model.TeamMember
import `in`.smartie.quotedesk.data.repository.AuthRepository
import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.domain.TeamRoles
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

sealed interface SessionState {
    data object Loading : SessionState
    data object SignedOut : SessionState
    data class Blocked(val member: Member) : SessionState
    data class Ready(val member: Member) : SessionState
    data class Failed(val message: String) : SessionState
}

data class SignInUiState(
    val busy: Boolean = false,
    val error: String? = null,
    val info: String? = null,
    /** Set when Google sign-in hit an email that already uses a password. */
    val linkEmail: String? = null,
)

/**
 * Owns the signed-in person for the whole app.
 *
 * The profile and the owner positions are observed live, so a role change or
 * a switch-off reaches the running app within seconds. Listeners are torn
 * down before signing out, which is what stopped the beta from crashing with
 * permission-denied on the way out.
 */
class SessionViewModel(
    private val repository: AuthRepository,
    private val errorReporter: ErrorReporter,
) : ViewModel() {

    private val _session = MutableStateFlow<SessionState>(SessionState.Loading)
    val session: StateFlow<SessionState> = _session.asStateFlow()

    private val _signIn = MutableStateFlow(SignInUiState())
    val signIn: StateFlow<SignInUiState> = _signIn.asStateFlow()

    private var profileJob: Job? = null

    /** Guards the self-heal so a permanent failure cannot become a loop. */
    private var healedUid: String? = null

    init {
        viewModelScope.launch {
            repository.authUsers.collect { user -> observeUser(user) }
        }
    }

    private fun observeUser(user: FirebaseUser?) {
        profileJob?.cancel()
        profileJob = null
        if (user == null) {
            _session.value = SessionState.SignedOut
            return
        }
        _session.value = SessionState.Loading
        profileJob = viewModelScope.launch {
            combine(
                repository.observeProfile(user.uid),
                repository.observeAccess(),
            ) { profile, access -> profile to access }
                .collect { (profile, access) -> applyProfile(user, profile, access) }
        }
    }

    private suspend fun applyProfile(user: FirebaseUser, profile: TeamMember?, access: TeamAccess) {
        if (profile == null) {
            // The profile was removed while this person was signed in. The PWA
            // re-creates it on the next sign-in; here it is re-created straight
            // away, so they return as a Worker instead of waiting on Loading.
            if (healedUid == user.uid) {
                _session.value = SessionState.Failed(
                    "Your profile could not be restored. Sign out and sign in again."
                )
                return
            }
            healedUid = user.uid
            _session.value = SessionState.Loading
            runCatching { repository.ensureProfile(user) }
                .onFailure {
                    val error = it.toAppError()
                    errorReporter.report(error)
                    _session.value = SessionState.Failed(error.message)
                }
            return
        }

        healedUid = null
        val member = TeamRoles.resolve(profile, access, repository.primaryOwnerEmail)
        _session.value = if (member.active) SessionState.Ready(member) else SessionState.Blocked(member)
    }

    // --- sign-in actions --------------------------------------------------

    fun signInWithGoogle(activity: Activity) = signInAction {
        repository.signInWithGoogle(activity)
    }

    fun signInWithEmail(email: String, password: String) {
        when {
            email.isBlank() || password.isBlank() ->
                _signIn.value = _signIn.value.copy(error = "Enter your email and password")
            else -> signInAction { repository.signInWithEmail(email, password) }
        }
    }

    fun sendPasswordReset(email: String) {
        if (email.isBlank()) {
            _signIn.value = _signIn.value.copy(
                error = "Type the email used for this app first.",
                info = null,
            )
            return
        }
        viewModelScope.launch {
            _signIn.value = SignInUiState(busy = true)
            repository.sendPasswordReset(email)
                .onSuccess {
                    _signIn.value = SignInUiState(
                        info = "Reset link sent to ${email.trim()}. Open it to set a new app " +
                            "password; your Google password is unchanged.",
                    )
                }
                .onFailure { _signIn.value = SignInUiState(error = it.toAppError().message) }
        }
    }

    private fun signInAction(block: suspend () -> Result<FirebaseUser>) {
        if (_signIn.value.busy) return
        viewModelScope.launch {
            _signIn.value = SignInUiState(busy = true)
            block()
                .onSuccess { _signIn.value = SignInUiState() }
                .onFailure {
                    val error = it.toAppError()
                    errorReporter.report(error)
                    _signIn.value = SignInUiState(
                        error = error.message,
                        linkEmail = repository.pendingLinkEmail,
                    )
                }
        }
    }

    fun dismissSignInMessage() {
        _signIn.value = _signIn.value.copy(error = null, info = null)
    }

    fun signOut(activity: Activity) {
        // Stop listening first: signing out with listeners attached makes them
        // fail with permission-denied (audit C2).
        profileJob?.cancel()
        profileJob = null
        _session.value = SessionState.Loading
        viewModelScope.launch {
            runCatching { repository.signOut(activity) }
                .onFailure { errorReporter.report(it) }
            _session.value = SessionState.SignedOut
        }
    }
}
