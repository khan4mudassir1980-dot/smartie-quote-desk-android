package `in`.smartie.quotedesk.core

import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch

/**
 * One place errors surface from. Nothing in the app is allowed to crash on a
 * Firebase or network failure; the user sees a message and the app keeps
 * working (audit C2, C3).
 */
class ErrorReporter {

    private val events = MutableSharedFlow<AppError>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val messages: Flow<AppError> = events.asSharedFlow()

    fun report(error: AppError) {
        Log.w(TAG, "${error.code ?: "error"}: ${error.message}", error.cause)
        if (!error.isBenign) events.tryEmit(error)
    }

    fun report(throwable: Throwable) = report(throwable.toAppError())

    /** Catches anything a coroutine throws instead of taking the process down. */
    fun coroutineHandler(): CoroutineExceptionHandler =
        CoroutineExceptionHandler { _, throwable -> report(throwable) }

    private companion object {
        const val TAG = "Smartie"
    }
}

/** Keeps a collector alive when a listener fails, reporting once. */
fun <T> Flow<T>.reportingErrors(reporter: ErrorReporter, fallback: T): Flow<T> =
    catch { throwable ->
        reporter.report(throwable)
        emit(fallback)
    }
