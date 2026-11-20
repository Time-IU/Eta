package io.github.mangi.eta.config

import kotlinx.coroutines.CancellationException

/** Remote mirror failures are state, not failure of the already committed local settings.
 * Cancellation remains structured cancellation and may never be reported as saved/confirmed.
 */
internal object CanvasSettingsErrorBoundary {
    suspend fun <T> mirror(failed: T, onFailure: (Exception) -> Unit, block: suspend () -> T): T =
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { onFailure(error); failed }

    /** Shared UI launch boundary: never let a recoverable save/retry error escape launch. */
    suspend fun action(onFailure: suspend (Exception) -> Unit, block: suspend () -> Unit) {
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { onFailure(error) }
    }
}
