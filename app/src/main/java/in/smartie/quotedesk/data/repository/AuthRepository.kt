package `in`.smartie.quotedesk.data.repository

import android.app.Activity
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import `in`.smartie.quotedesk.R
import `in`.smartie.quotedesk.data.model.MemberRole
import `in`.smartie.quotedesk.data.model.UserProfile
import `in`.smartie.quotedesk.data.model.toUserProfile
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class AuthRepository(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
) {
    @Volatile
    private var primaryOwnerUid: String? = null

    val authUsers: Flow<FirebaseUser?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser) }
        auth.addAuthStateListener(listener)
        trySend(auth.currentUser)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    suspend fun signInWithGoogle(activity: Activity): Result<FirebaseUser> = runCatching {
        val googleIdOption = GetGoogleIdOption.Builder()
            .setServerClientId(activity.getString(R.string.default_web_client_id))
            .setFilterByAuthorizedAccounts(false)
            .setAutoSelectEnabled(false)
            .build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()
        val credential = CredentialManager.create(activity)
            .getCredential(activity, request)
            .credential
        require(credential is CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            "Google did not return a valid identity token."
        }
        val googleToken = GoogleIdTokenCredential.createFrom(credential.data)
        val result = auth.signInWithCredential(
            GoogleAuthProvider.getCredential(googleToken.idToken, null),
        ).await()
        val user = requireNotNull(result.user)
        ensureProfile(user)
        user
    }

    suspend fun ensureProfile(user: FirebaseUser): UserProfile {
        val ref = firestore.collection("users").document(user.uid)
        val current = ref.get().await().toUserProfile()
        if (current != null) {
            if (current.role == MemberRole.OWNER) {
                val access = firestore.collection("teamSettings").document("access")
                var resolvedPrimaryUid = ""
                firestore.runTransaction { transaction ->
                    val settings = transaction.get(access)
                    val primaryUid = settings.getString("primaryOwnerUid").orEmpty()
                    val secondUid = settings.getString("secondOwnerUid").orEmpty()
                    if (primaryUid.isBlank() && secondUid != user.uid) {
                        transaction.set(
                            access,
                            mapOf(
                                "primaryOwnerUid" to user.uid,
                                "secondOwnerUid" to secondUid,
                                "updatedAt" to System.currentTimeMillis(),
                                "updatedBy" to user.uid,
                            ),
                            com.google.firebase.firestore.SetOptions.merge(),
                        )
                        resolvedPrimaryUid = user.uid
                    } else {
                        resolvedPrimaryUid = primaryUid
                    }
                }.await()
                primaryOwnerUid = resolvedPrimaryUid.ifBlank { null }
                return current.copy(primaryOwner = resolvedPrimaryUid == user.uid)
            }
            primaryOwnerUid = null
            return current
        }

        val email = user.email.orEmpty().lowercase()
        val profile = mapOf(
            "name" to (user.displayName ?: email.substringBefore('@').ifBlank { "Team member" }),
            "email" to email,
            "photoURL" to user.photoUrl?.toString().orEmpty(),
            "role" to MemberRole.WORKER.wireValue,
            "active" to true,
            "createdAt" to System.currentTimeMillis(),
        )
        ref.set(profile).await()
        return ref.get().await().toUserProfile() ?: error("Profile could not be created.")
    }

    fun observeProfile(uid: String): Flow<UserProfile?> = callbackFlow {
        val registration = firestore.collection("users").document(uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) close(error)
                else trySend(snapshot?.toUserProfile()?.let { profile ->
                    profile.copy(primaryOwner = profile.uid == primaryOwnerUid)
                })
            }
        awaitClose { registration.remove() }
    }

    suspend fun signOut(activity: Activity) {
        auth.signOut()
        runCatching {
            CredentialManager.create(activity).clearCredentialState(ClearCredentialStateRequest())
        }
    }
}
