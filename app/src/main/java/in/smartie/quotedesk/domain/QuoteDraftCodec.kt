package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.model.RateTierV2

/**
 * Storing a draft on the device, so killing the app does not lose it
 * (audit C8).
 *
 * Deliberately hand-written: the app has no JSON library on its main
 * classpath, and a plain text format keeps the codec pure Kotlin, so the one
 * thing that must never go wrong here — an unset rate coming back as zero —
 * is covered by ordinary unit tests rather than by an Android-only one.
 */
object QuoteDraftCodec {

    private const val VERSION = "v1"
    private const val FIELD = ''
    private const val RECORD = ''
    private const val ESCAPE = '\\'

    fun encode(draft: QuoteDraft): String {
        val head = listOf(VERSION, draft.tier.wireValue).joinToString(FIELD.toString())
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
            ).joinToString(FIELD.toString())
        }
        return (listOf(head) + lines).joinToString(RECORD.toString())
    }

    /** Anything unreadable decodes to an empty draft rather than throwing. */
    fun decode(stored: String?): QuoteDraft {
        if (stored.isNullOrBlank()) return QuoteDraft()
        val records = stored.split(RECORD)
        val head = records.first().split(FIELD)
        if (head.firstOrNull() != VERSION) return QuoteDraft()
        val tier = RateTierV2.from(head.getOrNull(1))
        val lines = records.drop(1).mapNotNull { record ->
            val parts = record.split(FIELD)
            if (parts.size < 9) return@mapNotNull null
            val key = unescape(parts[0])
            val quantity = parts[4].toDoubleOrNull() ?: return@mapNotNull null
            if (key.isBlank() || !QuoteDraft.isValidQuantity(quantity) || quantity == 0.0) {
                return@mapNotNull null
            }
            DraftLine(
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
            )
        }
        return QuoteDraft(tier = tier, lines = lines)
    }

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
