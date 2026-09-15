package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.mapping.Keys

enum class PeopleStatusFilter(val label: String) {
    ALL("All"),
    ACTIVE("Active"),
    SWITCHED_OFF("Switched off")
}

data class PeopleFilter(
    val query: String = "",
    val status: PeopleStatusFilter = PeopleStatusFilter.ALL
)

/** Active people first, then the switched-off ones, each under its own heading. */
data class PeopleGroups(
    val active: List<Member> = emptyList(),
    val switchedOff: List<Member> = emptyList()
) {
    val total: Int get() = active.size + switchedOff.size
    val isEmpty: Boolean get() = total == 0
}

/**
 * Builds the People list.
 *
 * The current user never appears, excluded by uid **and** by email: one
 * person can hold more than one profile (an email/password identity and a
 * Google identity have different uids), which is why the beta showed the
 * signed-in person their own row.
 */
object PeopleFilters {

    /** One row per person, keeping the newest profile for a repeated email. */
    fun deduplicate(members: List<Member>): List<Member> {
        val byIdentity = LinkedHashMap<String, Member>()
        members.forEach { member ->
            val key = member.normalisedEmail.ifEmpty { member.uid }
            val existing = byIdentity[key]
            if (existing == null || member.createdAt > existing.createdAt) {
                byIdentity[key] = member
            }
        }
        return byIdentity.values.toList()
    }

    fun excludeSelf(members: List<Member>, viewer: Member): List<Member> =
        members.filterNot { member ->
            member.uid == viewer.uid ||
                (viewer.normalisedEmail.isNotEmpty() &&
                    member.normalisedEmail == viewer.normalisedEmail)
        }

    fun matches(member: Member, query: String): Boolean {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return true
        return "${member.name} ${member.email}".lowercase().contains(needle)
    }

    fun group(
        members: List<Member>,
        viewer: Member,
        filter: PeopleFilter = PeopleFilter()
    ): PeopleGroups {
        val visible = excludeSelf(deduplicate(members), viewer)
            .filter { matches(it, filter.query) }
            .filter {
                when (filter.status) {
                    PeopleStatusFilter.ALL -> true
                    PeopleStatusFilter.ACTIVE -> it.active
                    PeopleStatusFilter.SWITCHED_OFF -> !it.active
                }
            }
            .sortedWith(
                compareByDescending<Member> { it.active }
                    .thenBy { it.name.ifBlank { it.email }.lowercase() }
            )
        return PeopleGroups(
            active = visible.filter { it.active },
            switchedOff = visible.filterNot { it.active }
        )
    }

    /** Duplicate profiles for one email, for the migration report. */
    fun duplicateEmails(members: List<Member>): List<String> = members
        .filter { it.normalisedEmail.isNotEmpty() }
        .groupBy { Keys.normaliseEmail(it.email) }
        .filterValues { it.size > 1 }
        .keys
        .sorted()
}
