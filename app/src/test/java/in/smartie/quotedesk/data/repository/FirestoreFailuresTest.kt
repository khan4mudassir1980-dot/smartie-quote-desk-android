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
    }
}
