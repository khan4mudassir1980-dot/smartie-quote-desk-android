package `in`.smartie.quotedesk.domain

import `in`.smartie.quotedesk.data.mapping.Keys
import `in`.smartie.quotedesk.data.model.StockRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a removal comes to, off device.
 *
 * The contract this holds is mostly about what the record **does not**
 * carry. A history entry outlives the item, so anything in it outlives the
 * promise that the quantity, the note and the photo were deleted.
 */
class StockRemovalTest {

    private val key = Keys.productKey("gateMotors", "SIE1000")

    private fun record(
        quantity: Double = 7.0,
        manual: Boolean = false,
        hasPhoto: Boolean = false,
        note: String = "",
        pinned: Boolean = false
    ) = StockRecord(
        documentId = Keys.stockDocId(key),
        key = key,
        quantity = quantity,
        reorderLevel = 2.0,
        name = "Sliding gate motor",
        model = "SIE1000",
        group = "gateMotors",
        unit = "each",
        manual = manual,
        note = note,
        pinned = pinned,
        hasPhoto = hasPhoto,
        photoRev = if (hasPhoto) 3.0 else 0.0
    )

    private val author = StockAuthor(name = "Asha", uid = "uid_admin")

    private fun plan(
        record: StockRecord = record(),
        stillThere: Boolean = true,
        quantity: Double = 7.0,
        hasStoredPhoto: Boolean = false,
        eventId: String = "sr_one"
    ) = StockRemoval.remove(
        record = record,
        stillThere = stillThere,
        storedQuantity = quantity,
        hasStoredPhoto = hasStoredPhoto,
        author = author,
        at = 1_700_000_000_000L,
        eventId = eventId
    )

    // --- the shape of a removal ---------------------------------------------

    @Test
    fun `removing deletes the row and records the event`() {
        val outcome = plan() as StockRemovalPlan.Remove

        assertEquals(Keys.stockDocId(key), outcome.stockDocId)
        assertEquals("sr_one", outcome.eventId)
        assertEquals("sr_one", outcome.event["id"])
        assertEquals(key, outcome.event["key"])
    }

    @Test
    fun `a row with a photo takes the photo with it`() {
        val outcome = plan(record = record(hasPhoto = true), hasStoredPhoto = true)
            as StockRemovalPlan.Remove
        assertEquals(StockPhoto.documentId(key), outcome.photoDocId)
    }

    @Test
    fun `a row without one spends no write deleting a photo that is not there`() {
        val outcome = plan() as StockRemovalPlan.Remove
        assertNull(outcome.photoDocId)
    }

    @Test
    fun `an item that is already gone is not removed twice`() {
        // A second tap must not write a second history entry.
        assertEquals(StockRemovalPlan.NoChange, plan(stillThere = false))
    }

    @Test
    fun `the quantity recorded is the stored one, not the one on screen`() {
        val outcome = plan(record = record(quantity = 3.0), quantity = 9.0)
            as StockRemovalPlan.Remove
        assertEquals(9.0, outcome.event["q"])
    }

    // --- what the record carries --------------------------------------------

    @Test
    fun `the record carries what a person needs to recognise the row`() {
        val event = (plan() as StockRemovalPlan.Remove).event

        assertEquals("Sliding gate motor", event["name"])
        assertEquals("SIE1000", event["model"])
        assertEquals(7.0, event["q"])
        assertEquals(false, event["manual"])
    }

    @Test
    fun `a manual item says so`() {
        val event = (plan(record = record(manual = true)) as StockRemovalPlan.Remove).event
        assertEquals(true, event["manual"])
    }

    @Test
    fun `the record carries nothing that was supposed to be deleted`() {
        // The confirmation promises the quantity, the note and the photo are
        // permanently deleted. A record holding any of them would make that
        // a lie — except the last quantity, which the history openly shows.
        val event = (
            plan(record = record(note = "Kept in the back rack", pinned = true, hasPhoto = true))
                as StockRemovalPlan.Remove
            ).event

        for (field in StockRemoval.FORBIDDEN_FIELDS) {
            assertFalse("a history entry must not carry $field", event.containsKey(field))
        }
    }

    @Test
    fun `the record carries the internals the rules and the ordering need`() {
        val event = (plan() as StockRemovalPlan.Remove).event

        // Neither is ever rendered; see the history section's tests.
        assertEquals("uid_admin", event["byUid"])
        assertEquals(1_700_000_000_000L, event["at"])
        assertEquals(ServerTimestamp, event["serverAt"])
        // The rules read this back to prove the row is gone after the commit.
        assertEquals(Keys.stockDocId(key), event["stockDoc"])
    }

    @Test
    fun `the author's name is not stored, only the uid the rules require`() {
        val event = (plan() as StockRemovalPlan.Remove).event
        assertFalse("who removed it is never shown, so it is not kept", event.containsKey("by"))
    }

    // --- event identity ------------------------------------------------------

    @Test
    fun `two removals of the same key are two distinct events`() {
        val first = plan(eventId = "sr_one") as StockRemovalPlan.Remove
        val second = plan(eventId = "sr_two") as StockRemovalPlan.Remove
        assertNotEquals(first.eventId, second.eventId)
    }

    @Test
    fun `a legacy conversion is deterministic, so a retry cannot double up`() {
        val id = StockRemoval.legacyEventId(Keys.stockDocId(key))
        assertEquals(id, StockRemoval.legacyEventId(Keys.stockDocId(key)))
        assertTrue(id.startsWith(StockRemoval.LEGACY_PREFIX))
    }

    @Test
    fun `a legacy id is a legal document id`() {
        // A stock document id already has `/` replaced, which is the only
        // character Firestore forbids outright.
        val id = StockRemoval.legacyEventId(Keys.stockDocId("gate/motors|SIE/1000"))
        assertFalse("a document id may not contain a slash", id.contains('/'))
    }

    @Test
    fun `two different rows convert under two different ids`() {
        assertNotEquals(
            StockRemoval.legacyEventId(Keys.stockDocId("gateMotors|A")),
            StockRemoval.legacyEventId(Keys.stockDocId("gateMotors|B"))
        )
    }
}
