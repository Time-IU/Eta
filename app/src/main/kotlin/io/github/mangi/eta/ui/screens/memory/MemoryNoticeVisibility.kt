package io.github.mangi.eta.ui.screens.memory

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner

/** A navigation-backed notice belongs to the active destination only, not covered retained pages. */
internal fun shouldShowMemoryNotice(lifecycleState: Lifecycle.State, hasNotice: Boolean): Boolean =
    hasNotice && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)

@Composable
internal fun rememberMemoryNoticeVisibility(hasNotice: Boolean): Boolean {
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    return shouldShowMemoryNotice(lifecycleState, hasNotice)
}
