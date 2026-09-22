package `in`.smartie.quotedesk.domain

/**
 * Who did something to a requirement, and what they are.
 *
 * **Resolved by uid, never by name**, which is the whole reason this exists.
 * Two accounts can carry the same display name — the Owner's and a Manager
 * test account both read "Mudassir Khan" on staging — so a name tells a
 * reader which words were typed and nothing about which person typed them.
 * A requirement stores both: `by`/`byUid` for who raised it, `rcvBy`/`rcvUid`
 * for who received it, `delBy`/`deletedBy` for who took it off the list.
 *
 * **The role is not always knowable, and that is not an error.** Another
 * person's `/users` document is readable only by an Owner or an Administrator
 * — `allow read: if (signedIn() && mine() == uid) || admin()` — so a Manager
 * or a Staff account has no way to look anyone up and is given no members to
 * look in. Every line falls back to the name alone, which is also what a
 * PWA-written row gets: those carry a name with no uid beside it.
 *
 * A deferred item in `docs/PROJECT-STATUS.md` records the way out: a
 * write-time role snapshot on the document itself, validated in the rules
 * against the writer's actual role, so every role could see who did what.
 * That needs a rules change and waits for a rules batch.
 */
object PurchasePeople {

    /** The separator the rest of the app already uses between two facts. */
    private const val JOIN = " · "

    /**
     * `"Mudassir Khan · Owner"`, or `"Mudassir Khan"` when the uid cannot be
     * resolved — because it is blank, because the row predates it, or because
     * this account may not read other people.
     *
     * Blank in, blank out: a caller with no name to show should show nothing
     * rather than a bare role, which would name nobody.
     */
    fun describe(name: String, uid: String, members: Map<String, Member>): String {
        val person = name.trim()
        if (person.isEmpty()) return ""
        val role = members[uid]?.takeIf { uid.isNotBlank() } ?: return person
        return person + JOIN + RoleTitles.word(role.role)
    }

    /** The same, for the many places that hold a list rather than a map. */
    fun byUid(members: List<Member>): Map<String, Member> =
        members.filter { it.uid.isNotBlank() }.associateBy { it.uid }
}
