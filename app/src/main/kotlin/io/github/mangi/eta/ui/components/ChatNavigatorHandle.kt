package io.github.mangi.eta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Both styles share this bounded tap / upward-swipe target above the composer. */
@Composable
internal fun ChatNavigatorHandle(onOpen: () -> Unit) {
    val currentOnOpen by rememberUpdatedState(onOpen)
    var requestVersion by remember { mutableIntStateOf(0) }
    var handledVersion by remember { mutableIntStateOf(0) }
    LaunchedEffect(requestVersion) {
        while (handledVersion < requestVersion) {
            handledVersion++
            currentOnOpen()
        }
    }
    val requestOpen = { requestVersion++; Unit }
    val currentRequestOpen by rememberUpdatedState(requestOpen)
    val triggerPx = with(LocalDensity.current) { 16.dp.toPx() }
    val label = stringResource(R.string.conversation_navigator_title)
    // The full-width parent ONLY positions the handle. Never attach input modifiers to it.
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                // A real 48dp square, not a 14dp-high target with invisible expanded hit slop
                // into the composer. Side gutters remain available to the chat's own gestures.
                .size(48.dp)
                .testTag("chat-navigator-handle")
                .semantics {
                    contentDescription = label
                    role = Role.Button
                    onClick { currentRequestOpen(); true }
                }
                // One recognizer owns tap AND swipe: no clickable/drag cancellation race.
                .pointerInput(triggerPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = true)
                        val gesture = NavigatorHandleGesture(triggerPx, viewConfiguration.touchSlop)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null || change.isConsumed || event.changes.size != 1) {
                                gesture.cancel()
                                break
                            }
                            val delta = change.positionChange()
                            gesture.add(delta.x, delta.y)
                            if (!change.pressed) {
                                if (gesture.finish()) {
                                    change.consume()
                                    currentRequestOpen()
                                }
                                break
                            }
                            if (gesture.ownsVerticalDrag) change.consume()
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .width(40.dp)
                    .height(4.dp)
                    .background(
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.36f),
                        shape = RoundedCornerShape(2.dp),
                    ),
            )
        }
    }
}
