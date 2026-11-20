package io.github.mangi.eta.ui.screens.tasks

import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest

/** A failed read is never a valid default. Last good data survives failure, retry and lifecycle
 * restarts. Only a successful read can re-enable editing. IO failure waits for explicit retry;
 * the retry token remains collected even when the failed inner cold Flow has completed. */
internal data class CanvasSettingsReadState<T>(
    val snapshot: T? = null,
    val reading: Boolean = true,
    val failed: Boolean = false,
) {
    val canEdit: Boolean get() = snapshot != null && !reading && !failed
}

internal class CanvasSettingsReader<T>(private val source: () -> Flow<T>) {
    private val retryToken = MutableStateFlow(0L)
    private val mutableState = MutableStateFlow(CanvasSettingsReadState<T>())
    val state: StateFlow<CanvasSettingsReadState<T>> = mutableState.asStateFlow()

    fun retry() { retryToken.value = retryToken.value + 1L }

    /** One lifecycle-owned collector; CancellationException and non-IO failures are not swallowed. */
    suspend fun collect() {
        retryToken.collectLatest {
            mutableState.value = mutableState.value.copy(reading = true, failed = false)
            try {
                source().collect { saved ->
                    mutableState.value = CanvasSettingsReadState(snapshot = saved, reading = false)
                }
            } catch (_: IOException) {
                mutableState.value = mutableState.value.copy(reading = false, failed = true)
            }
        }
    }
}
