package io.github.mangi.eta.ui.screens.tasks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Only visible canvas controls participate in equality; unrelated preference writes stay quiet. */
internal data class CanvasSettingsSnapshot(
    val virtualScreenEnabled: Boolean,
    val unifiedCanvasBackgroundEnabled: Boolean,
    val virtualScreenOffEnabled: Boolean,
    val virtualScreenFallbackEnabled: Boolean,
    val virtualScreenAutoRestartApps: Boolean,
    val virtualScreenIdleTimeoutMinutes: Int,
    val virtualScreenFloatWindowEnabled: Boolean,
    val virtualScreenKeepScreenOn: Boolean,
    val virtualScreenFloatEdgeCollapseEnabled: Boolean,
)

internal fun <T, R> Flow<T>.distinctCanvasSettings(project: (T) -> R): Flow<R> =
    map(project).distinctUntilChanged()

/** Composition-owned identity, shared by the screen and the real runtime regression harness. */
@Composable
internal fun <T> rememberCanvasSettingsFlow(factory: () -> Flow<T>): Flow<T> = remember { factory() }
