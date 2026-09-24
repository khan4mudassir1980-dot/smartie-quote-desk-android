package `in`.smartie.quotedesk.domain

/**
 * The two "add a line" forms, as what somebody actually typed.
 *
 * **These hold strings, not numbers, and that is the point.** An empty rate
 * box means "price not set" and must stay null rather than becoming zero
 * (audit P2); a box holding `12x` is a typing mistake and must be refused
 * rather than silently read as 12. Both distinctions vanish the moment a
 * screen parses a field into a `Double` and hands it on, so the parsing lives
 * here, where it is a unit test rather than an assertion about a form.
 */

/** A one-off line nobody will find in the catalogue. */
data class ManualEntry(
    val title: String = "",
    val quantity: String = "1",
    val unit: String = "",
    val rate: String = "",
    val spec: String = ""
) {
    /** Why this line cannot be added, or null when it can. */
    fun refusal(): String? = when {
        title.isBlank() -> QuoteLineEntry.NO_DESCRIPTION
        !QuoteLineEntry.reads(quantity) -> QuoteLineEntry.NOT_A_QUANTITY
        (QuoteLineEntry.number(quantity) ?: 0.0) <= 0.0 -> QuoteLineEntry.NOT_A_QUANTITY
        !QuoteLineEntry.readsOrEmpty(rate) -> QuoteLineEntry.NOT_A_RATE
        (QuoteLineEntry.number(rate) ?: 0.0) < 0.0 -> QuoteLineEntry.NEGATIVE_RATE
        else -> null
    }

    /**
     * This line, added to [draft]. **Only safe once [refusal] answered null.**
     *
     * An empty rate box stays null — "Price not set" — rather than becoming a
     * zero somebody would have to notice on the printed page.
     */
    fun addTo(draft: QuoteDraft, id: String): QuoteDraft = draft.addManual(
        id = id,
        title = title,
        quantity = QuoteLineEntry.number(quantity) ?: 1.0,
        rate = QuoteLineEntry.number(rate),
        unit = unit,
        spec = spec
    )
}

/** An opening, priced by the square foot. */
data class AreaEntry(
    val width: String = "",
    val height: String = "",
    val unit: DimensionUnit = DimensionUnit.MM,
    val count: String = "1",
    val rate: String = "",
    val minimumSqft: String = "",
    val title: String = ""
) {
    /** The opening this describes, or null while it is not a measurement. */
    fun toArea(): AreaLine? {
        val w = QuoteLineEntry.number(width) ?: return null
        val h = QuoteLineEntry.number(height) ?: return null
        val n = QuoteLineEntry.number(count) ?: return null
        return AreaLine(
            width = w,
            height = h,
            unit = unit,
            count = n,
            minimumSqft = QuoteLineEntry.number(minimumSqft)
        )
    }

    /**
     * Why this opening cannot be added, or null when it can.
     *
     * The measurement refusals are `QuoteArea.refusal`'s own, so the form and
     * the arithmetic cannot disagree about what a measurement is.
     */
    fun refusal(): String? {
        if (title.isBlank()) return QuoteLineEntry.NO_DESCRIPTION
        val area = toArea() ?: return QuoteArea.NOT_A_MEASUREMENT
        QuoteArea.refusal(area)?.let { return it }
        if (!QuoteLineEntry.readsOrEmpty(rate)) return QuoteLineEntry.NOT_A_RATE
        if ((QuoteLineEntry.number(rate) ?: 0.0) < 0.0) return QuoteLineEntry.NEGATIVE_RATE
        return null
    }

    /** **Only safe once [refusal] answered null.** */
    fun addTo(draft: QuoteDraft, id: String, key: String = ""): QuoteDraft {
        val area = toArea() ?: return draft
        return draft.addArea(
            id = id,
            area = area,
            rate = QuoteLineEntry.number(rate),
            title = title,
            key = key
        )
    }

    /**
     * The same opening, applied to a line that already exists.
     *
     * `setArea` recomputes the stored quantity from the opening, which is the
     * tie the card has no stepper in order to keep.
     *
     * **The rate is only touched when it actually changed**, because
     * `setRate` marks a line hand-typed and a hand-typed rate is never
     * repriced by a change of tier. Reopening the form to correct a width
     * would otherwise quietly freeze a catalogue rate at whatever tier it
     * happened to be on.
     */
    fun applyTo(draft: QuoteDraft, line: DraftLine): QuoteDraft {
        val area = toArea() ?: return draft
        val typed = QuoteLineEntry.number(rate)
        val moved = draft.setArea(line.id, area)
        return if (typed == line.rate) moved else moved.setRate(line.id, typed)
    }

    companion object {
        /** The form, reopened on a line that already exists. */
        fun of(line: DraftLine): AreaEntry {
            val area = line.area ?: return AreaEntry(title = line.title)
            return AreaEntry(
                width = QuoteLineEntry.plain(area.width),
                height = QuoteLineEntry.plain(area.height),
                unit = area.unit,
                count = QuoteLineEntry.plain(area.count),
                rate = line.rate?.let(QuoteLineEntry::plain).orEmpty(),
                minimumSqft = area.minimumSqft?.let(QuoteLineEntry::plain).orEmpty(),
                title = line.title
            )
        }
    }
}

/** Reading what somebody typed into a number box, and the words for it. */
object QuoteLineEntry {

    /**
     * The number in a box, or null when there is none.
     *
     * A blank box is null — **not zero** — because on a rate box blank means
     * "price not set" and zero means "free". `12x` is null too, which
     * [refusal] turns into a refusal rather than a silent 12.
     */
    fun number(typed: String): Double? = typed.trim()
        .takeIf { it.isNotEmpty() }
        ?.replace(",", "")
        ?.toDoubleOrNull()
        ?.takeIf { it.isFinite() }

    /** Whether the box holds a number at all. A blank box does not. */
    fun reads(typed: String): Boolean = number(typed) != null

    /** The same, but a blank box is allowed — an unset rate, a no-minimum. */
    fun readsOrEmpty(typed: String): Boolean = typed.isBlank() || reads(typed)

    /** A number put back into a box: no trailing zeros, no digit grouping. */
    fun plain(value: Double): String {
        if (value == value.toLong().toDouble()) return value.toLong().toString()
        return value.toString()
    }

    const val NO_DESCRIPTION = "Give the line a description"
    const val NOT_A_QUANTITY = "Enter how many, as a number greater than zero"
    const val NOT_A_RATE = "A rate has to be a number, or left empty"
    const val NEGATIVE_RATE = "A rate cannot be negative"
}
