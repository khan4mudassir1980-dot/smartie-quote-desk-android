package `in`.smartie.quotedesk.ui.purchase

import `in`.smartie.quotedesk.data.mapping.DocData
import `in`.smartie.quotedesk.data.model.PurchaseRecord
import `in`.smartie.quotedesk.data.repository.PurchaseStore
import `in`.smartie.quotedesk.data.repository.PurchaseTransaction
import `in`.smartie.quotedesk.data.repository.PurchaseWriteRepository
import `in`.smartie.quotedesk.domain.Member
import `in`.smartie.quotedesk.domain.PurchaseDraft
import `in`.smartie.quotedesk.domain.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

/**
 * Adding the same requirement twice writes **one** document.
 *
 * This is N4.4's B2, and the failure it guards is not a double tap — the view
 * model's in-flight key already stops that, synchronously, before any
 * coroutine starts. It is the retry the app invites: the Add sheet stays open
 * on a failure with everything typed still in it, and a write that failed
 * *after* the server committed it looks, from the device, exactly like one
 * that never landed. Tapping Add again used to mint a second identity and
 * write a second document with the same name, the same author and a timestamp
 * seconds apart — the two "yysh" cards the phone pass found.
 *
 * So the store here commits and *then* fails, which is the case that actually
 * happened, and the test asserts on the documents that exist afterwards
 * rather than on the messages.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PurchaseAddIdempotencyTest {

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    /** A store that keeps what it was given, so a second attempt can see it. */
    private class RememberingStore(
        /** Throw after committing, the ambiguous failure that started all this. */
        var failAfterWriting: Boolean = false
    ) : PurchaseStore {
        val documents = linkedMapOf<String, Map<String, Any?>>()

        override suspend fun <T> transaction(body: (PurchaseTransaction) -> T): T {
            val result = body(object : PurchaseTransaction {
                override fun read(docId: String): DocData? =
                    documents[docId]?.let { DocData(docId, it) }

                override fun write(docId: String, data: Map<String, Any?>, merge: Boolean) {
                    documents[docId] = data
                }
            })
            if (failAfterWriting) throw IllegalStateException("the network went away")
            return result
        }
    }

    private val worker = Member(uid = "uid_worker", name = "Wes", role = Role.WORKER)
    private val draft = PurchaseDraft(name = "Remote handsets", quantity = 4.0)

    private var minted = 0

    private fun viewModel(store: RememberingStore) = PurchaseViewModel(
        member = worker,
        writes = PurchaseWriteRepository(store, now = { 1_700_000_000_000L }),
        requirements = MutableStateFlow<List<PurchaseRecord>>(emptyList()),
        onlineFlow = MutableStateFlow(true),
        // Counts, rather than returning a constant: a constant would hide the
        // very defect this class exists for.
        newRequirementId = { "pr_${++minted}" }
    )

    @Test
    fun `a retry after a write that may have landed adds nothing new`() = runTest {
        val store = RememberingStore(failAfterWriting = true)
        val model = viewModel(store)

        model.open(PurchaseSheet.ADD)
        model.add(draft)
        assertEquals("the first attempt wrote its document", 1, store.documents.size)

        // The sheet is still open and the person taps Add again.
        store.failAfterWriting = false
        model.add(draft)

        assertEquals(
            "the retry must land on the document the first attempt wrote",
            1,
            store.documents.size
        )
        assertEquals("and must not mint a second identity", 1, minted)
    }

    @Test
    fun `and the one document it wrote is the requirement that was typed`() = runTest {
        val store = RememberingStore()
        val model = viewModel(store)

        model.open(PurchaseSheet.ADD)
        model.add(draft)

        val written = store.documents.values.single()
        assertEquals("Remote handsets", written["name"])
        assertEquals(4.0, written["qty"])
        assertEquals("uid_worker", written["byUid"])
        assertEquals(
            "the stored id is the document id, which is what the rules require",
            store.documents.keys.single(),
            written["id"]
        )
    }

    @Test
    fun `two taps in the same frame write once`() = runTest {
        // The guard that already existed, now asserted for `add` rather than
        // only for a receipt: the in-flight key is set synchronously, before
        // any coroutine starts, so the second call never opens a transaction.
        val store = RememberingStore()
        val model = viewModel(store)

        model.open(PurchaseSheet.ADD)
        model.add(draft)
        model.add(draft)

        assertEquals(1, store.documents.size)
        assertEquals(1, minted)
    }

    @Test
    fun `a second requirement, asked for separately, is its own document`() = runTest {
        // The other half: idempotency must not turn two deliberate adds into
        // one. Opening the sheet again is what says "a different thing".
        val store = RememberingStore()
        val model = viewModel(store)

        model.open(PurchaseSheet.ADD)
        model.add(draft)
        model.open(PurchaseSheet.ADD)
        model.add(draft.copy(name = "Gate rollers"))

        assertEquals(2, store.documents.size)
        assertEquals(2, minted)
        assertNotNull(store.documents["pr_1"])
        assertNotNull(store.documents["pr_2"])
    }
}
