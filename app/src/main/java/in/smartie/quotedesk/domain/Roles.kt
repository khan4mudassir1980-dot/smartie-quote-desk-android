package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.mapping.Keys
import `in`.smartie.quotedesk.data.model.TeamAccess
import `in`.smartie.quotedesk.data.model.TeamMember

/** The four wire roles the PWA and the Firestore rules understand. */
enum class Role(val wireValue: String) {
    OWNER("owner"),
    ADMIN("admin"),
    STAFF("staff"),
    WORKER("worker");

    companion object {
        fun from(value: String?): Role =
            entries.firstOrNull { it.wireValue.equals(value?.trim(), ignoreCase = true) } ?: WORKER
    }
}

/** Which of the two protected Owner positions a person holds, if either. */
enum class OwnerRank { PRIMARY, ADDITIONAL, NONE }

/**
 * A person as the permission rules see them: the wire role plus the owner
 * position resolved from `teamSettings/access`.
 */
data class Member(
    val uid: String,
    val email: String = "",
    val name: String = "",
    val role: Role = Role.WORKER,
    val active: Boolean = true,
    val ownerRank: OwnerRank = OwnerRank.NONE
) {
    val normalisedEmail: String get() = Keys.normaliseEmail(email)

    val isOwner: Boolean get() = ownerRank != OwnerRank.NONE || role == Role.OWNER

    /** "Owner / Administrator" for both owners, as the PWA labels them. */
    val roleLabel: String
        get() = when {
            isOwner -> "Owner / Administrator"
            role == Role.ADMIN -> "Administrator"
            role == Role.STAFF -> "Staff"
            else -> "Worker"
        }

    /** "Primary" or "Additional" beneath the owner label. */
    val ownerSubLabel: String?
        get() = when (ownerRank) {
            OwnerRank.PRIMARY -> "Primary"
            OwnerRank.ADDITIONAL -> "Additional"
            OwnerRank.NONE -> if (role == Role.OWNER) "Additional" else null
        }
}

/**
 * Resolves owner positions.
 *
 * `teamSettings/access.primaryOwnerUid` is authoritative once the migration
 * has seeded it. Until then the email the PWA hard-codes is the only way to
 * identify the primary Owner, so it stays as a documented fallback — it is
 * never written back to Firestore by the client.
 */
object TeamRoles {

    fun resolve(
        member: TeamMember,
        access: TeamAccess,
        primaryOwnerEmailFallback: String = ""
    ): Member = Member(
        uid = member.uid,
        email = member.email,
        name = member.displayName,
        role = Role.from(member.roleWireValue),
        active = member.active,
        ownerRank = rankOf(member.uid, member.email, Role.from(member.roleWireValue), access, primaryOwnerEmailFallback)
    )

    fun rankOf(
        uid: String,
        email: String,
        role: Role,
        access: TeamAccess,
        primaryOwnerEmailFallback: String = ""
    ): OwnerRank {
        val primaryUid = access.primaryOwnerUid
        if (primaryUid.isNotBlank()) {
            if (uid == primaryUid) return OwnerRank.PRIMARY
        } else if (
            primaryOwnerEmailFallback.isNotBlank() &&
            Keys.normaliseEmail(email) == Keys.normaliseEmail(primaryOwnerEmailFallback)
        ) {
            return OwnerRank.PRIMARY
        }
        if (access.secondOwnerUid.isNotBlank() && uid == access.secondOwnerUid) return OwnerRank.ADDITIONAL
        if (role == Role.OWNER) return OwnerRank.ADDITIONAL
        return OwnerRank.NONE
    }

    /** At most two people may hold an Owner position. */
    fun ownerCount(members: List<Member>): Int = members.count { it.isOwner }
}
