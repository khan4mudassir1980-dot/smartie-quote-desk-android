package `in`.smartie.quotedesk.data.repository

import `in`.smartie.quotedesk.data.mapping.DocData
import `in`.smartie.quotedesk.data.model.PartyRecord
import `in`.smartie.quotedesk.data.model.QuotationPartySnapshot
import `in`.smartie.quotedesk.data.model.RateTierV2
import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.domain.PartyDraft
import `in`.smartie.quotedesk.domain.PartyFormat
import `in`.smartie.quotedesk.domain.PartyMatcher
import `in`.smartie.quotedesk.domain.PartyWrite
import `in`.smartie.quotedesk.domain.QuoteParty
import `in`.smartie.quotedesk.domain.Role
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The party writer, through the transaction.
 *
 * The one that matters most is the one an emulator cannot stage on demand:
 * **a retry after an ambiguous failure must land on the same document.** The
 * commit went through, the acknowledgement did not, and the person presses
 * Save again. With an id minted per attempt that writes a second customer —
 * which is precisely N4.4's B2, and a customer is worse than a requirement
 * because every quotation ever issued points at one of the two.
 *
 * Stored `staff` is displayed **Manager**; stored `worker` is displayed
 * **Staff** and may not write a party at all.
 */
class PartyWriteRepositoryTest {

    private class FakeStore(
        private val stored: MutableMap<String, Map<String, Any?>> = mutableMapOf(),
        private val attempts: Int = 1
    ) : PartyStore {
        val writes = mutableListOf<Written>()
        var bodyRuns = 0

        class Written(val docId: String, val data: Map<String, Any?>, val merge: Boolean)

        /** Makes a committed write visible to the next call, as Firestore does. */
        fun commit() {
            writes.forEach { stored[it.docId] = it.data }
        }

        override suspend fun <T> transaction(body: (PartyTransaction) -> T): T {
            var last: T? = null
            repeat(attempts) {
                bodyRuns++
                // Firestore discards what a replayed attempt recorded.
                writes.clear()
                last = body(object : PartyTransaction {
                    override fun read(docId: String): DocData? =
                        stored[docId]?.let { DocData(docId, it) }

                    override fun write(docId: String, data: Map<String, Any?>, merge: Boolean) {
                        writes += Written(docId, data, merge)
                    }
                })
            }
            @Suppress("UNCHECKED_CAST")
            return last as T
        }
    }

    private val manager = Member(uid = "uid_staff", name = "Sam", role = Role.STAFF)
    private val owner = Member(uid = "uid_owner", name = "Mudassir", role = Role.OWNER)

    /** Stored `worker`, displayed Staff. */
    private val staff = Member(uid = "uid_worker", name = "Ravi", role = Role.WORKER)

    private val draft = PartyDraft(name = "Metro Glass", city = "Mumbai")

    private val sunriseDoc = mapOf(
        "id" to "c_1", "name" to "Sunrise Constructions", "city" to "Mumbai", "type" to "contractor"
    )
    private val sunrise = PartyRecord(id = "c_1", name = "Sunrise Constructions", city = "Mumbai")

    /** The throwable a write came back with, or null when it succeeded. */
    private suspend fun failureOf(body: suspend () -> Unit): Throwable? =
        runCatching { body() }.exceptionOrNull()

    // --- the retry that must not write twice --------------------------------------

    @Test
    fun `a retry with the same id lands on the same document, and is refused`() = runTest {
        val store = FakeStore()
        val writes = PartyWriteRepository(store, now = { 1_000L })

        // The first attempt commits, and its acknowledgement is lost.
        writes.create(manager, draft, id = "c_fixed")
        assertEquals("c_fixed", store.writes.single().docId)
        store.commit()

        // The person presses Save again with the same id, so the writer finds
        // the document and says so rather than writing a twin.
        val refusal = failureOf { writes.create(manager, draft, id = "c_fixed") }
        assertEquals(PartyWrite.ALREADY_EXISTS, refusal?.message)
        assertTrue("nothing was written", store.writes.isEmpty())
    }

    @Test
    fun `a fresh id each time is how two customers appear, which is why one is kept`() = runTest {
        // The behaviour the screen must not have, asserted so the reason the
        // id is minted once per form is visible rather than implied.
        val store = FakeStore()
        var next = 0
        val writes = PartyWriteRepository(store, now = { 1_000L }, newId = { "c_${next++}" })

        writes.create(manager, draft)
        store.commit()
        val first = store.writes.single().docId
        writes.create(manager, draft)
        val second = store.writes.single().docId

        assertEquals("c_0", first)
        assertEquals("c_1", second)
    }

    @Test
    fun `a replayed body reuses the timestamp it generated the first time`() = runTest {
        // Firestore may run the body more than once. `t` and `updated` are
        // taken outside it, so a replay cannot make one attempt disagree with
        // another about when this happened.
        var ticks = 0L
        val store = FakeStore(attempts = 3)
        val writes = PartyWriteRepository(store, now = { ticks++ })

        writes.create(manager, draft, id = "c_fixed")

        assertEquals(3, store.bodyRuns)
        assertEquals("now() is called once, outside the body", 1L, ticks)
        assertEquals(0L, store.writes.single().data["t"])
        assertEquals(0L, store.writes.single().data["updated"])
    }

    // --- what each role may write ---------------------------------------------------

    @Test
    fun `a Staff account cannot write a party at all`() = runTest {
        val writes = PartyWriteRepository(FakeStore())

        assertTrue(failureOf { writes.create(staff, draft) } is IllegalArgumentException)
        assertTrue(
            failureOf { writes.edit(staff, sunrise, draft) } is IllegalArgumentException
        )
    }

    @Test
    fun `a Manager corrects a party, sending no name and no archived key`() = runTest {
        val store = FakeStore(mutableMapOf("c_1" to sunriseDoc))
        val writes = PartyWriteRepository(store, now = { 2_000L })

        writes.edit(manager, sunrise, PartyDraft(name = "Sunrise Constructions", city = "Pune"))

        val data = store.writes.single().data
        assertEquals("Pune", data["city"])
        assertEquals("c_1", data["id"])
        assertEquals("Sam", data["upBy"])
        assertTrue("no name key", !data.containsKey("name"))
        assertTrue("no archived key", !data.containsKey("archived"))
        // The draft states no type, so the stored one is kept. Correcting a
        // city must not demote a contractor to a client on the way past.
        assertEquals("contractor", data["type"])
    }

    @Test
    fun `and cannot archive one`() = runTest {
        val store = FakeStore(mutableMapOf("c_1" to sunriseDoc))
        val writes = PartyWriteRepository(store, now = { 2_000L })

        assertTrue(
            failureOf { writes.setArchived(manager, sunrise, true) } is IllegalArgumentException
        )
        assertTrue(store.writes.isEmpty())
    }

    @Test
    fun `an Owner archives one, and the stamp is theirs`() = runTest {
        val store = FakeStore(mutableMapOf("c_1" to sunriseDoc))
        val writes = PartyWriteRepository(store, now = { 2_000L })

        writes.setArchived(owner, sunrise, archived = true)

        val data = store.writes.single().data
        assertEquals(true, data["archived"])
        // Not whoever last edited the record — the person doing it now.
        assertEquals("Mudassir", data["upBy"])
        assertEquals("uid_owner", data["upUid"])
    }

    @Test
    fun `the plan is built from the document, not from the copy the screen held`() = runTest {
        // The screen is showing a party that has since been archived. A
        // Manager's correction must be refused on what is stored, not waved
        // through on a stale record.
        val store = FakeStore(mutableMapOf("c_1" to sunriseDoc + mapOf("archived" to true)))
        val writes = PartyWriteRepository(store, now = { 2_000L })

        val refusal = failureOf {
            writes.edit(manager, sunrise, PartyDraft(name = "Sunrise Constructions", city = "Pune"))
        }

        assertEquals(PartyWrite.ARCHIVED_IS_READ_ONLY, refusal?.message)
    }

    @Test
    fun `a party that vanished under the screen is not an error`() = runTest {
        val store = FakeStore()
        val writes = PartyWriteRepository(store, now = { 2_000L })

        val result = writes.edit(owner, sunrise, PartyDraft(name = "Gone", city = "Pune"))

        assertEquals(PartyWriteResult.NO_CHANGE, result)
        assertTrue(store.writes.isEmpty())
        assertNull(failureOf { writes.edit(owner, sunrise, PartyDraft(name = "Gone")) })
    }

    // --- "Save this customer", from the quotation side ----------------------------------
    //
    // V8C4's `#qSaveParty` and `saveParty`: a name first; the saved customer the
    // FORM describes is found; the person is asked before it is updated; a new
    // one is created at the quotation's tier. N5.9a commits 8 and 8b.

    private val sunriseWithGstin = sunriseDoc + ("gstin" to "27AAACS1234F1Z5")

    /** Answers the question "yes" and remembers what was asked. */
    private val asked = mutableListOf<QuoteParty.MergeQuestion>()
    private val yes: suspend (QuoteParty.MergeQuestion) -> Boolean = { asked += it; true }
    private val no: suspend (QuoteParty.MergeQuestion) -> Boolean = { asked += it; false }

    private suspend fun PartyWriteRepository.save(
        member: Member,
        form: QuotationPartySnapshot,
        customers: List<PartyRecord>,
        newId: String = "c_new",
        tier: RateTierV2 = RateTierV2.CLIENT,
        confirm: suspend (QuoteParty.MergeQuestion) -> Boolean = yes
    ) = saveFromQuotation(member, form, tier, customers, newId, confirm)

    @Test
    fun `the customer the form describes is merged into, so an empty box forgets nothing`() = runTest {
        val store = FakeStore(mutableMapOf("c_1" to sunriseDoc))
        val writes = PartyWriteRepository(store, now = { 1_000L })

        // The quotation form has no Type box and no Notes box at all, so both
        // arrive blank. Through `edit` that would wipe the stored
        // `contractor`; through `mergeInto` it is silence.
        val saved = writes.save(
            manager,
            QuotationPartySnapshot(name = "Sunrise Constructions", phone = "9820011223"),
            customers = listOf(sunrise)
        )
        assertEquals(SavedParty("c_1", PartyWriteResult.WRITTEN), saved)

        val written = store.writes.single()
        assertEquals("c_1", written.docId)
        assertTrue("merged, never overwritten whole", written.merge)
        assertEquals("9820011223", written.data["phone"])
        assertNull("the stored type is left alone", written.data["type"])
        assertNull("and so is the city, with no site typed", written.data["city"])
    }

    @Test
    fun `declining the question writes nothing - V8C4's Cancel, leave it alone`() = runTest {
        // Rule 7 for commit 8b: take away the question — write on a match
        // without asking — and this fails.
        val store = FakeStore(mutableMapOf("c_1" to sunriseDoc))
        val writes = PartyWriteRepository(store, now = { 1_000L })

        val saved = writes.save(
            manager,
            QuotationPartySnapshot(name = "Sunrise Constructions", phone = "9820011223"),
            customers = listOf(sunrise),
            confirm = no
        )

        assertEquals(SavedParty(null, PartyWriteResult.NO_CHANGE, declined = true), saved)
        assertTrue("nothing on the wire", store.writes.isEmpty())
        // And the person was asked, with the party and the reason named.
        assertEquals(
            "\u201CSunrise Constructions\u201D is already saved with the same company name.",
            asked.single().message
        )
    }

    @Test
    fun `the question names matchReason's reason, GSTIN first`() = runTest {
        val store = FakeStore(mutableMapOf("c_1" to sunriseWithGstin))
        PartyWriteRepository(store, now = { 1_000L }).save(
            manager,
            // Lower case, which `norm` forgives and the validator upper-cases.
            // Until N5.9b commit 4c this was typed with spaces inside; V8C4's
            // `gstinProblem` refuses that by length before the find, and so
            // does this app now — see the test below.
            QuotationPartySnapshot(name = "Sunrise Constructions", gstin = "27aaacs1234f1z5"),
            customers = listOf(sunrise.copy(gstin = "27AAACS1234F1Z5"))
        )
        assertEquals(PartyMatcher.GSTIN, asked.single().reason)
    }

    // --- the formats, between the name and the find (N5.9b commit 4c) --------------------

    @Test
    fun `a malformed GSTIN, phone or email is refused bare, before anything is found, asked or written`() =
        runTest {
            val cases = listOf(
                QuotationPartySnapshot(name = "Sunrise Constructions", gstin = "27 AAACS 1234 F1Z5") to
                    PartyFormat.GSTIN_LENGTH,
                QuotationPartySnapshot(name = "Sunrise Constructions", phone = "98200") to
                    PartyFormat.PHONE_TOO_SHORT,
                QuotationPartySnapshot(name = "Sunrise Constructions", email = "sales@sunrise") to
                    PartyFormat.EMAIL_SHAPE
            )
            for ((form, problem) in cases) {
                val store = FakeStore(mutableMapOf("c_1" to sunriseWithGstin))
                val failure = failureOf {
                    // The name matches Sunrise, so without the check the
                    // question would be asked and a write would follow.
                    PartyWriteRepository(store, now = { 1_000L })
                        .save(manager, form, customers = listOf(sunrise.copy(gstin = "27AAACS1234F1Z5")))
                }
                // Bare, as V8C4's toast(bad): no "Client GSTIN: " prefix here.
                assertEquals(problem, failure?.message)
                assertTrue("nobody was asked about ${form}", asked.isEmpty())
                assertTrue("nothing on the wire for ${form}", store.writes.isEmpty())
            }
        }

    @Test
    fun `the GSTIN is checked before the phone, the phone before the email, and the name before all three`() =
        runTest {
            val store = FakeStore(mutableMapOf("c_1" to sunriseDoc))
            val allWrong = QuotationPartySnapshot(
                name = "Sunrise Constructions", gstin = "27ABC", phone = "98200", email = "x"
            )
            val writes = PartyWriteRepository(store, now = { 1_000L })

            assertEquals(PartyFormat.GSTIN_LENGTH, failureOf { writes.save(manager, allWrong, listOf(sunrise)) }?.message)
            assertEquals(
                PartyFormat.PHONE_TOO_SHORT,
                failureOf { writes.save(manager, allWrong.copy(gstin = ""), listOf(sunrise)) }?.message
            )
            assertEquals(
                PartyWrite.NAME_REQUIRED,
                failureOf { writes.save(manager, allWrong.copy(name = ""), listOf(sunrise)) }?.message
            )
        }

    @Test
    fun `an update never renames, so a Manager's spelling correction goes through`() = runTest {
        // V8C4's `saveParty` writes the name only on create. The `/customers`
        // rule forbids a Manager to rename; with no rename attempted, nothing
        // is refused. Matched here on the GSTIN alone.
        val store = FakeStore(mutableMapOf("c_1" to sunriseWithGstin))
        val saved = PartyWriteRepository(store, now = { 1_000L }).save(
            manager,
            QuotationPartySnapshot(name = "Sunrise Construction Co", gstin = "27AAACS1234F1Z5", phone = "9820011223"),
            customers = listOf(sunrise.copy(gstin = "27AAACS1234F1Z5"))
        )
        assertEquals(SavedParty("c_1", PartyWriteResult.WRITTEN), saved)
        val written = store.writes.single().data
        assertEquals("9820011223", written["phone"])
        assertTrue("the name is never written on an update", !written.containsKey("name"))
    }

    @Test
    fun `a customer typed onto the quotation is created at the quotation's tier, the site as its city`() = runTest {
        val store = FakeStore()
        val writes = PartyWriteRepository(store, now = { 1_000L })

        val saved = writes.save(
            owner,
            QuotationPartySnapshot(name = "Metro Glass", site = "Mumbai"),
            customers = listOf(sunrise),
            tier = RateTierV2.DEALER
        )
        assertEquals(SavedParty("c_new", PartyWriteResult.WRITTEN), saved)
        assertTrue("nothing to ask about a new customer", asked.isEmpty())

        val written = store.writes.single()
        assertEquals("c_new", written.docId)
        assertEquals("Metro Glass", written.data["name"])
        assertEquals("Mumbai", written.data["city"])
        // V8C4: `type: p.type || state.tier || "client"`, with `type: state.tier`.
        assertEquals("dealer", written.data["type"])
        // A new customer has nothing underneath it, so it is written whole.
        assertEquals(false, written.merge)
    }

    @Test
    fun `pressing Save twice on a new customer does not make two`() = runTest {
        val store = FakeStore()
        val writes = PartyWriteRepository(store, now = { 1_000L })
        val form = QuotationPartySnapshot(name = "Metro Glass", site = "Mumbai")

        writes.save(owner, form, customers = emptyList())
        store.commit()

        // The panel holds one id for as long as one quotation is being filled
        // in, so the second press arrives with the same one and is refused
        // honestly rather than writing a twin.
        val failure = failureOf { writes.save(owner, form, customers = emptyList()) }
        assertEquals(PartyWrite.ALREADY_EXISTS, failure?.message)
    }

    @Test
    fun `a customer deleted since the list saw it writes nothing`() = runTest {
        val store = FakeStore()
        val result = PartyWriteRepository(store, now = { 1_000L }).save(
            manager,
            QuotationPartySnapshot(name = "Sunrise Constructions"),
            customers = listOf(sunrise.copy(id = "c_gone"))
        )
        assertEquals(SavedParty(null, PartyWriteResult.NO_CHANGE), result)
        assertTrue("nothing on the wire", store.writes.isEmpty())
    }

    @Test
    fun `a customer with no name is refused before anything is found, asked or written`() = runTest {
        val store = FakeStore(mutableMapOf("c_1" to sunriseDoc))
        val failure = failureOf {
            PartyWriteRepository(store, now = { 1_000L }).save(
                manager,
                QuotationPartySnapshot(name = "   ", phone = "9876543210"),
                customers = listOf(sunrise.copy(phone = "9876543210"))
            )
        }
        assertEquals(PartyWrite.NAME_REQUIRED, failure?.message)
        assertTrue("nobody was asked", asked.isEmpty())
        assertTrue("nothing on the wire", store.writes.isEmpty())
    }

    @Test
    fun `a Staff account cannot save a customer from a quotation either`() = runTest {
        val store = FakeStore(mutableMapOf("c_1" to sunriseDoc))
        val failure = failureOf {
            PartyWriteRepository(store, now = { 1_000L })
                .save(staff, QuotationPartySnapshot(name = "Metro Glass"), customers = listOf(sunrise))
        }
        assertEquals(PartyWriteRepository.NOT_ALLOWED, failure?.message)
        assertTrue("nothing on the wire", store.writes.isEmpty())
    }

    @Test
    fun `a quotation that changes nothing writes nothing`() = runTest {
        val store = FakeStore(mutableMapOf("c_1" to sunriseDoc))
        val result = PartyWriteRepository(store, now = { 1_000L }).save(
            manager,
            QuotationPartySnapshot(name = "Sunrise Constructions", site = "Mumbai"),
            customers = listOf(sunrise)
        )
        assertEquals(SavedParty("c_1", PartyWriteResult.NO_CHANGE), result)
        assertTrue("nothing on the wire", store.writes.isEmpty())
    }

    @Test
    fun `a customer picked and then typed over is never written into - the N5_8b defect`() = runTest {
        // Pick Sunrise, type Metro Glass's details over the form, press Save.
        // V8C4's `saveParty` re-finds from the form and never consults the
        // held id; until N5.9a commit 8 this merged Metro Glass's phone into
        // Sunrise. There is no held id to pass any more.
        val metroDoc = mapOf("id" to "c_2", "name" to "Metro Glass", "phone" to "9822001100")
        val metro = PartyRecord(id = "c_2", name = "Metro Glass", phone = "9822001100")
        val typedOver = QuotationPartySnapshot(name = "Metro Glass", phone = "9822001100", site = "Pune")

        // Metro Glass is saved: it is updated once the person agrees, and
        // Sunrise is untouched.
        val both = FakeStore(mutableMapOf("c_1" to sunriseDoc, "c_2" to metroDoc))
        val saved = PartyWriteRepository(both, now = { 1_000L })
            .save(manager, typedOver, customers = listOf(sunrise, metro))
        assertEquals(SavedParty("c_2", PartyWriteResult.WRITTEN), saved)
        assertEquals(listOf("c_2"), both.writes.map { it.docId })
        assertEquals("Metro Glass", asked.single().partyName)

        // Metro Glass is not saved: a new customer, and Sunrise still untouched.
        val sunriseOnly = FakeStore(mutableMapOf("c_1" to sunriseDoc))
        val created = PartyWriteRepository(sunriseOnly, now = { 1_000L })
            .save(manager, typedOver, customers = listOf(sunrise))
        assertEquals(SavedParty("c_new", PartyWriteResult.WRITTEN), created)
        assertEquals(listOf("c_new"), sunriseOnly.writes.map { it.docId })
    }

    @Test
    fun `an archived customer the form resembles is not written into`() = runTest {
        // V8C4's `findCustomer` skips archived parties, so the form makes a
        // new customer rather than editing one somebody archived.
        val store = FakeStore(mutableMapOf("c_1" to sunriseDoc + ("archived" to true)))
        val saved = PartyWriteRepository(store, now = { 1_000L }).save(
            manager,
            QuotationPartySnapshot(name = "Sunrise Constructions", phone = "9820011223"),
            customers = listOf(sunrise.copy(archived = true))
        )
        assertEquals(SavedParty("c_new", PartyWriteResult.WRITTEN), saved)
        assertEquals(listOf("c_new"), store.writes.map { it.docId })
        assertTrue("nothing to ask about", asked.isEmpty())
    }
}
