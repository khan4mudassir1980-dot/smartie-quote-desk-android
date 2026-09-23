package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.mapping.Keys
import `in`.smartie.quotedesk.data.mapping.asBoolOrNull
import `in`.smartie.quotedesk.data.mapping.asDoubleOrNull
import `in`.smartie.quotedesk.data.mapping.asStringOrNull
import `in`.smartie.quotedesk.data.model.ProductRecord
import java.math.BigDecimal

/** Who is writing, for the provenance fields V8C4 keeps. */
data class ProductAuthor(val name: String, val uid: String)

/**
 * The unit a product is priced in, and the one value that means area pricing.
 *
 * **A free string, not a menu.** V8C4's `#fU` is a text input
 * (`index.html:6025`, saved at `:6058`), and the price book uses at least
 * `per m`, `per pc`, `per kg`, `per rft`, `per ft` and `per rm` besides
 * `per sq ft`. A closed list here would rewrite every one of them the first
 * time somebody corrected a rate.
 *
 * **[isArea] folds case and nothing else.** `asStringOrNull` already trims
 * every value on the way in, and every other wire reader in this app folds
 * case — `asBoolOrNull` does, and the catalogue sorts on `name.lowercase()`.
 * So `Per sq ft` is area-priced and `PER SQ FT` is area-priced. `sqft` and
 * `sq ft` are **different strings, not different cases**, and are not: such a
 * product is priced per piece, which is visible on its list row rather than
 * silent, and one edit fixes it.
 */
object ProductUnit {

    /** V8C4's own spelling, and the only value that turns area pricing on. */
    const val AREA = "per sq ft"

    /** What a cleared box falls back to, exactly as V8C4's `|| "each"` does. */
    const val EACH = "each"

    fun isArea(stored: String): Boolean = stored.trim().lowercase() == AREA

    /** A typed box, as it will be stored. A blank one is `each`. */
    fun normalise(typed: String): String = typed.trim().ifEmpty { EACH }
}

/**
 * A product as somebody typed it.
 *
 * **Prices and numbers are strings**, because a blank box means "not set" and
 * must never arrive as `0`. `ProductRecord` keeps the same distinction with
 * `Double?`, for the same reason (audit P2).
 *
 * [unit] is the text box's contents, not a flag. Deriving area pricing from a
 * toggle instead would mean writing `each` whenever the toggle was off, and a
 * complete write would then convert every `per m`, `per pc` and `per kg`
 * product to `each` the first time anybody corrected its rate.
 */
data class ProductDraft(
    val name: String = "",
    val unit: String = "",
    val dealer: String = "",
    val client: String = "",
    val minSqft: String = "",
    val gst: String = "",
    val active: Boolean = true
) {
    /** Why this product cannot be saved, or null when it can. */
    fun refusal(): String? = when {
        name.isBlank() -> ProductWrite.NAME_REQUIRED
        !ProductWrite.priceIsSayable(dealer) -> ProductWrite.PRICE_NOT_A_NUMBER
        !ProductWrite.priceIsSayable(client) -> ProductWrite.PRICE_NOT_A_NUMBER
        gst.isBlank() -> ProductWrite.GST_REQUIRED
        gst.trim().toDoubleOrNull() == null -> ProductWrite.GST_NOT_A_NUMBER
        gst.trim().toDouble() !in 0.0..ProductWrite.MAX_GST -> ProductWrite.GST_OUT_OF_RANGE
        minSqft.isNotBlank() && minSqft.trim().toDoubleOrNull() == null ->
            ProductWrite.MIN_SQFT_NOT_A_NUMBER
        minSqft.isNotBlank() && minSqft.trim().toDouble() < 0 -> ProductWrite.MIN_SQFT_NEGATIVE
        else -> null
    }
}

/** What a product write comes to, decided before anything reaches Firestore. */
sealed interface ProductPlan {
    data class Write(val docId: String, val data: Map<String, Any?>) : ProductPlan

    /** Nothing changed. Write nothing, and say nothing happened. */
    data object NoChange : ProductPlan

    /** The write must not be attempted; [message] is for the person. */
    data class Refused(val message: String) : ProductPlan
}

/**
 * Writing a product, in the shape V8C4 already reads.
 *
 * ## Why every field is written, not only the changed ones
 *
 * The deployed `/products` rule validates the **merged post-state**, not the
 * keys an update touches. A document an older PWA version left with `gst` as
 * the string `"18"` or `active` as `1` therefore refuses even a correction
 * that does not go near those fields — there is no partial fix. Both halves
 * of that are pinned in `firestore/tests/catalogue.test.js`.
 *
 * So the editor writes a complete, correctly typed document every time, the
 * way `fbPushProduct` already does, and the legacy defects repair themselves
 * as a side effect of any edit. That is why N5.7 changes no rule.
 *
 * ## Where the values come from, and why it matters
 *
 * **Never from a seed, and never from the screen's opening state.** The
 * catalogue importer carries seed rates in every payload, and running it over
 * edited data silently reverts them; an editor that fell back to a seed value
 * would reintroduce that one product at a time. [plan] takes [stored] from the
 * caller's **fresh read inside the transaction**, applies only the fields the
 * person actually changed, and takes every other field from that read. A name
 * corrected on another phone while this sheet was open survives, and two
 * people can only collide on a field they both edited.
 *
 * ## A null price is data, and an absent one is not allowed
 *
 * V8C4's `rate()` returns a null for a price the book does not give, and
 * `applyProductDoc` reads a stored `null` back as "deliberately not set"
 * rather than falling back to the seed figure — so `contractor: null` is
 * carried through untouched and **never dropped**. It cannot be left out
 * either: the rule reads the key bare, and the emulator reports an absent key
 * as `evaluation error`, not as null. So a missing price key is written as
 * `null`.
 *
 * The contractor tier is not offered on a new quotation, and is not editable
 * here at all — it is read from the document and written back exactly as
 * found, so quotations already issued at it keep their rate.
 *
 * Pure: every branch is decided from [stored] and the drafts, so the whole of
 * it is unit-tested without Firebase.
 */
object ProductWrite {

    const val MAX_GST = 28.0

    /** What the catalogue shows when a document carries no GST, and V8C4's own fallback. */
    const val DEFAULT_GST = 18.0

    private val PRICE_FIELDS = listOf("dealer", "contractor", "client")

    /**
     * Where this product's edit must land.
     *
     * **`group__model`, always — V8C4 reads and writes no other id.** `docId`
     * (`index.html:5723`) is the only document id the PWA computes for a
     * product; `pid` (`:5682`) is the pipe-separated value it puts in the `id`
     * and `key` *fields*, and is not a document id. Seeding once wrote
     * documents at the pipe id, and the migration left them in place, so the
     * record on screen may have been read from one — but writing back to it
     * would leave the PWA reading a stale rate, because the PWA never looks
     * there.
     *
     * Writing `updated` to the canonical document also makes the native
     * catalogue converge on it: `canonicalProduct` prefers a `schemaVersion`
     * 2 document and otherwise the most recently updated one, so after this
     * write both apps read the same document.
     */
    fun targetDocId(group: String, seedModel: String): String =
        Keys.productDocId(group, seedModel)

    /**
     * The edit, as a write or a refusal.
     *
     * [record] is what the sheet was opened on, and supplies identity only.
     * [loaded] is the draft as the sheet opened; [draft] is what the person
     * has now; a field is taken from [draft] when those two differ and from
     * [stored] otherwise.
     *
     * [stored] is the raw field map the caller read **inside the
     * transaction** — raw, because `ProductRecord` cannot tell a stored
     * `null` from an absent key and this write turns on that difference. When
     * the canonical document is being materialised from a legacy one, the
     * caller overlays the target's fields on the source's, so nothing the
     * legacy document held is lost on the way across.
     */
    fun plan(
        record: ProductRecord,
        loaded: ProductDraft,
        draft: ProductDraft,
        stored: Map<String, Any?>,
        author: ProductAuthor,
        at: Long,
        canEdit: Boolean
    ): ProductPlan {
        if (!canEdit) return ProductPlan.Refused(CANNOT_EDIT)
        draft.refusal()?.let { return ProductPlan.Refused(it) }

        val group = stored.text("group") ?: record.group.trim().ifEmpty { null }
            ?: return ProductPlan.Refused(GROUP_UNREADABLE)
        val seedModel = when (val derived = seedModelOf(stored, record)) {
            is DerivedModel.Value -> derived.value
            is DerivedModel.Unreadable -> return ProductPlan.Refused(derived.message)
        }
        val model = stored.text("model") ?: record.model.trim().ifEmpty { null } ?: seedModel

        // A stored price that is neither blank, nor the PWA's unset marker,
        // nor a number is not something to guess at: writing it back as "not
        // set" would wipe a rate nobody asked to change.
        PRICE_FIELDS.firstOrNull { !storedPriceIsReadable(stored, it) }
            ?.let { return ProductPlan.Refused(storedPriceUnreadable(it)) }

        if (loaded == draft) return ProductPlan.NoChange

        val fields = buildMap<String, Any?> {
            // Identity. Written on every save so a document that predates
            // either field ends up carrying both, and so a canonical document
            // materialised from a legacy one is complete rather than partial.
            put("id", Keys.productKey(group, seedModel))
            put("key", Keys.productKey(group, seedModel))
            put("group", group)
            put("seedModel", seedModel)
            put("model", model)

            // The edited fields, each from the draft only when it changed.
            put("name", pick(draft.name != loaded.name, draft.name.trim()) { stored.text("name").orEmpty() })
            put("unit", pick(draft.unit != loaded.unit, ProductUnit.normalise(draft.unit)) {
                ProductUnit.normalise(stored.text("unit").orEmpty())
            })
            put("gst", pick(draft.gst != loaded.gst, draft.gst.trim().toDouble()) {
                stored["gst"].asDoubleOrNull()?.coerceIn(0.0, MAX_GST) ?: DEFAULT_GST
            })
            put("dealer", pick(draft.dealer != loaded.dealer, priceOf(draft.dealer)) {
                stored["dealer"].asDoubleOrNull()
            })
            put("client", pick(draft.client != loaded.client, priceOf(draft.client)) {
                stored["client"].asDoubleOrNull()
            })
            put("active", pick(draft.active != loaded.active, draft.active) {
                stored["active"].asBoolOrNull() ?: true
            })
            put("minSqft", pick(draft.minSqft != loaded.minSqft, priceOf(draft.minSqft)) {
                stored["minSqft"].asDoubleOrNull()
            })

            // Never editable here, and never dropped: a stored null is a
            // deliberate "Price not set" that V8C4 reads as one, and an absent
            // key is an evaluation error in the rule. Both become null.
            put("contractor", stored["contractor"].asDoubleOrNull())

            // Not required by the rule, so a merge would preserve them on an
            // existing document — but a canonical document being materialised
            // from a legacy one has nothing to preserve, and losing
            // `categoryId` would move the product to the Other shelf.
            stored.text("categoryId")?.let { put("categoryId", it) }
            stored.text("spec")?.let { put("spec", it) }
            stored["kg"].asDoubleOrNull()?.let { put("kg", it) }

            put("updated", at)
            put("by", author.name)
            put("byUid", author.uid)
        }

        return ProductPlan.Write(docId = targetDocId(group, seedModel), data = fields)
    }

    /** Every editable field, for a caller building a draft from a record. */
    fun draftOf(stored: ProductRecord): ProductDraft = ProductDraft(
        name = stored.name,
        unit = stored.unit,
        dealer = stored.dealer?.let(::boxText).orEmpty(),
        client = stored.client?.let(::boxText).orEmpty(),
        minSqft = stored.minSqft?.let(::boxText).orEmpty(),
        gst = boxText(stored.gst),
        active = stored.active
    )

    /**
     * A number as it goes into an editable box.
     *
     * Plain and **ungrouped**, unlike `Money.formatQuantity`: a box showing
     * `1,23,456.50` would not parse back with `toDoubleOrNull`, so the person
     * would be refused their own unedited value.
     */
    fun boxText(value: Double): String =
        BigDecimal(value.toString()).stripTrailingZeros().toPlainString()

    // --- identity -------------------------------------------------------------------

    private sealed interface DerivedModel {
        data class Value(val value: String) : DerivedModel
        data class Unreadable(val message: String) : DerivedModel
    }

    /**
     * The seed model this document is keyed by.
     *
     * **Order matters, and it is not the obvious one.** The stored
     * `seedModel` first; then the `id`/`key` *field*, which is `group|model`
     * and loses nothing; then the document id, which is `group__model` with
     * `/ . # $ [ ]` each replaced by `_` and therefore **lossy**; and only
     * then `model`.
     *
     * `model` must not come earlier, however tempting: V8C4 writes
     * `seedModel` from the seed but `model` from `o.md || it.m`, so `model`
     * is the *display override* where one is set. Deriving from it would
     * compute a different `docId` and land the write on a second document —
     * exactly the duplicate the canonical id exists to prevent.
     *
     * When only the document id is left and its model half contains a `_`,
     * there is no way to tell a real underscore from a sanitised `/` or `.`
     * — and both occur in live models (`SIEBAL58H/V`, `SIE2.5MSMALL`). That
     * is refused rather than guessed: a wrong `seedModel` makes V8C4's
     * `applyProductDoc` materialise a new custom item instead of matching the
     * seed.
     */
    private fun seedModelOf(stored: Map<String, Any?>, record: ProductRecord): DerivedModel {
        stored.text("seedModel")?.let { return DerivedModel.Value(it) }

        val fromField = (stored.text("id") ?: stored.text("key"))
            ?.let { Keys.splitProductKey(it)?.second }
            ?.takeIf { it.isNotBlank() }
        if (fromField != null) return DerivedModel.Value(fromField)

        val fromDocId = Keys.splitProductKey(record.documentId)?.second?.takeIf { it.isNotBlank() }
        if (fromDocId != null) {
            if (fromDocId.contains('_')) return DerivedModel.Unreadable(SEED_MODEL_AMBIGUOUS)
            return DerivedModel.Value(fromDocId)
        }

        stored.text("model")?.let { return DerivedModel.Value(it) }
        return DerivedModel.Unreadable(SEED_MODEL_UNREADABLE)
    }

    // --- plumbing -------------------------------------------------------------------

    private inline fun <T> pick(changed: Boolean, typed: T, fromStored: () -> T): T =
        if (changed) typed else fromStored()

    private fun Map<String, Any?>.text(field: String): String? = this[field].asStringOrNull()

    /** A typed price box: blank is "not set", anything else must be a number ≥ 0. */
    fun priceIsSayable(typed: String): Boolean {
        if (typed.isBlank()) return true
        val value = typed.trim().toDoubleOrNull() ?: return false
        return value.isFinite() && value >= 0
    }

    private fun priceOf(typed: String): Double? =
        if (typed.isBlank()) null else typed.trim().toDouble()

    /**
     * Whether a stored price can be written back without inventing anything.
     *
     * Absent, `null` and the PWA's `∅` marker all mean "not set" and all
     * become `null`. A formatted number like `"1,250.50"` is read. Anything
     * else is refused, because writing it back as "not set" would wipe a rate
     * nobody asked to change.
     */
    private fun storedPriceIsReadable(stored: Map<String, Any?>, field: String): Boolean {
        if (!stored.containsKey(field)) return true
        val raw = stored[field] ?: return true
        if (raw.asDoubleOrNull() != null) return true
        return raw.asStringOrNull() == null
    }

    const val NAME_REQUIRED = "Enter the product's name"
    const val PRICE_NOT_A_NUMBER = "A price must be blank, or a number that is zero or more"
    const val GST_REQUIRED = "Enter the GST percentage, for example 18"
    const val GST_NOT_A_NUMBER = "GST must be a number, for example 18"
    const val GST_OUT_OF_RANGE = "GST must be between 0 and 28"
    const val MIN_SQFT_NOT_A_NUMBER = "The minimum chargeable area must be a number"
    const val MIN_SQFT_NEGATIVE = "The minimum chargeable area cannot be negative"
    const val CANNOT_EDIT = "Only an Owner or Administrator can change a product"
    const val GROUP_UNREADABLE =
        "This product has no group stored, so there is no safe document to save it to"
    const val SEED_MODEL_UNREADABLE =
        "This product has no model stored, so there is no safe document to save it to"
    const val SEED_MODEL_AMBIGUOUS =
        "This product's model can only be read from its document id, and it contains an " +
            "underscore that may stand for a / or a . — type the model on the PWA once so " +
            "it is stored, then edit it here"

    fun storedPriceUnreadable(field: String): String =
        "The stored ${field.replaceFirstChar { it.uppercase() }} price cannot be read as a " +
            "number, so saving would wipe it — correct it on the PWA first"
}
