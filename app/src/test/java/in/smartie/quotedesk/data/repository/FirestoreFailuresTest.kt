package `in`.smartie.quotedesk.data.repository

import com.google.firebase.firestore.FirebaseFirestoreException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Naming the one Firestore failure a person can do something about. */
class FirestoreFailuresTest {

    private fun firestore(code: FirebaseFirestoreException.Code) =
        FirebaseFirestoreException("Quota exceeded", code)

    @Test
    fun `an exhausted quota is recognised`() {
        assertTrue(
            FirestoreFailures.isQuotaExhausted(
                firestore(FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED)
            )
        )
    }

    @Test
    fun `the wording says it passes, rather than inviting somebody to pay`() {
        val message = FirestoreFailures.message(
            firestore(FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED)
        )
        assertEquals(FirestoreFailures.QUOTA_EXHAUSTED, message)
        assertTrue("it has to say when it comes back", message!!.contains("tomorrow"))
    }

    @Test
    fun `a wrapped failure is still recognised`() {
        // A failure raised inside a transaction reaches the caller wrapped.
        val wrapped = IllegalStateException(
            "transaction failed",
            firestore(FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED)
        )
        assertTrue(FirestoreFailures.isQuotaExhausted(wrapped))
    }

    @Test
    fun `a permission refusal is not a quota problem`() {
        assertFalse(
            FirestoreFailures.isQuotaExhausted(
                firestore(FirebaseFirestoreException.Code.PERMISSION_DENIED)
            )
        )
    }

    @Test
    fun `an ordinary failure keeps its own message`() {
        assertNull(FirestoreFailures.message(IOException("no connection")))
    }

    @Test
    fun `nothing at all is not a quota problem`() {
        assertFalse(FirestoreFailures.isQuotaExhausted(null))
        assertNull(FirestoreFailures.message(null))
    }

    @Test
    fun `a cause that points at itself does not spin`() {
        val looping = object : RuntimeException("round and round") {
            override val cause: Throwable get() = this
        }
        assertFalse(FirestoreFailures.isQuotaExhausted(looping))
        assertFalse(FirestoreFailures.isWriteConflict(looping))
        assertFalse(FirestoreFailures.isRefused(looping))
    }

    // --- contention and refusal ------------------------------------------

    @Test
    fun `losing a race is contention, not a mistake`() {
        for (code in listOf(
            FirebaseFirestoreException.Code.ABORTED,
            FirebaseFirestoreException.Code.FAILED_PRECONDITION
        )) {
            assertTrue(code.name, FirestoreFailures.isWriteConflict(firestore(code)))
            assertFalse(code.name, FirestoreFailures.isRefused(firestore(code)))
        }
    }

    @Test
    fun `a conflict is recognised through a transaction's wrapper`() {
        val wrapped = IllegalStateException(
            "Transaction failed",
            firestore(FirebaseFirestoreException.Code.ABORTED)
        )
        assertTrue(FirestoreFailures.isWriteConflict(wrapped))
    }

    @Test
    fun `the conflict wording does not promise a retry will work`() {
        // A purchase write carries `rev = stored.rev + 1`, so retrying by
        // itself would be a second opinion about a document somebody else has
        // just changed. The person looks before trying again.
        assertTrue(FirestoreFailures.WRITE_CONFLICT.contains("check it"))
    }

    @Test
    fun `a rules refusal is refusal and nothing else`() {
        val denied = firestore(FirebaseFirestoreException.Code.PERMISSION_DENIED)
        assertTrue(FirestoreFailures.isRefused(denied))
        assertFalse(FirestoreFailures.isWriteConflict(denied))
        assertFalse(FirestoreFailures.isQuotaExhausted(denied))
    }

    @Test
    fun `the quota message stays the only thing message reports`() {
        // Our Stock reads `message()` and says its own thing about everything
        // else. Widening it here would change that screen's wording without
        // anybody asking for it.
        for (code in listOf(
            FirebaseFirestoreException.Code.ABORTED,
            FirebaseFirestoreException.Code.FAILED_PRECONDITION,
            FirebaseFirestoreException.Code.PERMISSION_DENIED
        )) {
            assertNull(code.name, FirestoreFailures.message(firestore(code)))
        }
    }

    @Test
    fun `an ordinary failure is neither a conflict nor a refusal`() {
        assertFalse(FirestoreFailures.isWriteConflict(IOException("no connection")))
        assertFalse(FirestoreFailures.isRefused(IOException("no connection")))
        assertFalse(FirestoreFailures.isWriteConflict(null))
        assertFalse(FirestoreFailures.isRefused(null))
    }
}
