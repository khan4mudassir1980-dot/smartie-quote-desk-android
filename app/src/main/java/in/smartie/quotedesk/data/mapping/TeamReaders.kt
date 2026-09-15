package `in`.smartie.quotedesk.data.mapping

import `in`.smartie.quotedesk.data.model.TeamAccess
import `in`.smartie.quotedesk.data.model.TeamAuditEntry
import `in`.smartie.quotedesk.data.model.TeamMember

fun DocData.toTeamMember(): TeamMember = TeamMember(
    uid = id,
    name = string("name"),
    // The PWA stores the token email verbatim; compare folded, store as given.
    email = string("email"),
    roleWireValue = string("role", default = "worker").lowercase(),
    // A missing `active` field means active, as `d.active !== false` in the PWA.
    active = bool("active", default = true),
    photoUrl = string("photoURL", "photoUrl"),
    createdAt = millis("createdAt", "t"),
    createdBy = string("createdBy"),
    lastProvider = string("lastProvider"),
    updatedAt = millis("updatedAt"),
    updatedBy = string("updatedBy")
)

fun DocData.toTeamAccess(): TeamAccess = TeamAccess(
    primaryOwnerUid = string("primaryOwnerUid"),
    secondOwnerUid = string("secondOwnerUid"),
    updatedAt = millis("updatedAt"),
    updatedBy = string("updatedBy")
)

fun DocData.toTeamAuditEntry(): TeamAuditEntry {
    val detail = map("detail")
    return TeamAuditEntry(
        id = string("id", default = id),
        action = string("action"),
        targetUid = string("targetUid"),
        targetName = string("targetName"),
        targetEmail = string("targetEmail"),
        detailFrom = detail["from"].asString(),
        detailTo = detail["to"].asString(),
        byUid = string("byUid"),
        by = string("by"),
        at = millis("at")
    )
}
