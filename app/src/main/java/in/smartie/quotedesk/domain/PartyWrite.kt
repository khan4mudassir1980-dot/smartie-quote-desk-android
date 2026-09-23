package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.model.PartyRecord

/** Who is writing, for the four provenance fields V8C4 keeps. */
data class PartyAuthor(val name: String, val uid: String)

/**
 * A party as somebody typed it.
 *
 * Field names are V8C4's exactly — `city`, not `site`. The quotation's own
 * party *snapshot* does carry a `site`, because a quotation records where the
 * work is going; a customer record does not, and inventing a parallel field
 * would give the PWA a value it never reads.
 */
data class PartyDraft(
    val name: String = "",
    val type: String = PartyWrite.TYPE_CLIENT,
    val city: String = "",
    val gstin: String = "",
    val contact: String = "",
    val phone: String = "",
    val email: String = "",
    val address: String = "",
    val notes: String = ""
) {
    /** Why this party cannot be saved, or null when it can. */
    fun refusal(): String? = when {
        name.isBlank() -> PartyWrite.NAME_REQUIRED
        else -> null
    }
}

/** What a party write comes to, decided before anything reaches Firestore. */
sealed interface PartyPlan {
    data class Write(
        val docId: String,
        val data: Map<String, Any?>,
        val merge: Boolean = true
    ) : PartyPlan

    /** Nothing changed. Write nothing, and say nothing happened. */
    data object NoChange : PartyPlan

    /** The write must not be attempted; [message] is for the person. */
    data class Refused(val message: String) : PartyPlan
}

/**
 * Writing a customer, in the shape V8C4 already reads.
 *
 * ## Two save semantics, and they are not interchangeable
 *
 * **This file's [edit] is a replace.** The Parties editor shows every field
 * and saves exactly what is on screen, so an Owner who empties the GSTIN box
 * means it: the field is written as `""` and the value is gone. Anything else
 * would make a deliberate correction impossible — a person who discovers a
 * GSTIN was entered against the wrong customer has no way to take it off.
 *
 * **[mergeInto] is a fill.** The quotation side's "Save this customer", which
 * lands in N5.8, is V8C4's `saveParty`: it fills gaps, takes genuine changes,
 * and **never blanks a detail already held**. There the person was quoting,
 * not editing a customer, and the form they filled in is not a statement that
 * everything they left empty should be forgotten.
 *
 * Both are here so the difference is a tested function rather than a sentence
 * somebody has to remember. [mergeInto] has no caller until N5.8; that is not
 * the dead code N5.0 removed, which described a shape nothing writes — this is
 * a pure function with a named consumer one batch away and its own tests.
 *
 * ## What the rules let each role do
 *
 * The deployed `/customers` rule allows an update when
 * `request.resource.data.id == id` and either `admin()`, or `staff()` with the
 * name unchanged and the party neither archived before nor after. So a Manager
 * may correct details, may **not** rename, and may neither archive nor
 * unarchive — and cannot touch an archived party at all. The planner refuses
 * those rather than letting a write go out for the server to bounce, because a
 * permission error tells nobody anything.
 *
 * Pure: every branch is decided from the stored record and the draft, so the
 * whole of it is unit-tested without Firebase.
 */
object PartyWrite {

    const val ID_PREFIX = "c_"

    const val TYPE_DEALER = "dealer"
    const val TYPE_CONTRACTOR = "contractor"
    const val TYPE_CLIENT = "client"

    /** V8C4's three, and the one a party falls back to. */
    val TYPES: List<String> = listOf(TYPE_DEALER, TYPE_CONTRACTOR, TYPE_CLIENT)

    // --- creating ---------------------------------------------------------------

    /**
     * A new customer.
     *
     * [alreadyExists] is the N4.4 lesson, not paranoia about the id generator:
     * a `set` on an id already taken is evaluated by the rules as an
     * **update**, which any non-Worker may make, so a collision would quietly
     * overwrite somebody else's customer instead of failing. The caller keeps
     * one id for as long as the person is trying to add one party, so a retry
     * after an ambiguous failure lands on the same document and is refused
     * honestly rather than writing a twin.
     */
    fun create(
        id: String,
        draft: PartyDraft,
        author: PartyAuthor,
        at: Long,
        alreadyExists: Boolean = false
    ): PartyPlan {
        draft.refusal()?.let { return PartyPlan.Refused(it) }
        if (alreadyExists) return PartyPlan.Refused(ALREADY_EXISTS)
        return PartyPlan.Write(
            docId = id,
            // Written whole: a new customer has nothing underneath it.
            merge = false,
            data = buildMap {
                put("id", id)
                put("name", draft.name.trim())
                put("type", normaliseType(draft.type))
                put("city", draft.city.trim())
                put("gstin", draft.gstin.trim())
                put("contact", draft.contact.trim())
                put("phone", draft.phone.trim())
                put("email", draft.email.trim())
                put("address", draft.address.trim())
                put("notes", draft.notes.trim())
                put("archived", false)
                put("t", at)
                put("by", author.name)
                put("byUid", author.uid)
                put("updated", at)
                put("upBy", author.name)
                put("upUid", author.uid)
            }
        )
    }

    // --- editing one --------------------------------------------------------------

    /**
     * The editor: **what is on screen is what is stored**.
     *
     * A field the person emptied is written as `""`, which is how a wrong
     * GSTIN comes off a customer. `id` is written on every edit so the rule's
     * `data.id == id` holds even for a legacy document that never carried one.
     *
     * [canRename] and [canArchive] are the viewer's, and the refusals below
     * exist so a UI bug cannot send a write the rules would bounce with a
     * message nobody can act on.
     */
    fun edit(
        stored: PartyRecord,
        draft: PartyDraft,
        author: PartyAuthor,
        at: Long,
        canRename: Boolean,
        canArchive: Boolean
    ): PartyPlan {
        draft.refusal()?.let { return PartyPlan.Refused(it) }
        val renaming = draft.name.trim() != stored.name.trim()
        if (renaming && !canRename) return PartyPlan.Refused(CANNOT_RENAME)
        // The rules refuse a Manager any write at all to an archived party,
        // so there is nothing to attempt and a sentence is better than a
        // permission error.
        if (stored.archived && !canArchive) return PartyPlan.Refused(ARCHIVED_IS_READ_ONLY)

        val fields = buildMap {
            put("type", normaliseType(draft.type))
            put("city", draft.city.trim())
            put("gstin", draft.gstin.trim())
            put("contact", draft.contact.trim())
            put("phone", draft.phone.trim())
            put("email", draft.email.trim())
            put("address", draft.address.trim())
            put("notes", draft.notes.trim())
            if (canRename) put("name", draft.name.trim())
        }
        if (!changed(stored, fields)) return PartyPlan.NoChange

        return PartyPlan.Write(
            docId = stored.id,
            merge = true,
            data = fields + mapOf(
                "id" to stored.id,
                "updated" to at,
                "upBy" to author.name,
                "upUid" to author.uid
            )
        )
    }

    /**
     * Archiving, and bringing one back. **Owner and Administrator only** — the
     * rules refuse a Manager an `archived` key in either direction.
     */
    fun setArchived(
        stored: PartyRecord,
        archived: Boolean,
        author: PartyAuthor,
        at: Long,
        canArchive: Boolean
    ): PartyPlan {
        if (!canArchive) return PartyPlan.Refused(CANNOT_ARCHIVE)
        if (stored.archived == archived) return PartyPlan.NoChange
        return PartyPlan.Write(
            docId = stored.id,
            merge = true,
            data = mapOf(
                "id" to stored.id,
                "archived" to archived,
                "updated" to at,
                "upBy" to author.name,
                "upUid" to author.uid
            )
        )
    }

    // --- the other semantics, for N5.8 -----------------------------------------------

    /**
     * V8C4's `saveParty`: **fill the gaps, take the genuine changes, never
     * blank anything already held.**
     *
     * The quotation side's "Save this customer" uses this. The person was
     * quoting, not editing a customer, so a box they left empty is silence
     * rather than an instruction to forget what is stored. A box they filled
     * in differently is a correction and is taken.
     *
     * Contrast [edit], where an empty box **is** the instruction.
     */
    fun mergeInto(
        stored: PartyRecord,
        draft: PartyDraft,
        author: PartyAuthor,
        at: Long
    ): PartyPlan {
        val incoming = mapOf(
            "name" to draft.name.trim(),
            "type" to draft.type.trim(),
            "city" to draft.city.trim(),
            "gstin" to draft.gstin.trim(),
            "contact" to draft.contact.trim(),
            "phone" to draft.phone.trim(),
            "email" to draft.email.trim(),
            "address" to draft.address.trim(),
            "notes" to draft.notes.trim()
        )
        // A blank is not a change. That single line is the whole difference
        // between this and `edit`.
        val fields = incoming.filterValues { it.isNotEmpty() }
            .filter { (field, value) -> value != current(stored, field) }
        if (fields.isEmpty()) return PartyPlan.NoChange

        return PartyPlan.Write(
            docId = stored.id,
            merge = true,
            data = fields + mapOf(
                "id" to stored.id,
                "updated" to at,
                "upBy" to author.name,
                "upUid" to author.uid
            )
        )
    }

    // --- plumbing -----------------------------------------------------------------

    fun normaliseType(value: String): String =
        TYPES.firstOrNull { it.equals(value.trim(), ignoreCase = true) } ?: TYPE_CLIENT

    private fun changed(stored: PartyRecord, fields: Map<String, Any?>): Boolean =
        fields.any { (field, value) -> value != current(stored, field) }

    /** The stored value of one editable field, by its V8C4 name. */
    private fun current(stored: PartyRecord, field: String): String = when (field) {
        "name" -> stored.name.trim()
        "type" -> normaliseType(stored.type)
        "city" -> stored.city.trim()
        "gstin" -> stored.gstin.trim()
        "contact" -> stored.contact.trim()
        "phone" -> stored.phone.trim()
        "email" -> stored.email.trim()
        "address" -> stored.address.trim()
        "notes" -> stored.notes.trim()
        else -> ""
    }

    /** Every editable field, for a caller building a draft from a record. */
    fun draftOf(stored: PartyRecord): PartyDraft = PartyDraft(
        name = stored.name,
        type = normaliseType(stored.type),
        city = stored.city,
        gstin = stored.gstin,
        contact = stored.contact,
        phone = stored.phone,
        email = stored.email,
        address = stored.address,
        notes = stored.notes
    )

    const val NAME_REQUIRED = "Enter the party's name"
    const val ALREADY_EXISTS = "That party was already saved"
    const val CANNOT_RENAME = "Only an Owner or Administrator can rename a party"
    const val CANNOT_ARCHIVE = "Only an Owner or Administrator can archive a party"
    const val ARCHIVED_IS_READ_ONLY =
        "That party is archived — only an Owner or Administrator can change it"
}
