package in.smartie.quotedesk.ui

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseUser
import in.smartie.quotedesk.data.model.UserProfile
import in.smartie.quotedesk.data.repository.AuthRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface SessionState {
    data object Loading : SessionState
    data object SignedOut : SessionState
    data class Blocked(val profile: UserProfile) : SessionState
    data class Ready(val profile: UserProfile) : SessionState
}

data class SignInUiState(
    val busy: Boolean = false,
    val error: String? = null,
)

class SessionViewModel(private val repository: AuthRepository) : ViewModel() {
    private val _session = MutableStateFlow<SessionState>(SessionState.Loading)
    val session: StateFlow<SessionState> = _session.asStateFlow()

    private val _signIn = MutableStateFlow(SignInUiState())
    val signIn: StateFlow<SignInUiState> = _signIn.asStateFlow()

    private var profileJob: Job? = null

    init {
        viewModelScope.launch {
            repository.authUsers.collect { user -> observeUser(user) }
        }
    }

    private fun observeUser(user: FirebaseUser?) {
        profileJob?.cancel()
        if (user == null) {
            _session.value = SessionState.SignedOut
            return
        }
        _session.value = SessionState.Loading
        profileJob = viewModelScope.launch {
            runCatching { repository.ensureProfile(user) }
                .onFailure {
                    _signIn.value = SignInUiState(error = it.readableMessage())
                    _session.value = SessionState.SignedOut
                }
            repository.observeProfile(user.uid).collect { profile ->
                _session.value = when {
                    profile == null -> SessionState.Loading
                    !profile.active -> SessionState.Blocked(profile)
                    else -> SessionState.Ready(profile)
                }
            }
        }
    }

    fun signIn(activity: Activity) {
        if (_signIn.value.busy) return
        viewModelScope.launch {
            _signIn.value = SignInUiState(busy = true)
            repository.signInWithGoogle(activity)
                .onSuccess { _signIn.value = SignInUiState() }
                .onFailure { _signIn.value = SignInUiState(error = it.readableMessage()) }
        }
    }

    fun signOut(activity: Activity) {
        viewModelScope.launch { repository.signOut(activity) }
    }
}

internal fun Throwable.readableMessage(): String = when {
    message?.contains("network", ignoreCase = true) == true -> "Internet connection is unavailable. Please try again."
    message?.contains("cancel", ignoreCase = true) == true -> "Sign-in was cancelled."
    else -> message ?: "Something went wrong. Please try again."
}
