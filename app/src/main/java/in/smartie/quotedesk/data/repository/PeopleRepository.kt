package in.smartie.quotedesk.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import in.smartie.quotedesk.data.model.MemberRole
import in.smartie.quotedesk.data.model.UserProfile
import in.smartie.quotedesk.data.model.toUserProfile
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class PeopleRepository(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
) {
    fun observePeople(): Flow<List<UserProfile>> = callbackFlow {
        val registration = firestore.collection("users").addSnapshotListener { snapshot, error ->
            if (error != null) close(error)
            else trySend(snapshot?.documents.orEmpty().mapNotNull { it.toUserProfile() })
        }
        awaitClose { registration.remove() }
    }

    suspend fun changeRole(person: UserProfile, role: MemberRole) {
        require(person.uid != auth.currentUser?.uid) { "You cannot change your own role." }
        firestore.collection("users").document(person.uid)
            .update(mapOf("role" to role.wireValue, "updatedAt" to System.currentTimeMillis()))
            .await()
        audit("role_changed", person)
    }

    suspend fun setActive(person: UserProfile, active: Boolean) {
        require(person.uid != auth.currentUser?.uid) { "You cannot switch off your own account." }
        firestore.collection("users").document(person.uid)
            .update(mapOf("active" to active, "updatedAt" to System.currentTimeMillis()))
            .await()
        audit(if (active) "member_enabled" else "member_disabled", person)
    }

    suspend fun deleteProfile(person: UserProfile) {
        require(person.uid != auth.currentUser?.uid) { "You cannot remove your own profile." }
        firestore.collection("users").document(person.uid).delete().await()
        audit("profile_removed", person)
    }

    suspend fun appointSecondOwner(person: UserProfile) {
        val caller = requireNotNull(auth.currentUser)
        val access = firestore.collection("teamSettings").document("access")
        val user = firestore.collection("users").document(person.uid)
        firestore.runTransaction { transaction ->
            val occupied = transaction.get(access).getString("secondOwnerUid").orEmpty()
            require(occupied.isBlank() || occupied == person.uid) { "The second Owner position is already occupied." }
            transaction.set(access, mapOf(
                "primaryOwnerUid" to caller.uid,
                "secondOwnerUid" to person.uid,
                "updatedAt" to System.currentTimeMillis(),
                "updatedBy" to caller.uid,
            ), com.google.firebase.firestore.SetOptions.merge())
            transaction.update(user, mapOf(
                "role" to MemberRole.OWNER.wireValue,
                "isPrimaryOwner" to false,
            ))
        }.await()
        audit("second_owner_appointed", person)
    }

    suspend fun emergencyRevoke(person: UserProfile, fallbackRole: MemberRole = MemberRole.ADMIN) {
        require(fallbackRole != MemberRole.OWNER)
        val caller = requireNotNull(auth.currentUser)
        val access = firestore.collection("teamSettings").document("access")
        val user = firestore.collection("users").document(person.uid)
        firestore.runTransaction { transaction ->
            transaction.get(access)
            transaction.get(user)
            transaction.set(access, mapOf(
                "secondOwnerUid" to "",
                "updatedAt" to System.currentTimeMillis(),
                "updatedBy" to caller.uid,
            ), com.google.firebase.firestore.SetOptions.merge())
            transaction.update(user, mapOf(
                "role" to fallbackRole.wireValue,
                "updatedAt" to System.currentTimeMillis(),
            ))
        }.await()
        audit("second_owner_revoked", person)
    }

    private suspend fun audit(action: String, person: UserProfile) {
        val caller = auth.currentUser ?: return
        val id = firestore.collection("teamAudit").document().id
        firestore.collection("teamAudit").document(id).set(mapOf(
            "id" to id,
            "action" to action,
            "targetUid" to person.uid,
            "targetName" to person.name,
            "targetEmail" to person.email,
            "byUid" to caller.uid,
            "by" to (caller.displayName ?: caller.email.orEmpty()),
            "at" to System.currentTimeMillis(),
        )).await()
    }
}
