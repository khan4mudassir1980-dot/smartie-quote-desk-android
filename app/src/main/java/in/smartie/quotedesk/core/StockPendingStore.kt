package `in`.smartie.quotedesk.core

import kotlinx.coroutines.flow.Flow

/**
 * Where uncommitted `+`/`−` counts live between app launches.
 *
 * An interface only so the stock view model can be unit-tested without
 * Android; [DevicePreferences] is the one real implementation.
 */
interface StockPendingStore {
    val pending: Flow<Map<String, Double>>

    suspend fun setPending(pending: Map<String, Double>)
}
