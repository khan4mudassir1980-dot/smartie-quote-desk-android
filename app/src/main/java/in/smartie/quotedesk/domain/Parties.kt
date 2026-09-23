package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.model.PartyRecord

/**
 * What the Parties screen draws: the ones still in use, and the ones somebody
 * archived, counted separately.
 *
 * **Archived parties are hidden, never dropped.** A party carries the history
 * of every quotation raised against it, so a list that silently forgot one
 * would make old quotations look as though they were issued to nobody. They
 * sit behind a collapsed heading with their own count, which is also how
 * `PurchaseHistory` treats removed requirements.
 */
data class PartyBook(
    val query: String = "",
    val active: List<PartyRecord> = emptyList(),
    val archived: List<PartyRecord> = emptyList()
) {
    val searching: Boolean get() = query.isNotBlank()
    val isEmpty: Boolean get() = active.isEmpty() && archived.isEmpty()

    /** Every party the search matched, of either kind. */
    val matchCount: Int get() = active.size + archived.size
}

/**
 * Arranging the saved customers.
 *
 * **Searching covers the four things somebody actually knows about a party**
 * when they go looking: the firm's name, the contact person, a phone number
 * and a GSTIN. Not the address, and not the notes — a note is somebody's
 * aside, and matching on it turns a search for "Sharma" into a list of every
 * party whose notes happen to mention one.
 *
 * A phone number matches on its digits alone, so `98765 43210`,
 * `+91-9876543210` and `9876543210` all find each other. A GSTIN matches
 * case-insensitively, because it is written both ways on paper.
 *
 * **Archived is decided by the reader, not here.** `toPartyRecord` reads it
 * through `asBoolOrNull`, which already takes `1`, `"1"`, `true` and `"yes"`
 * as archived and a missing field, `0` and `false` as active. Nothing in this
 * file re-derives that; `PartiesTest` pins it so it cannot quietly change.
 *
 * Pure: it takes the parties and returns what to draw, so the whole of it is
 * unit-tested without a screen.
 */
object Parties {

    fun build(parties: List<PartyRecord>, query: String = ""): PartyBook {
        val needle = query.trim()
        val matched = if (needle.isEmpty()) parties else parties.filter { matches(it, needle) }
        val (archived, active) = matched.partition { it.archived }
        return PartyBook(
            query = needle,
            active = active.sortedWith(BY_NAME),
            archived = archived.sortedWith(BY_NAME)
        )
    }

    /**
     * Whether this party is one somebody searching for [needle] meant.
     *
     * Name, contact, phone and GSTIN — see the file KDoc for what is
     * deliberately left out.
     */
    fun matches(party: PartyRecord, needle: String): Boolean {
        val text = needle.trim().lowercase()
        if (text.isEmpty()) return true
        if (displayName(party).lowercase().contains(text)) return true
        if (party.contact.lowercase().contains(text)) return true
        if (party.gstin.lowercase().contains(text)) return true

        // A phone matches on digits alone, so spacing and a country code
        // cannot hide a number somebody typed correctly.
        val digits = needle.filter { it.isDigit() }
        return digits.isNotEmpty() && party.phone.filter { it.isDigit() }.contains(digits)
    }

    /**
     * What to call a party on a list.
     *
     * The beta wrote the firm into `company` and the contact person into
     * `name`; `toPartyRecord` already untangles that. What it cannot rescue is
     * a row where **both** were blank, and a blank line in a list is worse
     * than a stated absence — so such a row falls back to the contact, and
     * then to saying plainly that it has no name.
     */
    fun displayName(party: PartyRecord): String =
        party.name.trim().ifEmpty { party.contact.trim().ifEmpty { UNNAMED } }

    /**
     * Whether this row has no firm name at all, so the list is showing a
     * contact person where a customer's name belongs.
     *
     * **What this does and does not catch.** The beta stored the firm in
     * `company` and the person in `name`; `toPartyRecord` puts those back the
     * right way round, so the classic damaged row — `c_beta_damaged` in the
     * fixtures — arrives here already untangled and is *not* flagged. What is
     * left is a row with a contact and no name of its own, which is what the
     * screen labels.
     *
     * **A beta row whose `company` was never filled in cannot be detected**,
     * and is not guessed at. It reads as a party called "Mrs Pinto", which is
     * indistinguishable from a sole trader legitimately recorded under their
     * own name. Flagging on a name that merely looks like a person's would put
     * a red tag on real customers, so the app shows it plainly and says
     * nothing it cannot know.
     */
    fun missingFirmName(party: PartyRecord): Boolean =
        party.name.isBlank() && party.contact.isNotBlank()

    const val UNNAMED = "Party not named"

    /** Case-insensitive, so `abc` and `ABC` do not sort into two groups. */
    private val BY_NAME: Comparator<PartyRecord> =
        compareBy<PartyRecord>({ displayName(it).lowercase() }, { it.id })
}
