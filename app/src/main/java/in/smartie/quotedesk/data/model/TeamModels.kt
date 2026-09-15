package `in`.smartie.quotedesk.data.model

/**
 * Schema v2 keeps the PWA's field names and types and only adds fields, so
 * the PWA can still read everything the native app writes during the
 * transition (audit section 5.2).
 */
const val SCHEMA_VERSION = 2

/** A `users/{uid}` document. */
data class TeamMember(
    val uid: String,
    val name: String = "",
    val email: String = "",
    val roleWireValue: String = "worker",
    val active: Boolean = true,
    val photoUrl: String = "",
    val createdAt: Long = 0L,
    val createdBy: String = "",
    val lastProvider: String = "",
    val updatedAt: Long = 0L,
    val updatedBy: String = ""
) {
    val displayName: String get() = name.ifBlank { email.ifBlank { "Team member" } }
}

/** `teamSettings/access` — who holds the two protected Owner positions. */
data class TeamAccess(
    val primaryOwnerUid: String = "",
    val secondOwnerUid: String = "",
    val updatedAt: Long = 0L,
    val updatedBy: String = ""
)

/** A `teamAudit/{ta_…}` entry. */
data class TeamAuditEntry(
    val id: String,
    val action: String,
    val targetUid: String = "",
    val targetName: String = "",
    val targetEmail: String = "",
    val detailFrom: String = "",
    val detailTo: String = "",
    val byUid: String = "",
    val by: String = "",
    val at: Long = 0L
) {
    /** "role changed", as the PWA renders it. */
    val readableAction: String get() = action.replace('_', ' ')
}

/** The PWA's audit vocabulary; native must write exactly these strings. */
object TeamAuditActions {
    const val ROLE_CHANGED = "role_changed"
    const val ACCOUNT_ENABLED = "account_enabled"
    const val ACCOUNT_DISABLED = "account_disabled"
    const val MEMBER_REMOVED = "member_removed"
    const val OWNER_REVOKED = "owner_revoked"
}
