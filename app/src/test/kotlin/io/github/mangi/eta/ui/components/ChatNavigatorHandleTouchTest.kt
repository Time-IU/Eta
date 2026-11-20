package io.github.mangi.eta.ui.components

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import top.yukonga.miuix.kmp.basic.Scaffold
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.AgentAppTheme
import io.github.mangi.eta.ui.onboarding.GestureCoach
import io.github.mangi.eta.ui.onboarding.GestureCoachScript
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.time.Duration

/** Real Compose hit testing / Android MotionEvents, not semantics-only OnClick invocation. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w411dp-h896dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ChatNavigatorHandleTouchTest {
    @Test fun tapReachesTargetThroughEveryCoachStepInBothStyles() = eachStyle { h ->
        for (step in GestureCoachScript.steps) {
            h.coach.value = step
            h.advance()
            val before = h.opens
            h.tapHandle()
            assertEquals(before + 1, h.opens)
            // Tapping the target must not be implemented as 'dismiss all onboarding'.
            assertEquals(0, h.skips)
        }
    }

    @Test fun upwardSwipeThroughCoachUsesLatestCallbackAfterRecomposition() = eachStyle { h ->
        h.coach.value = GestureCoachScript.steps.last()
        h.advance()
        h.callbackVersion.value = 2
        h.advance()
        h.gesture(-80f)
        assertEquals(0, h.oldCallbackCalls)
        assertEquals(1, h.opens)
        assertEquals(0, h.skips)
    }

    @Test fun downwardAndCancelledDragsDoNotOpenOrClick() = eachStyle { h ->
        h.gesture(80f)
        h.gesture(-80f, cancel = true)
        assertEquals(0, h.opens)
    }

    @Test fun guttersPassThroughWithoutOpeningNavigation() = eachStyle { h ->
        h.coach.value = GestureCoachScript.steps.last()
        h.advance()
        val bounds = h.handle().boundsInRoot
        assertEquals(h.dp(48f), bounds.width, 1f)
        assertEquals(h.dp(48f), bounds.height, 1f)
        h.tap(h.dp(20f), bounds.center.y)
        assertEquals(0, h.opens)
        assertEquals(1, h.backgroundClicks)
    }

    @Test fun coachSkipButtonAndSystemBackBothWorkWithoutOpeningNavigation() = eachStyle { h ->
        h.coach.value = GestureCoachScript.steps.last()
        h.advance()
        val skip = h.nodes().first { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == "以后再说" }
        }.boundsInRoot.center
        h.tap(skip.x, skip.y)
        assertEquals(1, h.skips)
        h.coach.value = GestureCoachScript.steps.first()
        h.advance()
        h.activity.onBackPressedDispatcher.onBackPressed()
        h.advance()
        assertEquals(2, h.skips)
        assertEquals(0, h.opens)
    }

    @Test fun fullscreenInterceptorIsANegativeControl() = eachStyle { h ->
        h.blockHandle.value = true
        h.advance()
        h.tapHandle()
        assertEquals(0, h.opens)
        assertEquals(1, h.backgroundClicks)
    }

    private fun eachStyle(test: (Harness) -> Unit) {
        for (style in listOf(AppearanceUiStyle.COUI, AppearanceUiStyle.MIUIX)) {
            val h = Harness(style)
            try { test(h) } finally { h.close() }
        }
    }

    private class Harness(style: AppearanceUiStyle) {
        private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        private val clock = BroadcastFrameClock()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + clock)
        private val recomposer = Recomposer(scope.coroutineContext)
        val coach = mutableStateOf<GestureCoachScript.Step?>(null)
        val callbackVersion = mutableStateOf(1)
        val blockHandle = mutableStateOf(false)
        var opens = 0
        var oldCallbackCalls = 0
        var skips = 0
        var backgroundClicks = 0

        init {
            scope.launch { recomposer.runRecomposeAndApplyChanges() }
            val view = ComposeView(activity).apply {
                setParentCompositionContext(recomposer)
                setContent {
                    AgentAppTheme(appearance = AppearanceSettings(uiStyle = style), applyInterfaceScale = true) {
                        val capturedVersion = callbackVersion.value
                        Box(Modifier.fillMaxSize()) {
                            Scaffold(
                                bottomBar = {
                                    Box(Modifier.zIndex(1f)) {
                                        ChatNavigatorHandle(onOpen = {
                                            if (capturedVersion == 1) oldCallbackCalls++
                                            opens++
                                        })
                                    }
                                },
                            ) { _ ->
                                // Production chat deliberately fills behind translucent BottomBar.
                                Box(Modifier.fillMaxSize().clickable { backgroundClicks++ })
                            }
                            if (blockHandle.value) {
                                Box(Modifier.fillMaxSize().clickable { backgroundClicks++ })
                            }
                            coach.value?.let { step ->
                                GestureCoach(step = step, onSkip = { skips++; coach.value = null })
                            }
                        }
                    }
                }
            }
            activity.setContentView(view)
            advance()
        }

        fun dp(value: Float) = value * activity.resources.displayMetrics.density

        fun advance() {
            repeat(6) {
                Snapshot.sendApplyNotifications()
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
                clock.sendFrame(SystemClock.uptimeMillis() * 1_000_000)
                val decor = activity.window.decorView
                fun force(view: View) {
                    view.forceLayout()
                    if (view is ViewGroup) repeat(view.childCount) { force(view.getChildAt(it)) }
                }
                force(decor)
                decor.measure(View.MeasureSpec.makeMeasureSpec(dp(411f).toInt(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(dp(896f).toInt(), View.MeasureSpec.EXACTLY))
                decor.layout(0, 0, decor.measuredWidth, decor.measuredHeight)
            }
        }

        private fun root(view: View = activity.window.decorView): ViewRootForTest? {
            if (view is ViewRootForTest) return view
            if (view is ViewGroup) repeat(view.childCount) { root(view.getChildAt(it))?.let { return it } }
            return null
        }

        fun nodes(): List<SemanticsNode> {
            val result = mutableListOf<SemanticsNode>()
            fun visit(node: SemanticsNode) { result += node; node.children.forEach(::visit) }
            visit(requireNotNull(root()).semanticsOwner.unmergedRootSemanticsNode)
            return result
        }

        fun handle() = nodes().first {
            it.config.getOrNull(SemanticsProperties.TestTag) == "chat-navigator-handle"
        }

        fun tapHandle() {
            val center = handle().boundsInRoot.center
            tap(center.x, center.y)
        }

        fun tap(x: Float, y: Float) = touch(x, y, 0f, false)
        fun gesture(deltaDp: Float, cancel: Boolean = false) {
            val center = handle().boundsInRoot.center
            touch(center.x, center.y, dp(deltaDp), cancel)
        }

        private fun touch(x: Float, y: Float, delta: Float, cancel: Boolean) {
            val view = requireNotNull(root()).view
            val downTime = SystemClock.uptimeMillis()
            fun send(action: Int, currentY: Float) {
                val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, currentY, 0)
                try { view.dispatchTouchEvent(event) } finally { event.recycle() }
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            }
            send(MotionEvent.ACTION_DOWN, y)
            if (delta != 0f) repeat(10) { send(MotionEvent.ACTION_MOVE, y + delta * (it + 1) / 10) }
            send(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, y + delta)
            advance()
        }

        fun close() {
            recomposer.cancel()
            scope.cancel()
            controller.pause().stop().destroy()
        }
    }
}
