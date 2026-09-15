package `in`.smartie.quotedesk.data.repository

import android.app.Activity
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import `in`.smartie.quotedesk.R
import `in`.smartie.quotedesk.data.docDataFlow
import `in`.smartie.quotedesk.data.mapping.toDocData
import `in`.smartie.quotedesk.data.mapping.toTeamAccess
import `in`.smartie.quotedesk.data.mapping.toTeamMember
import `in`.smartie.quotedesk.data.model.SCHEMA_VERSION
import `in`.smartie.quotedesk.data.model.TeamAccess
import `in`.smartie.quotedesk.data.model.TeamMember
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.tasks.await

/**
 * Sign-in, the profile document and the protected owner positions.
 *
 * Sign-in methods match the approved PWA: Google with the account chooser,
 * an existing email and password, and a password reset. There is no
 * self-registration; a new person joins by signing in and entering as an
 * active Worker.
 */
class AuthRepository(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    /**
     * Only a transition fallback. Once teamSettings/access.primaryOwnerUid is
     * seeded by the migration, the uid decides and this is ignored. The client
     * never writes primaryOwnerUid itself (audit D5).
     */
    private val primaryOwnerEmailFallback: String,
) {

    private val users get() = firestore.collection("users")
    private val accessDocument get() = firestore.collection("teamSettings").document("access")

    /** A Google credential kept aside when the email already uses a password. */
    @Volatile
    private var pendingGoogleCredential: AuthCredential? = null

    /** The email a pending Google credential belongs to, for the prompt. */
    @Volatile
    var pendingLinkEmail: String? = null
        private set

    val authUsers: Flow<FirebaseUser?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser) }
        auth.addAuthStateListener(listener)
        trySend(auth.currentUser)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    // --- sign-in ----------------------------------------------------------

    suspend fun signInWithGoogle(activity: Activity): Result<FirebaseUser> = runCatching {
        val option = GetSignInWithGoogleOption
            .Builder(activity.getString(R.string.default_web_client_id))
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val credential = CredentialManager.create(activity).getCredential(activity, request).credential
        require(
            credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) { "Google did not return a valid identity token." }

        val googleToken = GoogleIdTokenCredential.createFrom(credential.data)
        val authCredential = GoogleAuthProvider.getCredential(googleToken.idToken, null)
        val user = try {
            requireNotNull(auth.signInWithCredential(authCredential).await().user)
        } catch (collision: FirebaseAuthUserCollisionException) {
            // The same email already signs in with a password. Keep the Google
            // credential so it can be linked after a password sign-in, leaving
            // the person with one uid.
            pendingGoogleCredential = authCredential
            pendingLinkEmail = collision.email ?: googleToken.id
            throw collision
        }
        clearPendingLinkFor(user)
        ensureProfile(user)
        user
    }

    suspend fun signInWithEmail(email: String, password: String): Result<FirebaseUser> = runCatching {
        val user = requireNotNull(auth.signInWithEmailAndPassword(email.trim(), password).await().user)
        linkPendingGoogleCredential(user)
        ensureProfile(user)
        user
    }

    suspend fun sendPasswordReset(email: String): Result<Unit> = runCatching {
        auth.sendPasswordResetEmail(email.trim()).await()
    }

    /**
     * Links a Google credential held back by a collision. Failure to link is
     * not failure to sign in: the person is signed in either way.
     */
    private suspend fun linkPendingGoogleCredential(user: FirebaseUser) {
        val pending = pendingGoogleCredential ?: return
        runCatching { user.linkWithCredential(pending).await() }
        pendingGoogleCredential = null
        pendingLinkEmail = null
    }

    private fun clearPendingLinkFor(user: FirebaseUser) {
        if (pendingLinkEmail.equals(user.email, ignoreCase = true)) {
            pendingGoogleCredential = null
            pendingLinkEmail = null
        }
    }

    // --- profile ----------------------------------------------------------

    /**
     * Creates the caller's own profile if it is missing, exactly as the PWA
     * does. A profile deleted while the person is signed in comes back here as
     * an active Worker instead of leaving the app on Loading for ever.
     */
    suspend fun ensureProfile(user: FirebaseUser): TeamMember {
        val reference = users.document(user.uid)
        val existing = reference.get().await()
        if (existing.exists()) return existing.toDocData().toTeamMember()

        // The token email verbatim: the rules compare it to request.auth.token.email,
        // and lower-casing it here is what made owner profile creation fail.
        val email = user.email.orEmpty()
        val role = if (isPrimaryOwner(user.uid, email)) "owner" else "worker"
        val profile = mapOf(
            "name" to (user.displayName?.takeIf { it.isNotBlank() }
                ?: email.substringBefore('@').takeIf { it.isNotBlank() }
                ?: "Team member"),
            "email" to email,
            "role" to role,
            "active" to true,
            "photoURL" to user.photoUrl?.toString().orEmpty(),
            "createdAt" to System.currentTimeMillis(),
            "createdBy" to createdBy(user),
            "lastProvider" to (user.providerData.lastOrNull()?.providerId ?: "google.com"),
            "updatedAt" to System.currentTimeMillis(),
            "schemaVersion" to SCHEMA_VERSION,
        )
        reference.set(profile).await()
        return reference.get().await().toDocData().toTeamMember()
    }

    private fun createdBy(user: FirebaseUser): String =
        if (user.providerData.any { it.providerId == "password" }) "self-password" else "self-google"

    /**
     * The access document is readable by owners and administrators only, so a
     * denial here simply means the caller is not one; the email fallback then
     * decides, exactly as it does in the PWA.
     */
    private suspend fun isPrimaryOwner(uid: String, email: String): Boolean {
        val access = runCatching { accessDocument.get().await().toDocData().toTeamAccess() }
            .getOrDefault(TeamAccess())
        return if (access.primaryOwnerUid.isNotBlank()) {
            uid == access.primaryOwnerUid
        } else {
            primaryOwnerEmailFallback.isNotBlank() &&
                email.trim().equals(primaryOwnerEmailFallback.trim(), ignoreCase = true)
        }
    }

    /** Emits null when the profile is removed, so the caller can self-heal. */
    fun observeProfile(uid: String): Flow<TeamMember?> =
        users.document(uid).docDataFlow().map { it?.toTeamMember() }

    /**
     * Starts with an empty document so a caller who may not read it (anyone
     * below Administrator) is never left waiting.
     */
    fun observeAccess(): Flow<TeamAccess> = accessDocument.docDataFlow()
        .map { it?.toTeamAccess() ?: TeamAccess() }
        .onStart { emit(TeamAccess()) }
        .catch { emit(TeamAccess()) }

    val primaryOwnerEmail: String get() = primaryOwnerEmailFallback

    // --- sign out ---------------------------------------------------------

    /**
     * Callers stop their listeners before this runs: signing out with live
     * listeners attached raises permission-denied inside them.
     */
    suspend fun signOut(activity: Activity) {
        auth.signOut()
        pendingGoogleCredential = null
        pendingLinkEmail = null
        runCatching {
            CredentialManager.create(activity).clearCredentialState(ClearCredentialStateRequest())
        }
    }
}
