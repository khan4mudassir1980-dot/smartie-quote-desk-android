package `in`.smartie.quotedesk.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import `in`.smartie.quotedesk.data.docDataFlow
import `in`.smartie.quotedesk.data.mapping.Keys
import `in`.smartie.quotedesk.data.mapping.toTeamAuditEntry
import `in`.smartie.quotedesk.data.mapping.toTeamMember
import `in`.smartie.quotedesk.data.model.TeamAuditActions
import `in`.smartie.quotedesk.data.model.TeamAuditEntry
import `in`.smartie.quotedesk.data.model.TeamMember
import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.domain.OwnerRank
import `in`.smartie.quotedesk.domain.Permissions
import `in`.smartie.quotedesk.domain.Role
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

/**
 * Team management.
 *
 * Every owner change is one transaction over `users/{uid}` and
 * `teamSettings/access`, because the rules check the access slot with
 * `getAfter` and refuse the two writes separately. Each change also writes a
 * `teamAudit` entry using the PWA's own action vocabulary; a failed audit
 * write never rolls back the account change.
 */
class PeopleRepository(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
) {

    private val users get() = firestore.collection("users")
    private val accessDocument get() = firestore.collection("teamSettings").document("access")
    private val auditCollection get() = firestore.collection("teamAudit")

    fun observeMembers(): Flow<List<TeamMember>> =
        users.docDataFlow().map { documents -> documents.map { it.toTeamMember() } }

    /** The latest activity, newest first, as the PWA's Team activity shows. */
    fun observeAudit(limit: Long = 40): Flow<List<TeamAuditEntry>> =
        auditCollection.orderBy("at", Query.Direction.DESCENDING).limit(limit).docDataFlow()
            .map { documents -> documents.map { it.toTeamAuditEntry() } }

    // --- role changes -----------------------------------------------------

    suspend fun changeRole(viewer: Member, target: Member, role: Role, ownerCount: Int) {
        when {
            role == Role.OWNER -> appointAdditionalOwner(viewer, target, ownerCount)
            target.ownerRank == OwnerRank.ADDITIONAL -> demoteAdditionalOwner(viewer, target, role)
            else -> changePlainRole(viewer, target, role)
        }
    }

    private suspend fun changePlainRole(viewer: Member, target: Member, role: Role) {
        require(Permissions.canManage(viewer, target)) { PROTECTED }
        require(role in Permissions.roleOptionsFor(viewer, target, ownerCount = Permissions.MAX_OWNERS)) {
            "You cannot set that role."
        }
        val from = target.role
        users.document(target.uid).update(
            mapOf(
                "role" to role.wireValue,
                "updatedAt" to System.currentTimeMillis(),
                "updatedBy" to callerUid(),
                // Unlike the PWA, a role change does not silently switch a
                // disabled account back on (audit A11).
                "email" to target.email,
            )
        ).await()
        writeAudit(
            viewer = viewer,
            action = TeamAuditActions.ROLE_CHANGED,
            target = target,
            detail = mapOf("from" to from.wireValue, "to" to role.wireValue),
        )
    }

    suspend fun appointAdditionalOwner(viewer: Member, target: Member, ownerCount: Int) {
        require(Permissions.canAppointAdditionalOwner(viewer, target, ownerCount)) {
            "Only the primary Owner can appoint a second Owner, and only two are allowed."
        }
        val callerUid = callerUid()
        val targetRef = users.document(target.uid)
        firestore.runTransaction { transaction ->
            val slot = transaction.get(accessDocument)
            val held = slot.getString("secondOwnerUid").orEmpty()
            if (held.isNotBlank() && held != target.uid) {
                throw IllegalStateException("A second Owner is already appointed.")
            }
            transaction.set(
                accessDocument,
                mapOf(
                    "secondOwnerUid" to target.uid,
                    "updatedAt" to System.currentTimeMillis(),
                    "updatedBy" to callerUid,
                ),
                SetOptions.merge(),
            )
            transaction.update(
                targetRef,
                mapOf(
                    "role" to Role.OWNER.wireValue,
                    "active" to true,
                    "email" to target.email,
                    "updatedAt" to System.currentTimeMillis(),
                    "updatedBy" to callerUid,
                ),
            )
        }.await()
        writeAudit(
            viewer = viewer,
            action = TeamAuditActions.ROLE_CHANGED,
            target = target,
            detail = mapOf("from" to target.role.wireValue, "to" to Role.OWNER.wireValue),
        )
    }

    suspend fun demoteAdditionalOwner(viewer: Member, target: Member, role: Role) {
        require(Permissions.canDemoteAdditionalOwner(viewer, target)) {
            "Only the primary Owner can change the second Owner."
        }
        require(role != Role.OWNER) { "Choose the role they should keep." }
        clearOwnerSlot(target, role, active = null)
        writeAudit(
            viewer = viewer,
            action = TeamAuditActions.ROLE_CHANGED,
            target = target,
            detail = mapOf("from" to Role.OWNER.wireValue, "to" to role.wireValue),
        )
    }

    /**
     * Emergency revoke: the Additional Owner becomes a Worker and the account
     * is switched off in the same transaction. The beta left them an active
     * Administrator (audit A9).
     */
    suspend fun emergencyRevoke(viewer: Member, target: Member) {
        require(Permissions.canEmergencyRevoke(viewer, target)) {
            "Only the primary Owner can revoke the second Owner."
        }
        clearOwnerSlot(target, Role.WORKER, active = false)
        writeAudit(viewer, TeamAuditActions.OWNER_REVOKED, target)
    }

    private suspend fun clearOwnerSlot(target: Member, role: Role, active: Boolean?) {
        val callerUid = callerUid()
        val targetRef: DocumentReference = users.document(target.uid)
        firestore.runTransaction { transaction ->
            transaction.get(accessDocument)
            transaction.set(
                accessDocument,
                mapOf(
                    "secondOwnerUid" to "",
                    "updatedAt" to System.currentTimeMillis(),
                    "updatedBy" to callerUid,
                ),
                SetOptions.merge(),
            )
            val update = mutableMapOf<String, Any>(
                "role" to role.wireValue,
                "email" to target.email,
                "updatedAt" to System.currentTimeMillis(),
                "updatedBy" to callerUid,
            )
            if (active != null) update["active"] = active
            transaction.update(targetRef, update)
        }.await()
    }

    // --- account state ----------------------------------------------------

    suspend fun setActive(viewer: Member, target: Member, active: Boolean) {
        require(Permissions.canToggleActive(viewer, target)) { PROTECTED }
        users.document(target.uid).update(
            mapOf(
                "active" to active,
                "email" to target.email,
                "updatedAt" to System.currentTimeMillis(),
                "updatedBy" to callerUid(),
            )
        ).await()
        writeAudit(
            viewer = viewer,
            action = if (active) TeamAuditActions.ACCOUNT_ENABLED else TeamAuditActions.ACCOUNT_DISABLED,
            target = target,
        )
    }

    /**
     * Removes the profile. The person keeps their sign-in and returns as an
     * active Worker next time, exactly as in the PWA.
     */
    suspend fun removeMember(viewer: Member, target: Member) {
        require(Permissions.canRemove(viewer, target)) { PROTECTED }
        // Written first: once the profile is gone its name and email are not
        // available to record.
        writeAudit(viewer, TeamAuditActions.MEMBER_REMOVED, target)
        users.document(target.uid).delete().await()
    }

    // --- audit ------------------------------------------------------------

    private suspend fun writeAudit(
        viewer: Member,
        action: String,
        target: Member,
        detail: Map<String, Any?> = emptyMap(),
    ) {
        val id = Keys.generateId("ta_")
        runCatching {
            auditCollection.document(id).set(
                mapOf(
                    "id" to id,
                    "action" to action,
                    "targetUid" to target.uid,
                    "targetName" to target.name,
                    "targetEmail" to target.email,
                    "detail" to detail,
                    "byUid" to callerUid(),
                    "by" to viewer.name,
                    "at" to System.currentTimeMillis(),
                )
            ).await()
        }
    }

    private fun callerUid(): String = auth.currentUser?.uid.orEmpty()

    private companion object {
        const val PROTECTED = "That account is protected."
    }
}
