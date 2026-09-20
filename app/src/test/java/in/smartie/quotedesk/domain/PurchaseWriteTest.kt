package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.model.PurchaseRecord
import `in`.smartie.quotedesk.data.model.UrgencyV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a purchase write comes to, off device.
 *
 * Most of these are about the five ways the v9 rules refuse a write without
 * saying so — a merged post-state that has to carry `id` and a numeric `qty`,
 * a `del` key that must never appear on an update, an `updated` that must be a
 * number rather than the server's sentinel, and a `rev` that only guards
 * anything if it is always sent. Each was found by reading the rules against
 * `fixtures/purchase.json`, and each is pinned here so it cannot come back.
 */
class PurchaseWriteTest {

    private val author = PurchaseAuthor(name = "Asha", uid = "uid_admin")
    private val at = 1_700_000_000_000L

    private fun stored(
        quantity: Double = 6.0,
        urgency: UrgencyV2 = UrgencyV2.URGENT,
        name: String = "Sliding gate rack",
        note: String = "For the Kandivali site",
        status: String = "Needed",
        received: Boolean = false,
        receivedBy: String = "",
        deleted: Boolean = false,
        revision: Int = 1
    ) = PurchaseRecord(
        id = "pr_one",
        name = name,
        quantity = quantity,
        urgency = urgency,
        note = note,
        status = status,
        by = "Ravi",
        byUid = "uid_worker",
        createdAt = 1_690_000_000_000L,
        updatedAt = 1_690_000_000_000L,
        received = received,
        receivedBy = receivedBy,
        deleted = deleted,
        revision = revision
    )

    private val draft = PurchaseDraft(
        name = "  Sliding gate rack 1 m  ",
        quantity = 6.0,
        urgency = UrgencyV2.URGENT,
        note = "  For the Kandivali site  "
    )

    private fun written(plan: PurchasePlan): Map<String, Any?> =
        (plan as PurchasePlan.Write).data

    /** Every operation that writes an update, so a rule can be asserted on all of them. */
    private fun everyUpdate(record: PurchaseRecord = stored()): Map<String, Map<String, Any?>> = mapOf(
        "edit" to written(
            PurchaseWrite.edit(record, "Something else", 9.0, UrgencyV2.CRITICAL, "", author, at)
        ),
        "setUrgency" to written(PurchaseWrite.setUrgency(record, UrgencyV2.CRITICAL, author, at)),
        "markReceived" to written(PurchaseWrite.markReceived(record, 6.0, author, at)),
        "reopen" to written(
            PurchaseWrite.reopen(record.copy(received = true, status = "Received"), author, at)
        ),
        "softDelete" to written(PurchaseWrite.softDelete(record, author, at))
    )

    // --- creating -------------------------------------------------------------

    @Test
    fun `a new requirement matches the create rule field for field`() {
        val data = written(PurchaseWrite.create("pr_new", draft, author, at))

        assertEquals("pr_new", data["id"])
        assertEquals("Sliding gate rack 1 m", data["name"])
        assertEquals(6.0, data["qty"])
        assertEquals("urgent", data["urgency"])
        assertEquals(PurchaseWrite.STATUS_NEEDED, data["status"])
        assertEquals("For the Kandivali site", data["note"])
        assertEquals("Asha", data["by"])
        assertEquals("uid_admin", data["byUid"])
        assertEquals(at, data["t"])
        assertEquals(at, data["updated"])
        assertEquals(1, data["rev"])
        assertEquals(false, data["received"])
        assertEquals(false, data["del"])
        assertSame(ServerTimestamp, data["serverAt"])
    }

    @Test
    fun `a new requirement is written whole, not merged onto whatever is there`() {
        val plan = PurchaseWrite.create("pr_new", draft, author, at) as PurchasePlan.Write
        assertEquals("pr_new", plan.docId)
        assertFalse(plan.merge)
    }

    @Test
    fun `the urgency on the wire is the PWA's value, never the words on screen`() {
        // The rules accept only these three, and the label is display text.
        for (urgency in UrgencyV2.entries) {
            val data = written(
                PurchaseWrite.create("pr_new", draft.copy(urgency = urgency), author, at)
            )
            assertEquals(urgency.wireValue, data["urgency"])
            assertTrue(data["urgency"] in setOf<Any?>("critical", "urgent", "normal"))
        }
    }

    @Test
    fun `a requirement with nothing typed in it is refused`() {
        assertEquals(
            PurchasePlan.Refused(PurchaseWrite.NO_NAME),
            PurchaseWrite.create("pr_new", draft.copy(name = "   "), author, at)
        )
    }

    @Test
    fun `a quantity of zero or less is refused rather than written`() {
        // `qty is number && qty > 0` — the rules refuse it, so the app says so.
        for (quantity in listOf(0.0, -1.0)) {
            assertEquals(
                PurchasePlan.Refused(PurchaseWrite.NOT_POSITIVE),
                PurchaseWrite.create("pr_new", draft.copy(quantity = quantity), author, at)
            )
        }
    }

    @Test
    fun `a colliding id is refused rather than quietly overwriting somebody else`() {
        // The rules evaluate a `set` on an existing id as an update, which a
        // Manager may perform — so a collision would not fail on its own.
        assertEquals(
            PurchasePlan.Refused(PurchaseWrite.ALREADY_EXISTS),
            PurchaseWrite.create("pr_new", draft, author, at, alreadyExists = true)
        )
    }

    @Test
    fun `the stock key is written only when a requirement came from a row`() {
        assertFalse(written(PurchaseWrite.create("pr_new", draft, author, at)).containsKey("key"))

        val fromStock = draft.copy(key = "gateMotors|SIE1000")
        assertEquals(
            "gateMotors|SIE1000",
            written(PurchaseWrite.create("pr_new", fromStock, author, at))["key"]
        )
    }

    // --- what every update must carry -----------------------------------------

    @Test
    fun `every update re-asserts the id and a numeric quantity`() {
        // Both are checked against the MERGED post-state, and a V8C4 row may
        // hold neither usably: `pr_received_legacy` stores qty as the string
        // "10", and a row with no `id` field at all exists.
        for ((name, data) in everyUpdate()) {
            assertEquals("$name must re-assert the id", "pr_one", data["id"])
            assertTrue("$name must write qty as a number", data["qty"] is Double)
            assertTrue("$name must write a positive qty", (data["qty"] as Double) > 0.0)
        }
    }

    @Test
    fun `every update advances the revision by exactly one`() {
        // revOk() tolerates a missing rev, so the guard against two devices
        // completing the same requirement only exists because we always send it.
        for ((name, data) in everyUpdate(stored(revision = 4))) {
            assertEquals("$name must advance rev", 5, data["rev"])
        }
    }

    @Test
    fun `every update stamps a number, not the server's sentinel`() {
        // `updated is number`; FieldValue.serverTimestamp() is not one.
        for ((name, data) in everyUpdate()) {
            assertEquals("$name must write epoch millis", at, data["updated"])
            assertTrue("$name must not put the marker in updated", data["updated"] is Long)
            assertSame("$name should still record the server clock", ServerTimestamp, data["serverAt"])
        }
    }

    @Test
    fun `no update but a soft delete carries a del key`() {
        // `touched()` reports keys ADDED, so writing del:false onto a row that
        // has no del field trips the guard reserving soft delete for an
        // Administrator — and refuses an ordinary Manager's save.
        for ((name, data) in everyUpdate()) {
            if (name == "softDelete") {
                assertEquals(true, data["del"])
            } else {
                assertFalse("$name must not mention del", data.containsKey("del"))
            }
        }
    }

    @Test
    fun `every update records who made it`() {
        for ((name, data) in everyUpdate()) {
            assertEquals("$name must record the author", "Asha", data["upBy"])
            assertEquals("$name must record the uid", "uid_admin", data["upUid"])
        }
    }

    // --- editing ---------------------------------------------------------------

    @Test
    fun `an edit that changes nothing writes nothing`() {
        val record = stored()
        assertEquals(
            PurchasePlan.NoChange,
            PurchaseWrite.edit(record, record.name, record.quantity, record.urgency, record.note, author, at)
        )
    }

    @Test
    fun `an edit saves the name, the quantity, the urgency and the note`() {
        val data = written(
            PurchaseWrite.edit(stored(), "  Remote handsets  ", 12.0, UrgencyV2.CRITICAL, "  Two spare  ", author, at)
        )
        assertEquals("Remote handsets", data["name"])
        assertEquals(12.0, data["qty"])
        assertEquals("critical", data["urgency"])
        assertEquals("Two spare", data["note"])
    }

    @Test
    fun `an edit refuses a blank name or a quantity of zero`() {
        assertEquals(
            PurchasePlan.Refused(PurchaseWrite.NO_NAME),
            PurchaseWrite.edit(stored(), " ", 3.0, UrgencyV2.NORMAL, "", author, at)
        )
        assertEquals(
            PurchasePlan.Refused(PurchaseWrite.NOT_POSITIVE),
            PurchaseWrite.edit(stored(), "Remote handsets", 0.0, UrgencyV2.NORMAL, "", author, at)
        )
    }

    // --- the unusable-quantity rescue ------------------------------------------

    @Test
    fun `a requirement with no usable quantity refuses every path but edit`() {
        // The rules refuse every update to it until qty is a number, so the
        // app says which one it is rather than surfacing a permission error.
        val broken = stored(quantity = 0.0)
        val refusal = PurchasePlan.Refused(PurchaseWrite.UNUSABLE_QUANTITY)

        assertEquals(refusal, PurchaseWrite.setUrgency(broken, UrgencyV2.CRITICAL, author, at))
        assertEquals(refusal, PurchaseWrite.markReceived(broken, 3.0, author, at))
        assertEquals(refusal, PurchaseWrite.softDelete(broken, author, at))
        assertEquals(
            refusal,
            PurchaseWrite.reopen(broken.copy(received = true, status = "Received"), author, at)
        )
    }

    @Test
    fun `edit is the way out, and writes a quantity the rules will accept`() {
        val data = written(
            PurchaseWrite.edit(stored(quantity = 0.0), "Remote handsets", 10.0, UrgencyV2.NORMAL, "", author, at)
        )
        assertEquals(10.0, data["qty"])
        assertTrue(data["qty"] is Double)
    }

    // --- receiving --------------------------------------------------------------

    @Test
    fun `receiving closes the requirement and records who, how many and when`() {
        val data = written(PurchaseWrite.markReceived(stored(), 5.0, author, at))

        assertEquals(PurchaseWrite.STATUS_RECEIVED, data["status"])
        assertEquals(true, data["received"])
        assertEquals(5.0, data["rcvQty"])
        assertEquals("Asha", data["rcvBy"])
        assertEquals("uid_admin", data["rcvUid"])
        assertEquals(at, data["rcvAt"])
    }

    @Test
    fun `a second device is told who received it, not handed a permission error`() {
        val already = stored(received = true, status = "Received", receivedBy = "Omar")
        assertEquals(
            PurchasePlan.Refused("Already received by Omar"),
            PurchaseWrite.markReceived(already, 5.0, author, at)
        )
    }

    @Test
    fun `a received quantity of zero or less is refused`() {
        assertEquals(
            PurchasePlan.Refused(PurchaseWrite.NOT_POSITIVE),
            PurchaseWrite.markReceived(stored(), 0.0, author, at)
        )
    }

    // --- reopening ----------------------------------------------------------------

    @Test
    fun `reopening removes the received fields rather than blanking them`() {
        // A null would be dropped by the store and a zero would read back as
        // "0 in" on the card, so the four fields are deleted outright.
        val data = written(
            PurchaseWrite.reopen(stored(received = true, status = "Received"), author, at)
        )

        assertEquals(PurchaseWrite.STATUS_NEEDED, data["status"])
        assertEquals(false, data["received"])
        for (field in listOf("rcvQty", "rcvBy", "rcvUid", "rcvAt")) {
            assertSame("$field must be deleted, not blanked", DeleteField, data[field])
        }
    }

    @Test
    fun `reopening something already open writes nothing`() {
        assertEquals(PurchasePlan.NoChange, PurchaseWrite.reopen(stored(), author, at))
    }

    // --- removing -------------------------------------------------------------------

    @Test
    fun `a soft delete marks the row and names who did it`() {
        val data = written(PurchaseWrite.softDelete(stored(), author, at))
        assertEquals(true, data["del"])
        assertEquals("uid_admin", data["deletedBy"])
    }

    @Test
    fun `removing something already removed writes nothing`() {
        assertEquals(
            PurchasePlan.NoChange,
            PurchaseWrite.softDelete(stored(deleted = true), author, at)
        )
    }

    @Test
    fun `nothing else touches a removed requirement`() {
        val gone = stored(deleted = true)
        val refusal = PurchasePlan.Refused(PurchaseWrite.ALREADY_DELETED)

        assertEquals(refusal, PurchaseWrite.edit(gone, "Anything", 3.0, UrgencyV2.NORMAL, "", author, at))
        assertEquals(refusal, PurchaseWrite.setUrgency(gone, UrgencyV2.CRITICAL, author, at))
        assertEquals(refusal, PurchaseWrite.markReceived(gone, 3.0, author, at))
        assertEquals(refusal, PurchaseWrite.reopen(gone, author, at))
    }

    // --- what is deliberately not here ------------------------------------------------

    @Test
    fun `a status is only ever moved by the operation that owns it`() {
        // There is no setStatus and no cancel: `Received` comes from
        // markReceived and `Needed` from reopen, so no caller can invent a
        // state nobody designed. A PWA-written `Cancelled` still reads fine.
        val received = written(PurchaseWrite.markReceived(stored(), 5.0, author, at))
        val reopened = written(
            PurchaseWrite.reopen(stored(received = true, status = "Received"), author, at)
        )
        assertEquals(PurchaseWrite.STATUS_RECEIVED, received["status"])
        assertEquals(PurchaseWrite.STATUS_NEEDED, reopened["status"])

        for ((name, data) in everyUpdate()) {
            if (name !in listOf("markReceived", "reopen")) {
                assertFalse("$name must not move the status", data.containsKey("status"))
            }
        }
    }
}
