package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.model.QuotationPartySnapshot
import `in`.smartie.quotedesk.data.model.RateTierV2

/**
 * Storing a draft on the device, so killing the app does not lose it
 * (audit C8).
 *
 * Deliberately hand-written: the app has no JSON library on its main
 * classpath, and a plain text format keeps the codec pure Kotlin, so the one
 * thing that must never go wrong here — an unset rate coming back as zero —
 * is covered by ordinary unit tests rather than by an Android-only one.
 *
 * ## New fields are appended, never inserted, and the old version still reads
 *
 * [decode] returns an **empty draft** when the version string does not match.
 * So bumping the version to add a field would silently erase every draft on
 * every phone at the next app update — the kind of thing that is only found
 * before it happens or long afterwards. Fields are therefore appended to the
 * end of a record and read with `getOrNull` and a default, and [decode]
 * accepts every version it has ever written. A `v1` record decodes under the
 * `v2` reader with the new fields at their defaults.
 *
 * ## What is *not* leniently parsed, and why
 *
 * `RateTierV2.from` falls back to `DEALER` and `InstallationMode.from` to
 * `FIXED`. Both are right when reading a document V8C4 wrote, which carries
 * its own stored figures — and both are wrong here. This is our own draft: a
 * value we cannot read means the stored text is damaged, and quietly choosing
 * for the person changes money in the **underquote** direction, which is the
 * dangerous one. An overquote gets argued down in conversation; an underquote
 * goes out, is accepted and is honoured. So a damaged value is recorded as a
 * [DraftFault] and surfaced, never silently resolved.
 */
object QuoteDraftCodec {

    /** Written today. [decode] still reads every version before it. */
    private const val VERSION = "v2"
    private val READABLE = setOf("v1", VERSION)

    private const val FIELD = '\u001f'
    private const val RECORD = '\u001e'
    private const val ESCAPE = '\\'

    /** The count a `v1` record has. Anything beyond it is appended. */
    private const val V1_LINE_FIELDS = 9

    fun encode(draft: QuoteDraft): String {
        val head = listOf(
            VERSION,
            draft.tier.wireValue,
            // --- appended in v2; a v1 head stops above ----------------------
            escape(draft.id),
            escape(draft.partyId),
            draft.transport.toString(),
            draft.installation?.mode?.wireValue.orEmpty(),
            draft.installation?.rate?.toString().orEmpty(),
            draft.installation?.basis?.toString().orEmpty(),
            draft.discount?.kind?.wireValue.orEmpty(),
            draft.discount?.value?.toString().orEmpty(),
            if (draft.gstEnabled) "1" else "0",
            draft.gstPercent?.toString().orEmpty(),
            draft.updatedAt.toString(),
            escape(draft.party.name),
            escape(draft.party.site),
            escape(draft.party.gstin),
            escape(draft.party.contact),
            escape(draft.party.phone),
            escape(draft.party.email),
            escape(draft.party.address),
            escape(draft.party.city),
            // --- appended after v2 shipped; an older head simply stops above -
            escape(draft.transportNote),
        ).joinToString(FIELD.toString())
        val lines = draft.lines.map { line ->
            listOf(
                escape(line.key),
                escape(line.title),
                escape(line.spec),
                escape(line.unit),
                line.quantity.toString(),
                line.rate?.toString().orEmpty(),
                line.originalRate?.toString().orEmpty(),
                line.tier.wireValue,
                if (line.rateEdited) "1" else "0",
                // --- appended in v2; a v1 record simply stops above ---------
                escape(line.id),
                if (line.manual) "1" else "0",
                line.area?.width?.toString().orEmpty(),
                line.area?.height?.toString().orEmpty(),
                line.area?.unit?.wireValue.orEmpty(),
                line.area?.count?.toString().orEmpty(),
                line.area?.minimumSqft?.toString().orEmpty(),
            ).joinToString(FIELD.toString())
        }
        return (listOf(head) + lines).joinToString(RECORD.toString())
    }

    /** Anything unreadable decodes to an empty draft rather than throwing. */
    fun decode(stored: String?): QuoteDraft {
        if (stored.isNullOrBlank()) return QuoteDraft()
        val records = stored.split(RECORD)
        val head = records.first().split(FIELD)
        if (head.firstOrNull() !in READABLE) return QuoteDraft()

        val faults = mutableSetOf<DraftFault>()
        // Strict, not `RateTierV2.from`, which falls back to DEALER — the
        // LOWER-priced tier and so the underquote direction. Recovered to
        // Client, the higher of the two offered, and the fault is surfaced.
        val tier = RateTierV2.entries
            .firstOrNull { it.wireValue.equals(head.getOrNull(1)?.trim(), ignoreCase = true) }
            ?: RateTierV2.CLIENT.also { faults += DraftFault.TIER }
        val lines = records.drop(1).mapNotNull { record ->
            val parts = record.split(FIELD)
            if (parts.size < V1_LINE_FIELDS) return@mapNotNull null
            val quantity = parts[4].toDoubleOrNull() ?: return@mapNotNull null
            if (!QuoteDraft.isValidQuantity(quantity) || quantity == 0.0) return@mapNotNull null

            val key = unescape(parts[0])
            val manual = parts.getOrNull(10) == "1"
            // A line is identified by its id. A `v1` record has none, so it is
            // given one here — deterministic, from the product key it did
            // carry, because `v1` could only ever hold catalogue lines.
            val id = parts.getOrNull(9)?.takeIf { it.isNotEmpty() }?.let(::unescape)
                ?: key.takeIf { it.isNotBlank() }?.let { "$V1_ID_PREFIX$it" }
                ?: return@mapNotNull null

            DraftLine(
                id = id,
                key = key,
                title = unescape(parts[1]),
                spec = unescape(parts[2]),
                unit = unescape(parts[3]),
                quantity = quantity,
                // An empty field is "Price not set" and must stay null.
                rate = parts[5].takeIf { it.isNotEmpty() }?.toDoubleOrNull(),
                originalRate = parts[6].takeIf { it.isNotEmpty() }?.toDoubleOrNull(),
                tier = RateTierV2.from(parts[7]),
                rateEdited = parts[8] == "1",
                manual = manual,
                area = areaOf(parts)
            )
        }
        // Present only when a rate or a value was stored, so an absent charge
        // is absent rather than damaged.
        val installation = head.getOrNull(6)?.takeIf { it.isNotEmpty() }?.let { rawRate ->
            val mode = InstallationMode.entries
                .firstOrNull { it.wireValue.equals(head.getOrNull(5)?.trim(), ignoreCase = true) }
            val rate = rawRate.toDoubleOrNull()
            if (mode == null || rate == null) {
                faults += DraftFault.INSTALLATION
                null
            } else {
                Installation(mode, rate, head.getOrNull(7)?.toDoubleOrNull() ?: 0.0)
            }
        }

        val discount = head.getOrNull(9)?.takeIf { it.isNotEmpty() }?.let { rawValue ->
            val kind = DiscountKind.entries
                .firstOrNull { it.wireValue.equals(head.getOrNull(8)?.trim(), ignoreCase = true) }
            val value = rawValue.toDoubleOrNull()
            if (kind == null || value == null) {
                faults += DraftFault.DISCOUNT
                null
            } else {
                Discount(kind, value)
            }
        }

        return QuoteDraft(
            id = head.getOrNull(2)?.let(::unescape).orEmpty(),
            tier = tier,
            lines = lines,
            partyId = head.getOrNull(3)?.let(::unescape).orEmpty(),
            party = partyOf(head),
            transport = head.getOrNull(4)?.toDoubleOrNull() ?: 0.0,
            installation = installation,
            discount = discount,
            // Absent on a v1 head, where GST was never part of a draft.
            gstEnabled = head.getOrNull(10)?.let { it == "1" } ?: true,
            gstPercent = head.getOrNull(11)?.takeIf { it.isNotEmpty() }?.toDoubleOrNull(),
            updatedAt = head.getOrNull(12)?.toLongOrNull() ?: 0L,
            transportNote = head.getOrNull(21)?.let(::unescape).orEmpty(),
            faults = faults
        )
    }

    private fun partyOf(head: List<String>): QuotationPartySnapshot = QuotationPartySnapshot(
        name = head.getOrNull(13)?.let(::unescape).orEmpty(),
        site = head.getOrNull(14)?.let(::unescape).orEmpty(),
        gstin = head.getOrNull(15)?.let(::unescape).orEmpty(),
        contact = head.getOrNull(16)?.let(::unescape).orEmpty(),
        phone = head.getOrNull(17)?.let(::unescape).orEmpty(),
        email = head.getOrNull(18)?.let(::unescape).orEmpty(),
        address = head.getOrNull(19)?.let(::unescape).orEmpty(),
        city = head.getOrNull(20)?.let(::unescape).orEmpty()
    )

    /** The opening, when every part of one was stored. */
    private fun areaOf(parts: List<String>): AreaLine? {
        val width = parts.getOrNull(11)?.toDoubleOrNull() ?: return null
        val height = parts.getOrNull(12)?.toDoubleOrNull() ?: return null
        val count = parts.getOrNull(14)?.toDoubleOrNull() ?: return null
        return AreaLine(
            width = width,
            height = height,
            unit = DimensionUnit.from(parts.getOrNull(13)),
            count = count,
            minimumSqft = parts.getOrNull(15)?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()
        )
    }

    /** Marks a line id recovered from a `v1` record rather than stored. */
    const val V1_ID_PREFIX = "v1_"

    private fun escape(value: String): String = buildString(value.length) {
        for (character in value) {
            when (character) {
                ESCAPE -> append(ESCAPE).append(ESCAPE)
                FIELD -> append(ESCAPE).append('f')
                RECORD -> append(ESCAPE).append('r')
                else -> append(character)
            }
        }
    }

    private fun unescape(value: String): String = buildString(value.length) {
        var index = 0
        while (index < value.length) {
            val character = value[index]
            if (character == ESCAPE && index + 1 < value.length) {
                when (val next = value[index + 1]) {
                    ESCAPE -> append(ESCAPE)
                    'f' -> append(FIELD)
                    'r' -> append(RECORD)
                    else -> append(next)
                }
                index += 2
            } else {
                append(character)
                index++
            }
        }
    }
}
