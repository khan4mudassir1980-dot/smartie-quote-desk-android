package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.model.PartyRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Writing a customer, and the two save semantics that must never be confused.
 *
 * **[PartyWrite.edit] is a replace.** The editor shows every field and stores
 * exactly what is on screen, so emptying a box takes that detail off the
 * customer. That is the only way a GSTIN entered against the wrong firm ever
 * comes off it.
 *
 * **[PartyWrite.mergeInto] is a fill.** The quotation side's "Save this
 * customer" fills gaps and takes genuine changes and **never blanks**, because
 * there the person was quoting rather than editing, and a box they left empty
 * is silence rather than an instruction.
 *
 * The role rules come from the deployed `/customers` block: a Manager may
 * correct details, may not rename, and may neither archive nor unarchive.
 * Stored `staff` is displayed **Manager**.
 */
class PartyWriteTest {

    private val author = PartyAuthor(name = "Asha", uid = "uid_admin")

    private val stored = PartyRecord(
        id = "c_1",
        name = "Sunrise Constructions",
        type = "contractor",
        city = "Mumbai",
        gstin = "27AAACS1234F1Z5",
        contact = "Mr Deshmukh",
        phone = "9876543210",
        email = "accounts@sunrise.invalid",
        address = "Plot 14, Andheri East",
        notes = "Pays in 30 days"
    )

    private fun written(plan: PartyPlan): Map<String, Any?> =
        (plan as PartyPlan.Write).data

    // --- creating ----------------------------------------------------------------

    @Test
    fun `a new party carries every field V8C4 keeps, and no parallel one`() {
        val plan = PartyWrite.create(
            id = "c_new",
            draft = PartyDraft(name = "Metro Glass", city = "Mumbai", type = "dealer"),
            author = author,
            at = 1_000L
        )
        val data = written(plan)

        assertEquals("c_new", data["id"])
        assertEquals("Metro Glass", data["name"])
        assertEquals("dealer", data["type"])
        assertEquals("Mumbai", data["city"])
        assertEquals(1_000L, data["t"])
        assertEquals("Asha", data["by"])
        assertEquals("uid_admin", data["byUid"])
        assertEquals(1_000L, data["updated"])
        assertEquals("Asha", data["upBy"])
        assertEquals("uid_admin", data["upUid"])
        assertEquals(false, data["archived"])

        // `city`, never `site`. A quotation's party snapshot has a site; a
        // customer record does not, and inventing one gives the PWA a field
        // it never reads.
        assertTrue("no invented field", !data.containsKey("site"))
        assertEquals(
            setOf(
                "id", "name", "type", "city", "gstin", "contact", "phone", "email",
                "address", "notes", "archived", "t", "by", "byUid", "updated", "upBy", "upUid"
            ),
            data.keys
        )
    }

    @Test
    fun `a party with no name is refused before anything is sent`() {
        val plan = PartyWrite.create("c_new", PartyDraft(name = "   "), author, 1_000L)
        assertEquals(PartyWrite.NAME_REQUIRED, (plan as PartyPlan.Refused).message)
    }

    @Test
    fun `an unknown type falls back to client, as V8C4 does`() {
        val plan = PartyWrite.create("c_new", PartyDraft(name = "X", type = "reseller"), author, 1L)
        assertEquals("client", written(plan)["type"])
        assertEquals("client", PartyWrite.normaliseType(""))
        assertEquals("contractor", PartyWrite.normaliseType(" Contractor "))
    }

    @Test
    fun `writing onto an id that is already taken is refused, not merged`() {
        // A `set` on a taken id is evaluated by the rules as an update, which
        // any non-Worker may make — so a collision would quietly overwrite
        // somebody else's customer.
        val plan = PartyWrite.create("c_1", PartyDraft(name = "X"), author, 1L, alreadyExists = true)
        assertEquals(PartyWrite.ALREADY_EXISTS, (plan as PartyPlan.Refused).message)
    }

    // --- editing: a replace ----------------------------------------------------------

    @Test
    fun `an Owner clearing a field really clears it`() {
        // The whole point of the editor. A GSTIN entered against the wrong
        // firm has to be removable.
        val draft = PartyWrite.draftOf(stored).copy(gstin = "", notes = "")
        val plan = PartyWrite.edit(stored, draft, author, 2_000L, canRename = true, canArchive = true)
        val data = written(plan)

        assertEquals("", data["gstin"])
        assertEquals("", data["notes"])
        // And the details that were left alone are still sent, unchanged.
        assertEquals("Mr Deshmukh", data["contact"])
        assertEquals("c_1", data["id"])
        assertEquals(2_000L, data["updated"])
        assertEquals("uid_admin", data["upUid"])
    }

    @Test
    fun `an edit never rewrites who created the customer`() {
        val draft = PartyWrite.draftOf(stored).copy(city = "Pune")
        val data = written(
            PartyWrite.edit(stored, draft, author, 2_000L, canRename = true, canArchive = true)
        )

        listOf("t", "by", "byUid").forEach {
            assertTrue("$it must not be in an edit", !data.containsKey(it))
        }
    }

    @Test
    fun `saving an unchanged party writes nothing at all`() {
        val plan = PartyWrite.edit(
            stored, PartyWrite.draftOf(stored), author, 2_000L, canRename = true, canArchive = true
        )
        assertEquals(PartyPlan.NoChange, plan)
    }

    // --- editing: what a Manager may not do ---------------------------------------------

    @Test
    fun `a Manager correcting details sends no name at all`() {
        // The rule requires `data.name == resource.data.name`, and a merged
        // update that omits the key keeps the stored value — which is exactly
        // what satisfies it.
        val draft = PartyWrite.draftOf(stored).copy(contact = "Mrs Deshmukh")
        val data = written(
            PartyWrite.edit(stored, draft, author, 2_000L, canRename = false, canArchive = false)
        )

        assertEquals("Mrs Deshmukh", data["contact"])
        assertTrue("no name key", !data.containsKey("name"))
        assertTrue("no archived key", !data.containsKey("archived"))
    }

    @Test
    fun `a Manager who somehow submits a rename is refused here, not by the server`() {
        val draft = PartyWrite.draftOf(stored).copy(name = "Renamed Ltd")
        val plan = PartyWrite.edit(stored, draft, author, 2_000L, canRename = false, canArchive = false)

        assertEquals(PartyWrite.CANNOT_RENAME, (plan as PartyPlan.Refused).message)
    }

    @Test
    fun `and cannot touch an archived party at all`() {
        // The staff branch requires the party was not archived to begin with,
        // so there is no correction to attempt — a sentence beats a permission
        // error nobody can act on.
        val archived = stored.copy(archived = true)
        val draft = PartyWrite.draftOf(archived).copy(city = "Pune")
        val plan = PartyWrite.edit(archived, draft, author, 2_000L, canRename = false, canArchive = false)

        assertEquals(PartyWrite.ARCHIVED_IS_READ_ONLY, (plan as PartyPlan.Refused).message)
    }

    @Test
    fun `archiving is an Owner's and an Administrator's`() {
        assertEquals(
            PartyWrite.CANNOT_ARCHIVE,
            (PartyWrite.setArchived(stored, true, author, 3_000L, canArchive = false) as PartyPlan.Refused).message
        )

        val data = written(PartyWrite.setArchived(stored, true, author, 3_000L, canArchive = true))
        assertEquals(true, data["archived"])
        assertEquals("c_1", data["id"])
        // And bringing one back is the same write the other way.
        val back = written(
            PartyWrite.setArchived(stored.copy(archived = true), false, author, 3_000L, canArchive = true)
        )
        assertEquals(false, back["archived"])
    }

    @Test
    fun `archiving one that already is changes nothing`() {
        val plan = PartyWrite.setArchived(stored.copy(archived = true), true, author, 3_000L, true)
        assertEquals(PartyPlan.NoChange, plan)
    }

    // --- the other semantics: a fill ------------------------------------------------------

    @Test
    fun `a type nobody chose does not turn a contractor into a client`() {
        // The fallback belongs to the writer, not to the draft. A default of
        // `client` on `PartyDraft` made "nobody picked one" and "somebody
        // picked Client" the same value, so the quotation side's first
        // "Save this customer" silently demoted every contractor — the exact
        // thing `mergeInto` promises never to do.
        assertEquals("a draft states nothing until somebody types", "", PartyDraft().type)

        // `create` still answers V8C4's fallback for an unstated type...
        assertEquals(
            "client",
            written(PartyWrite.create("c_new", PartyDraft(name = "X"), author, 1L))["type"]
        )
        // ...while a merge of the same silence leaves the stored one alone.
        assertEquals(
            PartyPlan.NoChange,
            PartyWrite.mergeInto(stored, PartyDraft(name = stored.name), author, 4_000L)
        )

        // A type the person did choose is taken, and canonicalised on the way.
        assertEquals(
            "dealer",
            written(
                PartyWrite.mergeInto(
                    stored, PartyDraft(name = stored.name, type = " Dealer "), author, 4_000L
                )
            )["type"]
        )
    }

    @Test
    fun `merge fills a gap the stored party has`() {
        val bare = stored.copy(email = "", notes = "")
        val draft = PartyDraft(name = stored.name, email = "new@sunrise.invalid")
        val data = written(PartyWrite.mergeInto(bare, draft, author, 4_000L))

        assertEquals("new@sunrise.invalid", data["email"])
    }

    @Test
    fun `merge takes a genuine change`() {
        val draft = PartyDraft(name = stored.name, phone = "9820000000")
        val data = written(PartyWrite.mergeInto(stored, draft, author, 4_000L))
        assertEquals("9820000000", data["phone"])
    }

    @Test
    fun `but merge never blanks a detail already held`() {
        // The one line that separates the two semantics. Everything the
        // quotation form left empty is silence, not an instruction.
        val draft = PartyDraft(name = stored.name)
        val plan = PartyWrite.mergeInto(stored, draft, author, 4_000L)

        assertEquals("nothing to change, so nothing is written", PartyPlan.NoChange, plan)
    }

    @Test
    fun `and the two disagree on exactly that, which is the point`() {
        val emptying = PartyWrite.draftOf(stored).copy(gstin = "")

        val replaced = written(
            PartyWrite.edit(stored, emptying, author, 5_000L, canRename = true, canArchive = true)
        )
        assertEquals("the editor clears it", "", replaced["gstin"])

        val filled = PartyWrite.mergeInto(stored, emptying, author, 5_000L)
        assertNull(
            "the quotation side leaves it alone",
            (filled as? PartyPlan.Write)?.data?.get("gstin")
        )
    }
}
