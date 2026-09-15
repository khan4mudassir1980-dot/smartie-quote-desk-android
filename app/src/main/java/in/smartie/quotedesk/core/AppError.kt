package `in`.smartie.quotedesk.core

import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.firestore.FirebaseFirestoreException
import java.util.concurrent.CancellationException

/**
 * Plain-English failure messages, ported from the PWA's `friendlyAuthError`
 * (`index.html:4866-4880`) so both apps say the same thing for the same
 * problem.
 */
object FriendlyMessages {

    const val GENERIC = "Something went wrong. Please try again."

    fun forCode(code: String?, fallback: String? = null): String {
        val normalised = code.orEmpty().lowercase()
        return when {
            normalised.contains("invalid-credential") ||
                normalised.contains("wrong-password") ||
                normalised.contains("user-not-found") ->
                "That email and password do not match an account."

            normalised.contains("invalid-email") -> "That does not look like an email address."
            normalised.contains("missing-password") -> "Enter your password."
            normalised.contains("user-disabled") ->
                "This account has been switched off. Ask your administrator."
            normalised.contains("too-many-requests") ->
                "Too many attempts. Wait a few minutes and try again."
            normalised.contains("network") || normalised.contains("unavailable") ->
                "No connection. Check the internet and try again."
            // Firebase reports a cancelled Google sign-in as
            // popup-closed-by-user; Credential Manager says "cancelled".
            normalised.contains("cancel") ||
                normalised.contains("popup-closed") ||
                normalised.contains("popup-blocked") -> "Sign-in was cancelled."
            normalised.contains("account-exists-with-different-credential") ->
                "This email already signs in with a password. Sign in with the password once, " +
                    "then Google can be linked to the same account."
            normalised.contains("credential-already-in-use") ->
                "That Google account is already linked to another sign-in."
            normalised.contains("permission-denied") -> "Your account is not allowed to do that."
            normalised.contains("unauthenticated") -> "Please sign in again."
            normalised.contains("not-found") -> "That record no longer exists."
            normalised.contains("aborted") || normalised.contains("failed-precondition") ->
                "Someone else changed this at the same time. Open it again and retry."
            normalised.contains("deadline-exceeded") ->
                "The connection timed out. Please try again."
            else -> fallback?.removePrefix("Firebase: ")?.takeIf { it.isNotBlank() } ?: GENERIC
        }
    }
}

/** A failure already translated for the person using the app. */
data class AppError(
    val message: String,
    val code: String? = null,
    val cause: Throwable? = null
) {
    /**
     * Errors that are the normal consequence of signing out or navigating
     * away. They are reported to the log, never to the user.
     */
    val isBenign: Boolean
        get() = cause is CancellationException ||
            code == "permission-denied" ||
            code == "cancelled" ||
            code == "unauthenticated"
}

fun Throwable.toAppError(): AppError {
    val code = when (this) {
        is FirebaseFirestoreException -> this.code.name.lowercase().replace('_', '-')
        is FirebaseAuthException -> this.errorCode.lowercase()
        is FirebaseException -> message?.substringAfter('(', "")?.substringBefore(')')?.lowercase()
        is CancellationException -> "cancelled"
        else -> null
    }
    return AppError(
        message = FriendlyMessages.forCode(code, message),
        code = code,
        cause = this
    )
}
