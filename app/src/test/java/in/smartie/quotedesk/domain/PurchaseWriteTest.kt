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
        receivedQuantity: Double? = null,
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
        receivedQuantity = receivedQuantity,
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
    fun `the total needed cannot be edited below what has already arrived`() {
        val partly = stored(quantity = 10.0, receivedQuantity = 5.0)
        assertEquals(
            PurchasePlan.Refused(PurchaseWrite.belowReceived(5.0)),
            PurchaseWrite.edit(partly, partly.name, 4.0, partly.urgency, partly.note, author, at)
        )
    }

    @Test
    fun `setting the total to what has arrived finishes the requirement`() {
        // Ten were wanted, five came, and the rest is not coming. Correcting
        // the total to five is how somebody says so, and leaving it open
        // afterwards would be a list nobody can ever clear.
        val partly = stored(quantity = 10.0, receivedQuantity = 5.0)
        val data = written(
            PurchaseWrite.edit(partly, partly.name, 5.0, partly.urgency, partly.note, author, at)
        )

        assertEquals(5.0, data["qty"])
        assertEquals(PurchaseWrite.STATUS_RECEIVED, data["status"])
        assertEquals(true, data["received"])
        assertEquals("squared off against the new total", 5.0, data["rcvQty"])
        // Who received the delivery is not who edited the total, and the
        // receipt's own author and time are not rewritten by an edit.
        assertFalse(data.containsKey("rcvBy"))
        assertFalse(data.containsKey("rcvAt"))
        assertEquals("Asha", data["upBy"])
    }

    @Test
    fun `raising the total above what has arrived keeps the requirement open`() {
        val partly = stored(quantity = 10.0, receivedQuantity = 5.0)
        val data = written(
            PurchaseWrite.edit(partly, partly.name, 12.0, partly.urgency, partly.note, author, at)
        )

        assertEquals(12.0, data["qty"])
        assertFalse("nothing here closes it", data.containsKey("received"))
        assertFalse(data.containsKey("status"))
    }

    @Test
    fun `an edit that changes nothing still closes a row whose total is already in`() {
        // A PWA receive can leave `rcvQty == qty` with the row still open.
        // `NoChange` there would be a requirement nobody could ever finish.
        val stuck = stored(quantity = 6.0, receivedQuantity = 6.0)
        val data = written(
            PurchaseWrite.edit(stuck, stuck.name, 6.0, stuck.urgency, stuck.note, author, at)
        )

        assertEquals(true, data["received"])
        assertEquals(PurchaseWrite.STATUS_RECEIVED, data["status"])
    }

    @Test
    fun `an edit to a closed requirement is not turned into a receipt`() {
        // `receivedTotal` is read on a closed row too, and the finishing
        // branch must not fire on one that is already finished.
        val done = stored(
            quantity = 10.0,
            receivedQuantity = 10.0,
            received = true,
            status = "Received"
        )
        val data = written(
            PurchaseWrite.edit(done, "A different name", 10.0, done.urgency, done.note, author, at)
        )

        assertEquals("A different name", data["name"])
        assertFalse("the row was already closed; nothing re-closes it", data.containsKey("received"))
        assertFalse(data.containsKey("rcvQty"))
    }

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
    fun `receiving the lot closes the requirement and records who, how many and when`() {
        val data = written(PurchaseWrite.markReceived(stored(quantity = 6.0), 6.0, author, at))

        assertEquals(PurchaseWrite.STATUS_RECEIVED, data["status"])
        assertEquals(true, data["received"])
        assertEquals(6.0, data["rcvQty"])
        assertEquals("Asha", data["rcvBy"])
        assertEquals("uid_admin", data["rcvUid"])
        assertEquals(at, data["rcvAt"])
    }

    // --- part deliveries ------------------------------------------------------

    @Test
    fun `five of ten leaves the requirement open and says so out loud`() {
        // The defect this answers: the first delivery used to close the
        // requirement whatever its size, and the five still outstanding
        // vanished off the shop floor's list.
        val data = written(PurchaseWrite.markReceived(stored(quantity = 10.0), 5.0, author, at))

        assertEquals(5.0, data["rcvQty"])
        // Both written rather than left absent, because a V8C4 row may carry
        // neither and "not finished" has to be stated.
        assertEquals(false, data["received"])
        assertEquals(PurchaseWrite.STATUS_NEEDED, data["status"])
        // The total required is untouched: `qty` is what was asked for, and a
        // delivery never reduces it.
        assertEquals(10.0, data["qty"])
    }

    @Test
    fun `the second five closes it, and the total is cumulative`() {
        val partly = stored(quantity = 10.0, receivedQuantity = 5.0)
        val data = written(PurchaseWrite.markReceived(partly, 5.0, author, at))

        assertEquals("the running total, not the last delivery", 10.0, data["rcvQty"])
        assertEquals(true, data["received"])
        assertEquals(PurchaseWrite.STATUS_RECEIVED, data["status"])
    }

    @Test
    fun `three smaller deliveries add up rather than replace each other`() {
        var record = stored(quantity = 9.0)
        listOf(2.0, 3.0, 4.0).forEachIndexed { index, arrived ->
            val data = written(PurchaseWrite.markReceived(record, arrived, author, at))
            val total = data["rcvQty"] as Double
            record = record.copy(
                receivedQuantity = total,
                received = data["received"] as Boolean,
                status = data["status"] as String
            )
            val last = index == 2
            assertEquals("after ${index + 1} deliveries", last, record.received)
        }
        assertEquals(9.0, record.receivedQuantity!!, 0.0)
        assertTrue("the third delivery finishes it", record.isClosed)
    }

    @Test
    fun `more than is still outstanding is refused, with the figure in the sentence`() {
        val partly = stored(quantity = 10.0, receivedQuantity = 7.0)
        assertEquals(
            PurchasePlan.Refused(PurchaseWrite.moreThanRemaining(3.0)),
            PurchaseWrite.markReceived(partly, 4.0, author, at)
        )
        // Exactly what is outstanding is not "more than", and must go through.
        assertTrue(PurchaseWrite.markReceived(partly, 3.0, author, at) is PurchasePlan.Write)
    }

    @Test
    fun `awkward decimals still close the requirement`() {
        // `0.1 + 0.2` is not `0.3`, and a requirement that will not close
        // because of the seventeenth decimal place is a defect on a floor.
        var record = stored(quantity = 0.3)
        listOf(0.1, 0.1, 0.1).forEach { arrived ->
            val data = written(PurchaseWrite.markReceived(record, arrived, author, at))
            record = record.copy(
                receivedQuantity = data["rcvQty"] as Double,
                received = data["received"] as Boolean
            )
        }
        assertTrue("three tenths make three tenths", record.received)
        assertEquals(0.3, record.receivedQuantity!!, 1e-9)
    }

    @Test
    fun `a legacy row already marked received stays closed however short its total`() {
        // The PWA overwrites `rcvQty` with each delivery, so a closed row
        // carrying less than its `qty` is ordinary. Arithmetic must never
        // reopen something a person marked finished.
        val legacy = stored(
            quantity = 10.0,
            receivedQuantity = 4.0,
            received = true,
            status = "Received",
            receivedBy = "Omar"
        )
        assertTrue(legacy.isClosed)
        assertFalse(legacy.isOpen)
        assertEquals(
            PurchasePlan.Refused("Already received by Omar"),
            PurchaseWrite.markReceived(legacy, 6.0, author, at)
        )
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

    // --- writing off what is not coming --------------------------------------

    @Test
    fun `a shortfall closes the requirement at exactly what arrived`() {
        val partly = stored(quantity = 10.0, receivedQuantity = 7.0)
        val data = written(PurchaseWrite.closeShortfall(partly, author, at))

        // The whole of the change: the required total becomes the receipt.
        assertEquals(7.0, data["qty"])
        assertEquals(PurchaseWrite.STATUS_RECEIVED, data["status"])
        assertEquals(true, data["received"])
        assertEquals("the stored revision plus one, as every update does", 2, data["rev"])
        assertEquals("and who wrote it off, not who took the delivery in", "Asha", data["upBy"])
    }

    @Test
    fun `it takes no quantity, so nobody can write off a delivery`() {
        // There is no parameter to pass a figure in. The new total is read
        // from the stored receipt and from nowhere else, which is what keeps
        // this from being a status setter wearing a hat.
        val partly = stored(quantity = 10.0, receivedQuantity = 7.0)
        val data = written(PurchaseWrite.closeShortfall(partly, author, at))
        assertEquals(partly.receivedTotal, data["qty"])
    }

    @Test
    fun `the receipt itself is preserved untouched`() {
        // Who took the delivery in is not who wrote off the rest, and the
        // record of a delivery is not the write-off's to rewrite.
        val partly = stored(quantity = 10.0, receivedQuantity = 7.0)
        val data = written(PurchaseWrite.closeShortfall(partly, author, at))

        for (field in listOf("rcvQty", "rcvBy", "rcvUid", "rcvAt")) {
            assertFalse("$field must not be rewritten", data.containsKey(field))
        }
    }

    @Test
    fun `nothing arrived means remove it, not close it`() {
        assertEquals(
            PurchasePlan.Refused(PurchaseWrite.NOTHING_ARRIVED),
            PurchaseWrite.closeShortfall(stored(quantity = 10.0), author, at)
        )
    }

    @Test
    fun `a requirement that is not short of anything is refused`() {
        assertEquals(
            PurchasePlan.Refused(PurchaseWrite.NOTHING_OUTSTANDING),
            PurchaseWrite.closeShortfall(
                stored(quantity = 10.0, receivedQuantity = 10.0), author, at
            )
        )
    }

    @Test
    fun `a closed or removed requirement cannot be written off`() {
        val done = stored(quantity = 10.0, receivedQuantity = 7.0, received = true, receivedBy = "Omar")
        assertEquals(
            PurchasePlan.Refused("Already received by Omar"),
            PurchaseWrite.closeShortfall(done, author, at)
        )
        assertEquals(
            PurchasePlan.Refused(PurchaseWrite.ALREADY_DELETED),
            PurchaseWrite.closeShortfall(stored(receivedQuantity = 2.0, deleted = true), author, at)
        )
    }

    @Test
    fun `a shortfall carries no del key and re-asserts the id`() {
        val data = written(
            PurchaseWrite.closeShortfall(stored(quantity = 10.0, receivedQuantity = 7.0), author, at)
        )
        assertFalse(data.containsKey("del"))
        assertEquals("pr_one", data["id"])
        assertEquals(at, data["updated"])
        assertSame(ServerTimestamp, data["serverAt"])
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
        // There is no setStatus and no cancel: `Received` comes from a
        // delivery and `Needed` from reopen, so no caller can invent a state
        // nobody designed. A PWA-written `Cancelled` still reads fine.
        //
        // Three others write a status and each is a named thing somebody
        // decided to do: `edit` finishes a requirement when the total needed
        // is corrected down to what arrived, `closeShortfall` finishes one
        // when the rest is not coming, and `reopen` returns it to Needed.
        // All three are tested above. There is still no setter.
        val received = written(PurchaseWrite.markReceived(stored(quantity = 6.0), 6.0, author, at))
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
