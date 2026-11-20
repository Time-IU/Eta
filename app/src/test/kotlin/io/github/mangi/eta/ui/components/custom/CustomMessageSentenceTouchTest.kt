package io.github.mangi.eta.ui.components.custom

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

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.input.TextFieldState
import io.github.mangi.eta.ui.markdown.StaticMarkdown
import io.github.mangi.eta.ui.markdown.MarkdownTone
import top.yukonga.miuix.kmp.basic.Text
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame

/** Real production host, SelectionContainer, Markdown and MotionEvents. Requires supported native Robolectric host. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w411dp-h896dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CustomMessageSentenceTouchTest {
    private fun each(test: (Harness) -> Unit) {
        for (markdown in listOf(false, true)) for (style in listOf(AppearanceUiStyle.COUI, AppearanceUiStyle.MIUIX)) {
            val h = Harness(markdown, style)
            try { test(h) } finally { h.close() }
        }
    }
    @Test fun doubleTapSelectsOnlyHitSentenceInOriginalBody() = each { h ->
        h.source.value = "前面一句。目标第二句话！最后一句。"; h.advance()
        val token = h.retainedBodyToken
        h.doubleTap("第")
        assertTrue(h.selecting)
        assertEquals("目标第二句话！", h.selected)
        assertSame(token, h.retainedBodyToken)
        assertEquals("原有草稿", h.composer.text.toString())
    }
    @Test fun dragAndSingleTapNeverEnterSelection() = each { h ->
        h.singleTap("第"); assertFalse(h.selecting)
        h.drag("第"); assertFalse(h.selecting)
    }
    @Test fun longPressStillOpensUnifiedMenuNotSelection() = each { h ->
        h.longPress("第"); assertFalse(h.selecting)
        assertTrue(h.nodes().any { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { t -> t.text == "选取文字" } })
    }
    @Test fun doneRetainsBodyAndFourActions() = each { h ->
        h.doubleTap("第")
        assertTrue(h.selecting)
        assertEquals(CustomSelectionAction.entries.toSet(), h.selectionActions!!.enabled)
        val token = h.retainedBodyToken
        h.selectionActions!!.perform(CustomSelectionAction.DONE, h.selected); h.advance()
        assertFalse(h.selecting); assertSame(token, h.retainedBodyToken)
    }
    @Test fun quoteKeepsDraftAndDoesNotSend() = each { h ->
        h.source.value = "前句。需要追问！后句。"; h.advance(); h.doubleTap("需")
        assertEquals("需要追问！", h.selected)
        h.selectionActions!!.perform(CustomSelectionAction.QUOTE, h.selected); h.advance()
        assertFalse(h.selecting)
        assertTrue(h.composer.text.toString().contains("原有草稿"))
        assertTrue(h.composer.text.toString().contains("需要追问！"))
        assertFalse(h.composer.text.toString().contains("前句"))
    }
    @Test fun sameIdContentUpdateKeepsRenderSessionButUsesLatestText() = each { h ->
        val token = h.retainedBodyToken
        h.source.value = "新版第一句。點擊這裡！結束。"; h.advance()
        assertSame(token, h.retainedBodyToken)
        h.doubleTap("這"); assertEquals("點擊這裡！", h.selected)
    }
    @Test fun duplicateMarkdownParagraphsUseExactLayoutNode() {
        val h = Harness(true, AppearanceUiStyle.COUI)
        try {
            h.source.value = "重复。目标句！\n\n重复。目标句！"; h.advance()
            h.doubleTap("目标", last = true)
            assertEquals("目标句！", h.selected)
            assertTrue(h.selecting)
        } finally { h.close() }
    }
    @Test fun markdownCodeAndOrderedListDoNotShiftSentenceOffsets() {
        val h = Harness(true, AppearanceUiStyle.MIUIX)
        try {
            h.source.value = "```text\ncode text\n```\n\n1. 前句。目標句！後句。"; h.advance()
            h.doubleTap("目標"); assertEquals("目標句！", h.selected)
        } finally { h.close() }
    }
    @Test fun linkSingleClickStillOpensUriAndDoubleTapDoesNotSelectLink() {
        val h = Harness(true, AppearanceUiStyle.COUI)
        try {
            h.source.value = "前句。[网站](https://example.com) 后句。"; h.advance()
            h.singleTap("网站")
            assertEquals(listOf("https://example.com"), h.openedUris)
            h.doubleTap("网站"); assertFalse(h.selecting)
        } finally { h.close() }
    }
    private fun assertFirstSentenceAnchors(h: Harness) {
        val state = requireNotNull(h.sentenceHits?.activeSelection)
        val anchors = Foundation113SelectionAnchors.anchors(state)
        assertEquals(4, anchors.size)
        assertEquals(anchors[0], anchors[2]) // Previous node's tail must NOT own the start handle.
        assertEquals(0L, anchors[1]); assertEquals(4L, anchors[3])
        assertEquals(listOf("目标句！"), state.selectedTexts.map { it.text })
        assertEquals("目标句！", h.selected)
    }
    @Test fun secondParagraphFirstSentenceOwnsBothRealAnchors() {
        val h = Harness(true, AppearanceUiStyle.COUI)
        try {
            h.source.value = "前段。\n\n目标句！后句。"; h.advance()
            h.doubleTap("目标"); assertFirstSentenceAnchors(h)
        } finally { h.close() }
    }
    @Test fun orderedListFirstSentenceOwnsBothRealAnchors() {
        val h = Harness(true, AppearanceUiStyle.MIUIX)
        try {
            h.source.value = "前段。\n\n1. 目标句！后句。"; h.advance()
            h.doubleTap("目标"); assertFirstSentenceAnchors(h)
        } finally { h.close() }
    }
    @Test fun firstSentenceAfterCodeOwnsBothRealAnchors() {
        val h = Harness(true, AppearanceUiStyle.COUI)
        try {
            h.source.value = "```text\ncode text\n```\n\n目标句！后句。"; h.advance()
            h.doubleTap("目标"); assertFirstSentenceAnchors(h)
        } finally { h.close() }
    }
    @Test fun disabledHostCannotDoubleTapIntoSelection() {
        val h = Harness(false, AppearanceUiStyle.COUI, enabled = false)
        try { h.doubleTap("第"); assertFalse(h.selecting) } finally { h.close() }
    }
    private class Harness(val markdown: Boolean, style: AppearanceUiStyle, val enabled: Boolean = true) {
        private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity = controller.get()
        private val clock = BroadcastFrameClock()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + clock)
        private val recomposer = Recomposer(scope.coroutineContext)
        val source = mutableStateOf("前面一句。目标 **第二句话！**最后一句。")
        var selecting = false
        var selected = ""
        var contentInstances = 0
        var retainedBodyToken: Any? = null
        var selectionActions: CustomSelectionActions? = null
        var sentenceHits: MessageSentenceHits? = null
        val openedUris = mutableListOf<String>()
        val composer = androidx.compose.foundation.text.input.TextFieldState("原有草稿")

        init {
            scope.launch { recomposer.runRecomposeAndApplyChanges() }
            val view = ComposeView(activity).apply {
                setParentCompositionContext(recomposer)
                setContent {
                    AgentAppTheme(appearance = AppearanceSettings(uiStyle = style), applyInterfaceScale = true) {
                        CompositionLocalProvider(
                            LocalCustomComposer provides composer,
                            androidx.compose.ui.platform.LocalUriHandler provides object : androidx.compose.ui.platform.UriHandler {
                                override fun openUri(uri: String) { openedUris += uri }
                            },
                        ) {
                            CustomMessageActionsHost(
                                messageId = "fixture", text = source.value, enabled = enabled,
                                onDelete = null, onEdit = null, onRegenerate = null, onBranch = null,
                            ) { active, openMenu, copied ->
                                selecting = active
                                selectionActions = LocalCustomSelectionActions.current
                                Column(Modifier.fillMaxSize().combinedClickable(onClick = {}, onLongClick = openMenu)) {
                                    CustomMessageSelection(active, { selected = it; copied(it) }) {
                                        sentenceHits = LocalMessageSentenceHits.current
                                        val token = remember { contentInstances++; Any() }
                                        retainedBodyToken = token
                                        if (markdown) {
                                            StaticMarkdown(content = source.value, tone = MarkdownTone.Answer)
                                        } else {
                                            val sentence = rememberMessageSentenceBinding()
                                            Text(text = source.value, modifier = sentence.modifier,
                                                onTextLayout = sentence.onTextLayout)
                                        }
                                    }
                                }
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
                Thread.sleep(15) // allow the production background Markdown parser to publish
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

        fun textPoint(needle: String, last: Boolean = false): androidx.compose.ui.geometry.Offset {
            val node = nodes().last { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> needle in text.text } }
            val text = node.config.getOrNull(SemanticsProperties.Text)!!.first().text
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            check(node.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult)!!.action!!.invoke(layouts))
            val index = if (last) text.lastIndexOf(needle) else text.indexOf(needle)
            return node.boundsInRoot.topLeft + layouts.first().getBoundingBox(index).center
        }
        fun doubleTap(needle: String, last: Boolean = false) {
            val p = textPoint(needle, last)
            touch(p.x, p.y, 0f, false)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(64))
            touch(p.x, p.y, 0f, false)
        }
        fun drag(needle: String) {
            val p = textPoint(needle)
            touch(p.x, p.y, dp(80f), false)
        }
        fun singleTap(needle: String) {
            val p = textPoint(needle); touch(p.x, p.y, 0f, false)
        }
        fun longPress(needle: String) {
            val p = textPoint(needle)
            val view = requireNotNull(root()).view
            val t = SystemClock.uptimeMillis()
            view.dispatchTouchEvent(MotionEvent.obtain(t,t,MotionEvent.ACTION_DOWN,p.x,p.y,0))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))
            view.dispatchTouchEvent(MotionEvent.obtain(t,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,p.x,p.y,0))
            advance()
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
