package `in`.smartie.quotedesk.ui.quotations

import `in`.smartie.quotedesk.data.model.QuotationRecord
import `in`.smartie.quotedesk.data.repository.CancelOutcome
import `in`.smartie.quotedesk.data.repository.QuotationWriteRepository
import `in`.smartie.quotedesk.ui.products.QuoteFinaliser
import `in`.smartie.quotedesk.ui.products.QuoteRequest
import `in`.smartie.quotedesk.ui.products.QuoteRequests
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Cancel and Edit from the Quotations tab (N5.10 commit 6).
 *
 * Cancel is remote-first: nothing on this device says "cancelled" until the
 * write has been accepted, and the list itself turns only when the listener
 * brings the change — so this view model holds no quotation at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QuotationsViewModelTest {

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    private val nine = QuotationRecord(id = "qd_9", number = "SIE/QD/2025-26/009", byUid = "u_m")

    private val sent = mutableListOf<String>()
    private val logged = mutableListOf<Throwable>()
    private val online = MutableStateFlow(true)
    private val requests = QuoteRequests()

    private fun viewModel(
        capMillis: Long = QuoteFinaliser.CAP_MILLIS,
        write: suspend (String, String) -> CancelOutcome = { _, number -> CancelOutcome.Cancelled(number) }
    ) = QuotationsViewModel(
        uid = "u_m",
        cancelWrite = { id, number -> sent += id; write(id, number) },
        requests = requests,
        onlineFlow = online,
        describe = { it.message ?: "Something went wrong" },
        log = { logged += it },
        capMillis = capMillis
    )

    @Test
    fun `a cancel accepted says V8C4's words, and nothing stays pending`() = runTest {
        val model = viewModel()

        model.cancel(nine)

        assertEquals(listOf("qd_9"), sent)
        assertEquals("SIE/QD/2025-26/009 cancelled", model.messages.first())
        assertNull(model.cancelling.value)
        assertNull(model.failure.value)
    }

    @Test
    fun `offline, nothing is sent and the person is told why`() = runTest {
        online.value = false
        val model = viewModel()

        model.cancel(nine)

        assertTrue(sent.isEmpty())
        assertEquals(CancelFailure("qd_9", QuotationsViewModel.CANCEL_OFFLINE), model.failure.value)
    }

    @Test
    fun `a refusal is shown as not cancelled, and its cause logged`() = runTest {
        val cause = IllegalStateException("PERMISSION_DENIED")
        val model = viewModel(write = { _, _ -> CancelOutcome.Refused(QuotationWriteRepository.CANCEL_REFUSED, cause) })

        model.cancel(nine)

        assertEquals(
            CancelFailure("qd_9", QuotationsViewModel.notCancelled(QuotationWriteRepository.CANCEL_REFUSED)),
            model.failure.value
        )
        assertEquals(listOf<Throwable>(cause), logged)
        assertNull(model.cancelling.value)
    }

    @Test
    fun `a write that throws may have landed, and says so`() = runTest {
        val model = viewModel(write = { _, _ -> throw IllegalStateException("Network unavailable") })

        model.cancel(nine)

        assertEquals(
            "Not cancelled — Network unavailable. If it went through, the list will show it as cancelled.",
            model.failure.value?.message
        )
        assertEquals("Network unavailable", logged.single().message)
    }

    @Test
    fun `a write that never answers ends at the cap`() = runTest {
        val model = viewModel(capMillis = 50, write = { _, _ -> awaitCancellation() })

        model.cancel(nine)
        assertEquals("qd_9", model.cancelling.value)
        advanceTimeBy(51)
        runCurrent()

        assertEquals(
            CancelFailure("qd_9", QuotationsViewModel.notCancelled(QuoteFinaliser.TIMED_OUT, mayHaveLanded = true)),
            model.failure.value
        )
        assertNull(model.cancelling.value)
    }

    @Test
    fun `a second press while a cancel is out sends nothing more`() = runTest {
        val answer = CompletableDeferred<CancelOutcome>()
        val model = viewModel(write = { _, _ -> answer.await() })

        model.cancel(nine)
        model.cancel(nine)
        model.cancel(nine.copy(id = "qd_10", number = "SIE/QD/2025-26/010"))
        answer.complete(CancelOutcome.Cancelled(nine.number))

        assertEquals(listOf("qd_9"), sent)
        assertNull(model.cancelling.value)
    }

    @Test
    fun `a new try clears the last failure`() = runTest {
        online.value = false
        val model = viewModel()
        model.cancel(nine)
        online.value = true

        model.cancel(nine)

        assertNull(model.failure.value)
    }

    @Test
    fun `Edit hands the quotation to the builder, marked with who asked`() = runTest {
        viewModel().edit(nine)

        assertEquals(QuoteRequest.Edit(nine, requestedBy = "u_m"), requests.pending.value)
    }
}
