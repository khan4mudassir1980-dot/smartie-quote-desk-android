package `in`.smartie.quotedesk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FriendlyMessagesTest {

    @Test
    fun `sign-in failures read the way the PWA words them`() {
        assertEquals(
            "That email and password do not match an account.",
            FriendlyMessages.forCode("auth/invalid-credential")
        )
        assertEquals(
            "This account has been switched off. Ask your administrator.",
            FriendlyMessages.forCode("auth/user-disabled")
        )
        assertEquals(
            "Too many attempts. Wait a few minutes and try again.",
            FriendlyMessages.forCode("auth/too-many-requests")
        )
        assertEquals(
            "No connection. Check the internet and try again.",
            FriendlyMessages.forCode("auth/network-request-failed")
        )
        assertEquals("Sign-in was cancelled.", FriendlyMessages.forCode("auth/popup-closed-by-user"))
    }

    @Test
    fun `the finalise gate's timeout says what a timed-out transaction says`() {
        // `QuoteFinaliser` keeps its own copy because it must stay pure; this
        // keeps the two equal.
        assertEquals(
            FriendlyMessages.forCode("deadline-exceeded"),
            `in`.smartie.quotedesk.ui.products.QuoteFinaliser.TIMED_OUT
        )
    }

    @Test
    fun `a collision explains how to link the two sign-in methods`() {
        val message = FriendlyMessages.forCode("auth/account-exists-with-different-credential")
        assertTrue(message.contains("password"))
        assertTrue(message.contains("linked"))
    }

    @Test
    fun `firestore failures are translated too`() {
        assertEquals(
            "Your account is not allowed to do that.",
            FriendlyMessages.forCode("permission-denied")
        )
        assertEquals(
            "Someone else changed this at the same time. Open it again and retry.",
            FriendlyMessages.forCode("aborted")
        )
    }

    @Test
    fun `an unknown code falls back to the raw message without the Firebase prefix`() {
        assertEquals("Disk full", FriendlyMessages.forCode("weird/code", "Firebase: Disk full"))
        assertEquals(FriendlyMessages.GENERIC, FriendlyMessages.forCode(null, null))
        assertEquals(FriendlyMessages.GENERIC, FriendlyMessages.forCode("", "   "))
    }

    @Test
    fun `errors raised by signing out are not shown to the user`() {
        assertTrue(AppError("x", code = "permission-denied").isBenign)
        assertTrue(AppError("x", code = "cancelled").isBenign)
        assertTrue(AppError("x", code = "unauthenticated").isBenign)
        assertTrue(!AppError("x", code = "unavailable").isBenign)
    }
}
