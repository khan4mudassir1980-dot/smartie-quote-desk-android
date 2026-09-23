package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.model.ProductRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The complete product write, and the three things it must never do.
 *
 * **It must never write a seed value.** The catalogue importer carries seed
 * rates in every payload, so running it over edited data silently reverts
 * them; an editor that fell back to a seed rate would reintroduce that one
 * product at a time, invisibly. Every unchanged field here comes from the
 * caller's fresh read inside the transaction, and
 * `a hand-edited rate survives a unit change` is the test that pins it.
 *
 * **It must never drop a contractor price.** V8C4's `rate()` returns a null
 * for a price the book does not give, and `applyProductDoc` reads a stored
 * null back as "deliberately not set" rather than falling back to the seed
 * figure. It cannot be left absent either: the rule reads the key bare and
 * the emulator reports an absent key as an evaluation error, which
 * `firestore/tests/catalogue.test.js` settles in the engine's own words.
 *
 * **It must never rewrite a unit nobody touched.** Far more of the catalogue
 * is `per m`, `per pc` or `per kg` than is `per sq ft`, and a complete write
 * that derived the unit from a toggle would convert all of them.
 */
class ProductWriteTest {

    private val owner = ProductAuthor(name = "Mudassir", uid = "uid_owner")

    private val record = ProductRecord(
        documentId = "gateMotors__SIE1000",
        key = "gateMotors|SIE1000",
        group = "gateMotors",
        seedModel = "SIE1000",
        model = "SIE1000",
        name = "Sliding gate motor 1000 kg",
        unit = "each",
        gst = 18.0,
        dealer = 18500.0,
        client = 25900.0
    )

    /** What the document actually holds, as the transaction re-read it. */
    private fun stored(vararg pairs: Pair<String, Any?>): Map<String, Any?> = mapOf(
        "id" to "gateMotors|SIE1000",
        "key" to "gateMotors|SIE1000",
        "group" to "gateMotors",
        "seedModel" to "SIE1000",
        "model" to "SIE1000",
        "name" to "Sliding gate motor 1000 kg",
        "unit" to "each",
        "gst" to 18.0,
        "dealer" to 18500.0,
        "contractor" to null,
        "client" to 25900.0,
        "categoryId" to "cat-sliding",
        "active" to true
    ) + pairs

    private val loaded = ProductWrite.draftOf(record)

    private fun plan(
        draft: ProductDraft,
        stored: Map<String, Any?> = stored(),
        on: ProductRecord = record,
        loadedAs: ProductDraft = loaded,
        canEdit: Boolean = true
    ) = ProductWrite.plan(
        record = on, loaded = loadedAs, draft = draft, stored = stored,
        author = owner, at = 1_758_600_000_000L, canEdit = canEdit
    )

    private fun written(plan: ProductPlan): Map<String, Any?> = (plan as ProductPlan.Write).data
    private fun docIdOf(plan: ProductPlan): String = (plan as ProductPlan.Write).docId

    // --- the rate must come from Firestore, never from a seed -----------------------

    @Test
    fun `a hand-edited rate survives a unit change`() {
        // The whole reason no import is run to correct the units. The sheet
        // was opened when the dealer rate was 18500; somebody has since
        // corrected it to 21000 on another phone. Changing only the unit must
        // carry the 21000, not the 18500 this screen still shows, and above
        // all not a seed figure.
        val fields = written(
            plan(
                draft = loaded.copy(unit = "per sq ft"),
                stored = stored("dealer" to 21000.0)
            )
        )
        assertEquals(21000.0, fields["dealer"])
        assertEquals("per sq ft", fields["unit"])
    }

    @Test
    fun `editing only the rate on a per-m product leaves its unit untouched`() {
        // The amendment that saved the catalogue: 34 products are `per m`,
        // and a toggle-derived unit would have written `each` over every one
        // of them on the first rate correction.
        val perMetre = record.copy(unit = "per m")
        val openedOn = ProductWrite.draftOf(perMetre)
        val fields = written(
            plan(
                draft = openedOn.copy(dealer = "450"),
                stored = stored("unit" to "per m"),
                on = perMetre,
                loadedAs = openedOn
            )
        )
        assertEquals("per m", fields["unit"])
        assertEquals(450.0, fields["dealer"])
    }

    @Test
    fun `an untouched field is taken from the fresh read, not from the open sheet`() {
        // The stale-sheet case in general: only what the person actually
        // changed is theirs, and everything else is whatever is stored now.
        val fields = written(
            plan(
                draft = loaded.copy(client = "27000"),
                stored = stored("name" to "Sliding gate motor 1000 kg (heavy duty)")
            )
        )
        assertEquals("Sliding gate motor 1000 kg (heavy duty)", fields["name"])
        assertEquals(27000.0, fields["client"])
    }

    // --- the contractor tier ---------------------------------------------------------

    @Test
    fun `a stored null contractor is written back as null, never dropped`() {
        val fields = written(plan(loaded.copy(unit = "per sq ft")))
        assertTrue("the key must be present", fields.containsKey("contractor"))
        assertNull(fields["contractor"])
    }

    @Test
    fun `a stored contractor rate is carried through unchanged and is not editable`() {
        val fields = written(
            plan(draft = loaded.copy(dealer = "19000"), stored = stored("contractor" to 22200.0))
        )
        assertEquals(22200.0, fields["contractor"])
    }

    @Test
    fun `an absent contractor key becomes null, because the rule refuses an absent one`() {
        // Settled by the emulator, not by argument: an absent key is an
        // evaluation error and the allow does not grant.
        val withoutKey = stored().toMutableMap().apply { remove("contractor") }
        val fields = written(plan(draft = loaded.copy(unit = "per sq ft"), stored = withoutKey))
        assertTrue(fields.containsKey("contractor"))
        assertNull(fields["contractor"])
    }

    @Test
    fun `and so does the PWA's unset marker, which only the reader understands`() {
        val fields = written(
            plan(draft = loaded.copy(unit = "per sq ft"), stored = stored("contractor" to "∅"))
        )
        assertNull(fields["contractor"])
    }

    // --- repairing what an older PWA left behind -------------------------------------

    @Test
    fun `a gst stored as a string is repaired to a number`() {
        val fields = written(
            plan(draft = loaded.copy(name = "Sliding gate motor"), stored = stored("gst" to "18"))
        )
        assertEquals(18.0, fields["gst"])
    }

    @Test
    fun `an active of 1 is repaired to a boolean`() {
        val fields = written(
            plan(draft = loaded.copy(name = "Sliding gate motor"), stored = stored("active" to 1))
        )
        assertEquals(true, fields["active"])
    }

    @Test
    fun `a price stored as a formatted string is repaired to its number`() {
        val fields = written(
            plan(draft = loaded.copy(name = "Wheel"), stored = stored("dealer" to "1,250.50"))
        )
        assertEquals(1250.5, fields["dealer"])
    }

    @Test
    fun `a missing seedModel is derived and written, which is what repairs it`() {
        // V8C4's `applyProductDoc` keys on `v.seedModel || v.model`, so after
        // this write the two apps agree — the write fixes the ambiguity rather
        // than inheriting it.
        val legacy = stored().toMutableMap().apply { remove("seedModel") }
        val fields = written(plan(draft = loaded.copy(name = "Motor"), stored = legacy))
        assertEquals("SIE1000", fields["seedModel"])
    }

    @Test
    fun `a stored price that is not a number at all is refused rather than wiped`() {
        // Writing it back as "not set" would delete a rate nobody asked to
        // change, so this is a refusal and not a repair.
        val result = plan(draft = loaded.copy(name = "Motor"), stored = stored("client" to "call us"))
        assertEquals(
            ProductWrite.storedPriceUnreadable("client"),
            (result as ProductPlan.Refused).message
        )
    }

    // --- which document the write lands on --------------------------------------------

    @Test
    fun `the write goes to V8C4's document id, not to the one it was read from`() {
        // A product seeded at the pipe id still shows in this app, but the PWA
        // reads only `group__model`. Writing back to the pipe document would
        // leave the PWA on a stale rate.
        val fromLegacy = record.copy(documentId = "gateMotors|SIE1000")
        assertEquals(
            "gateMotors__SIE1000",
            docIdOf(plan(draft = loaded.copy(dealer = "19000"), on = fromLegacy))
        )
    }

    @Test
    fun `a model carrying a slash keeps it, and its document id replaces it`() {
        val wheel = record.copy(
            documentId = "hwWheel__SIEBAL58H_V", key = "hwWheel|SIEBAL58H/V",
            group = "hwWheel", seedModel = "SIEBAL58H/V", model = "SIEBAL58H/V"
        )
        val openedOn = ProductWrite.draftOf(wheel)
        val result = plan(
            draft = openedOn.copy(dealer = "1300"),
            stored = mapOf(
                "id" to "hwWheel|SIEBAL58H/V", "group" to "hwWheel",
                "seedModel" to "SIEBAL58H/V", "model" to "SIEBAL58H/V",
                "name" to "Wheel", "gst" to 18.0, "active" to true
            ),
            on = wheel, loadedAs = openedOn
        )
        assertEquals("SIEBAL58H/V", written(result)["seedModel"])
        assertEquals("hwWheel|SIEBAL58H/V", written(result)["key"])
        assertEquals("hwWheel__SIEBAL58H_V", docIdOf(result))
    }

    @Test
    fun `the seed model is read from the id field, which loses nothing`() {
        // The `id`/`key` field is `group|model` and is never sanitised, so it
        // is preferred over the document id, which is.
        val wheel = record.copy(
            documentId = "hwWheel__SIEBAL58H_V", key = "hwWheel|SIEBAL58H/V",
            group = "hwWheel", seedModel = "", model = "SIEBAL58H/V"
        )
        val openedOn = ProductWrite.draftOf(wheel)
        val fields = written(
            plan(
                draft = openedOn.copy(dealer = "1300"),
                stored = mapOf(
                    "id" to "hwWheel|SIEBAL58H/V", "group" to "hwWheel",
                    "model" to "SIEBAL58H/V", "name" to "Wheel",
                    "gst" to 18.0, "active" to true
                ),
                on = wheel, loadedAs = openedOn
            )
        )
        assertEquals("SIEBAL58H/V", fields["seedModel"])
    }

    @Test
    fun `an overridden model never becomes the seed model`() {
        // V8C4 writes `seedModel` from the seed but `model` from
        // `o.md || it.m`, so `model` is the display override where one is
        // set. Deriving the seed model from it would compute a different
        // document id and write to a second document — the duplicate the
        // canonical id exists to prevent.
        val renamed = record.copy(seedModel = "", model = "SIE-1000 HEAVY")
        val openedOn = ProductWrite.draftOf(renamed)
        val result = plan(
            draft = openedOn.copy(dealer = "19000"),
            stored = mapOf(
                "id" to "gateMotors|SIE1000", "group" to "gateMotors",
                "model" to "SIE-1000 HEAVY", "name" to "Motor",
                "gst" to 18.0, "active" to true
            ),
            on = renamed, loadedAs = openedOn
        )
        assertEquals("SIE1000", written(result)["seedModel"])
        assertEquals("SIE-1000 HEAVY", written(result)["model"])
        assertEquals("gateMotors__SIE1000", docIdOf(result))
    }

    @Test
    fun `a seed model readable only from a sanitised document id is refused`() {
        // `gate__SIE2_5MSMALL` could be `SIE2.5MSMALL` or `SIE2_5MSMALL`, and
        // both a `.` and a `/` occur in real models. A wrong seed model makes
        // the PWA materialise a new custom item instead of matching the seed,
        // so this is a refusal rather than a guess.
        val opaque = record.copy(
            documentId = "gate__SIE2_5MSMALL", key = "", group = "gate", seedModel = "", model = ""
        )
        val openedOn = ProductWrite.draftOf(opaque)
        val result = plan(
            draft = openedOn.copy(name = "Motor", dealer = "1000"),
            stored = mapOf("group" to "gate", "name" to "Motor", "gst" to 18.0, "active" to true),
            on = opaque, loadedAs = openedOn
        )
        assertEquals(ProductWrite.SEED_MODEL_AMBIGUOUS, (result as ProductPlan.Refused).message)
    }

    @Test
    fun `fields the rule does not require are carried across to a new canonical document`() {
        // Merge preserves them on a document that already exists, but a
        // canonical document materialised from a legacy one has nothing to
        // preserve — and losing `categoryId` moves the product to the Other
        // shelf.
        val fields = written(
            plan(
                draft = loaded.copy(dealer = "19000"),
                stored = stored("spec" to "1000 kg, 230 V", "kg" to 1000.0)
            )
        )
        assertEquals("cat-sliding", fields["categoryId"])
        assertEquals("1000 kg, 230 V", fields["spec"])
        assertEquals(1000.0, fields["kg"])
    }

    @Test
    fun `schemaVersion is never written`() {
        // Merge keeps it where it exists. Writing 2 onto an unmigrated
        // document would claim a migration that has not run.
        assertTrue("schemaVersion" !in written(plan(loaded.copy(dealer = "19000"))))
    }

    // --- the minimum chargeable area ---------------------------------------------------

    @Test
    fun `a minimum area is written as a number, and a blank one as null`() {
        assertEquals(10.0, written(plan(loaded.copy(minSqft = "10")))["minSqft"])
        val cleared = plan(
            draft = loaded.copy(minSqft = ""),
            stored = stored("minSqft" to 10.0),
            loadedAs = loaded.copy(minSqft = "10")
        )
        assertNull(written(cleared)["minSqft"])
    }

    // --- refusals -----------------------------------------------------------------------

    @Test
    fun `a product with no name is refused`() {
        assertEquals(
            ProductWrite.NAME_REQUIRED,
            (plan(loaded.copy(name = " ")) as ProductPlan.Refused).message
        )
    }

    @Test
    fun `a price that is not a number is refused, and a blank one is not`() {
        assertEquals(
            ProductWrite.PRICE_NOT_A_NUMBER,
            (plan(loaded.copy(dealer = "call us")) as ProductPlan.Refused).message
        )
        assertEquals(
            ProductWrite.PRICE_NOT_A_NUMBER,
            (plan(loaded.copy(client = "-1")) as ProductPlan.Refused).message
        )
        // Blank is "Price not set" and is a legitimate state, not an error.
        assertNull(written(plan(loaded.copy(dealer = "")))["dealer"])
    }

    @Test
    fun `a gst outside the Indian slabs is refused`() {
        assertEquals(
            ProductWrite.GST_OUT_OF_RANGE,
            (plan(loaded.copy(gst = "29")) as ProductPlan.Refused).message
        )
        assertEquals(
            ProductWrite.GST_NOT_A_NUMBER,
            (plan(loaded.copy(gst = "eighteen")) as ProductPlan.Refused).message
        )
        assertEquals(28.0, written(plan(loaded.copy(gst = "28")))["gst"])
    }

    @Test
    fun `a negative minimum area is refused`() {
        assertEquals(
            ProductWrite.MIN_SQFT_NEGATIVE,
            (plan(loaded.copy(minSqft = "-1")) as ProductPlan.Refused).message
        )
    }

    @Test
    fun `a Manager is refused before anything is built`() {
        assertEquals(
            ProductWrite.CANNOT_EDIT,
            (plan(loaded.copy(dealer = "19000"), canEdit = false) as ProductPlan.Refused).message
        )
    }

    @Test
    fun `an unchanged sheet writes nothing at all`() {
        assertEquals(ProductPlan.NoChange, plan(loaded))
    }

    // --- the draft the sheet opens with --------------------------------------------------

    @Test
    fun `a price that is not set opens as a blank box, never as zero`() {
        val draft = ProductWrite.draftOf(record.copy(dealer = null, client = null))
        assertEquals("", draft.dealer)
        assertEquals("", draft.client)
    }

    @Test
    fun `a number opens ungrouped, so the person's own value parses back`() {
        // `Money.formatQuantity` would give `1,23,456.5`, which does not parse
        // and would refuse somebody their own unedited figure.
        assertEquals("123456.5", ProductWrite.boxText(123456.5))
        assertEquals("18", ProductWrite.boxText(18.0))
        assertEquals("1000", ProductWrite.boxText(1000.0))
    }
}
