package `in`.smartie.quotedesk.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * How a live listener survives losing its connection.
 *
 * The defect this answers: a snapshot listener used to **complete** on a
 * benign refusal, and a `stateIn` whose upstream has completed is never
 * collected again. One transient `PERMISSION_DENIED` froze a screen's data
 * for the life of the process, silently. So the operator under test here is
 * the thing that must never give up quietly — and must never spin either.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FirestoreFlowsTest {

    private val fast = ListenerRetry(firstDelayMillis = 10L, maxDelayMillis = 80L)

    // --- the waits ------------------------------------------------------------

    @Test
    fun `the wait doubles and then stops growing`() {
        val retry = ListenerRetry(firstDelayMillis = 1_000L, maxDelayMillis = 60_000L)
        assertEquals(1_000L, retry.delayFor(0))
        assertEquals(2_000L, retry.delayFor(1))
        assertEquals(4_000L, retry.delayFor(2))
        assertEquals(32_000L, retry.delayFor(5))
        // Capped, so a refusal that is never going to clear is not asked
        // about every second all day against a fixed daily quota.
        assertEquals(60_000L, retry.delayFor(6))
        assertEquals(60_000L, retry.delayFor(40))
    }

    @Test
    fun `the first wait is never zero, so nothing can spin`() {
        val retry = ListenerRetry()
        assertTrue(retry.delayFor(0) > 0L)
        assertTrue(retry.delayFor(-5) > 0L)
    }

    // --- recovery --------------------------------------------------------------

    @Test
    fun `a listener that fails once is attached again and carries on`() = runTest {
        var attachments = 0
        val listener = flow {
            attachments += 1
            emit(listOf("first"))
            if (attachments == 1) throw IOException("the connection went")
            emit(listOf("second"))
        }

        val seen = mutableListOf<List<String>>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            listener.retryingListener(fast).toList(seen)
        }
        advanceTimeBy(200)
        runCurrent()

        assertEquals("the listener has to come back on its own", 2, attachments)
        assertEquals(listOf(listOf("first"), listOf("first"), listOf("second")), seen)
        job.cancel()
    }

    @Test
    fun `every failure is announced before the wait, with the wait it will use`() = runTest {
        val announced = mutableListOf<Pair<Long, Long>>()
        val alwaysFails = flow<List<String>> { throw IOException("refused") }

        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            alwaysFails
                .retryingListener(fast) { _, attempt, wait -> announced += attempt to wait }
                .toList()
        }
        advanceTimeBy(100)
        runCurrent()

        // Nothing is swallowed: the caller hears about each attempt, which is
        // what lets it report, count, and speak up once it stops being a blip.
        assertTrue("at least three failures should have been announced", announced.size >= 3)
        assertEquals(0L to 10L, announced[0])
        assertEquals(1L to 20L, announced[1])
        assertEquals(2L to 40L, announced[2])
        job.cancel()
    }

    @Test
    fun `a permanent failure keeps being retried rather than ending in silence`() = runTest {
        var attachments = 0
        val alwaysFails = flow<List<String>> {
            attachments += 1
            throw IOException("still refused")
        }

        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            alwaysFails.retryingListener(fast).toList()
        }
        advanceTimeBy(500)
        runCurrent()

        assertTrue("a dead listener must not stay dead", attachments > 3)
        assertTrue("and the flow must still be alive", job.isActive)
        job.cancel()
    }

    // --- cancellation ----------------------------------------------------------

    @Test
    fun `a cancelled scope leaves no retry running`() = runTest {
        var attachments = 0
        val alwaysFails = flow<List<String>> {
            attachments += 1
            throw IOException("refused")
        }

        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            alwaysFails.retryingListener(fast).toList()
        }
        advanceTimeBy(50)
        runCurrent()
        val whenCancelled = attachments

        job.cancel()
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()

        assertFalse(job.isActive)
        assertTrue("no child survives it", job.children.none { it.isActive })
        assertEquals(
            "nothing may attach again after the scope is cancelled",
            whenCancelled,
            attachments
        )
    }

    @Test
    fun `cancellation is not a failure to retry`() = runTest {
        var announced = 0
        val cancelled = flow<List<String>> { throw CancellationException("gone") }

        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            runCatching { cancelled.retryingListener(fast) { _, _, _ -> announced += 1 }.toList() }
        }
        advanceTimeBy(200)
        runCurrent()

        assertEquals("a cancellation must end the flow, not restart it", 0, announced)
        job.cancel()
    }

    // --- the streams this operator is shared by --------------------------------

    @Test
    fun `a healthy stream is passed through untouched`() = runTest {
        // Products, stock and requirements all run through this operator now.
        // A stream that never fails must behave exactly as it did before it
        // existed: same values, same order, and it still completes.
        val products = flowOf(listOf("SIE1000"), listOf("SIE1000", "SIE9000"))
        assertEquals(
            listOf(listOf("SIE1000"), listOf("SIE1000", "SIE9000")),
            products.retryingListener(fast).toList()
        )
    }

    @Test
    fun `an empty stream stays empty rather than being retried for ever`() = runTest {
        // A Worker's products list is `flowOf(emptyList())` and completes at
        // once. Completing is not failing, so it must not be re-collected.
        var attachments = 0
        val empty = flow {
            attachments += 1
            emit(emptyList<String>())
        }
        assertEquals(listOf(emptyList<String>()), empty.retryingListener(fast).toList())
        assertEquals(1, attachments)
    }

    @Test
    fun `values already delivered are kept when a later attachment fails`() = runTest {
        // What the screen keeps is what it last read — a list that failed to
        // refresh is not an empty list, and nothing here substitutes one.
        val stock = flow {
            emit(listOf("motor"))
            delay(5)
            throw IOException("dropped")
        }

        val seen = mutableListOf<List<String>>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            stock.retryingListener(fast).toList(seen)
        }
        advanceTimeBy(40)
        runCurrent()

        assertTrue(seen.isNotEmpty())
        assertTrue("no empty list is ever substituted", seen.none { it.isEmpty() })
        job.cancel()
    }
}
