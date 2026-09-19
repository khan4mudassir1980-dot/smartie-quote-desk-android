package `in`.smartie.quotedesk.domain

/**
 * The **only** place a role is turned into words for a person to read.
 *
 * ## Titles are not identities
 *
 * The business renamed two of its job titles. Nothing about the permission
 * system changed with them, and nothing about the stored data did either:
 *
 * | Stored `Role` | `wireValue` in Firestore | Title shown to a person |
 * |---|---|---|
 * | `OWNER` | `owner` | Owner |
 * | `ADMIN` | `admin` | Administrator |
 * | `STAFF` | `staff` | **Manager** |
 * | `WORKER` | `worker` | **Staff** |
 *
 * So a person the rules call `staff` — and who has exactly the permissions
 * `staff` has always had — is now *called* Manager, and a `worker` is now
 * *called* Staff. The documents, the security rules, the PWA and every
 * permission predicate are untouched and must stay that way: renaming a
 * stored value would need a migration and would break the PWA, which reads
 * the same collection.
 *
 * The consequence to keep in mind while reading this codebase: **`Role.STAFF`
 * means Manager and `Role.WORKER` means Staff.** Identifiers, wire values,
 * rules and permission tests all keep the old vocabulary on purpose, because
 * that is what is actually stored.
 *
 * Every user-visible role word goes through here. Ad-hoc `when (role)` blocks
 * producing strings are how the app ended up with three copies of this
 * mapping before, one of which would inevitably have been missed.
 */
object RoleTitles {

    /**
     * The badge an Owner wears: "Owner / Administrator", as the PWA labels
     * them. Unchanged by the rename, and deliberately so — this is the title
     * on a person's chip, not a word for a sentence.
     */
    const val OWNER_BADGE: String = "Owner / Administrator"

    const val OWNER: String = "Owner"

    const val ADMINISTRATOR: String = "Administrator"

    /** Stored `staff`. */
    const val MANAGER: String = "Manager"

    /** Stored `worker`. */
    const val STAFF: String = "Staff"

    /**
     * The title on a badge, a chip or a role selector.
     *
     * An Owner reads "Owner / Administrator" here because that is what the
     * position is called where it is displayed as a thing somebody *is*.
     */
    fun of(role: Role): String = when (role) {
        Role.OWNER -> OWNER_BADGE
        Role.ADMIN -> ADMINISTRATOR
        Role.STAFF -> MANAGER
        Role.WORKER -> STAFF
    }

    /**
     * The plain word for a role inside a sentence.
     *
     * Differs from [of] only for an Owner: "Only an Owner, Administrator or
     * Manager can…" reads properly, where the badge's compound label would
     * put Administrator in the list twice.
     */
    fun word(role: Role): String = when (role) {
        Role.OWNER -> OWNER
        Role.ADMIN -> ADMINISTRATOR
        Role.STAFF -> MANAGER
        Role.WORKER -> STAFF
    }

    /**
     * The title for a **stored wire value**, for the one surface that holds
     * raw wire values rather than a [Role]: the team audit log, whose
     * `detail.from` and `detail.to` are written as `"staff"`, `"worker"` and
     * so on by this app and by the PWA.
     *
     * Anything unrecognised is passed through **unchanged** rather than
     * guessed at. [Role.from] falls back to `WORKER` for an unknown value,
     * which is right for deciding permissions and quite wrong for telling
     * somebody what an audit entry says: it would print "Staff" over a value
     * nobody here understands.
     */
    fun ofWireValue(value: String): String {
        val trimmed = value.trim()
        val role = Role.entries.firstOrNull { it.wireValue.equals(trimmed, ignoreCase = true) }
        return role?.let { word(it) } ?: trimmed
    }

    /**
     * "an Owner, Administrator or Manager" — the roles that may do something,
     * with the article the sentences using it need.
     *
     * Built from the roles themselves so a message cannot drift from the
     * permission it describes, and so one rename reaches every such message
     * at once.
     */
    fun anyOf(vararg roles: Role): String {
        val titles = roles.map { word(it) }
        val list = when (titles.size) {
            0 -> return ""
            1 -> titles.first()
            else -> titles.dropLast(1).joinToString(", ") + " or " + titles.last()
        }
        return "an $list"
    }
}
