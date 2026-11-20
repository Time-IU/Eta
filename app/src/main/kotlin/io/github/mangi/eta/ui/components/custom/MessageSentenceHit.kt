package io.github.mangi.eta.ui.components.custom

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange

internal val LocalBeginMessageSelection = compositionLocalOf<(() -> Unit)?> { null }
internal val LocalMessageSentenceHits = compositionLocalOf<MessageSentenceHits?> { null }

internal class MessageTextHit {
    var layout: TextLayoutResult? = null
    var coordinates: LayoutCoordinates? = null
}

internal data class SentenceHitRequest(val texts: List<String>, val index: Int, val range: TextRange)

/** Layout-order registry; no source/Markdown offset guessing and no search for the first matching sentence. */
internal class MessageSentenceHits {
    val nodes = mutableSetOf<MessageTextHit>()
    var request: SentenceHitRequest? = null
    // Current inline registry state, scoped to this body's lifetime (also used by anchor contracts).
    var activeSelection: androidx.compose.foundation.text.selection.SelectionState? = null
    fun hit(node: MessageTextHit, position: Offset): Boolean {
        val layout = node.layout ?: return false
        if (layout.layoutInput.text.isEmpty()) return false
        val line = layout.getLineForVerticalPosition(position.y)
        // Padding/outside glyph runs must not turn into a clamped start/end-of-paragraph hit.
        if (position.y < layout.getLineTop(line) || position.y > layout.getLineBottom(line) ||
            position.x < layout.getLineLeft(line) || position.x > layout.getLineRight(line)) return false
        val text = layout.layoutInput.text
        val caret = layout.getOffsetForPosition(position).coerceIn(0, text.length)
        // getOffsetForPosition returns an insertion boundary. The right half of a punctuation glyph
        // still belongs to that glyph's sentence, not the next sentence's first insertion position.
        val offset = if (caret > 0 && layout.getBoundingBox(caret - 1).contains(position)) caret - 1
            else caret.coerceAtMost(text.lastIndex)
        // Links and inline math retain their own click/long-press/overflow gestures.
        if (text.getLinkAnnotations(offset, offset + 1).isNotEmpty() ||
            text.getStringAnnotations("androidx.compose.foundation.text.inlineContent", offset, offset + 1).isNotEmpty()) return false
        val ordered = nodes.filter { it.layout != null && it.coordinates?.isAttached == true }
            .sortedWith { a, b ->
                val ac = a.coordinates!!; val bc = b.coordinates!!
                val ap = ac.positionInRoot(); val bp = bc.positionInRoot()
                MessageSelectionLayoutOrder.compare(ap.x, ap.y, ac.size.width.toFloat(), ac.size.height.toFloat(),
                    bp.x, bp.y, bc.size.width.toFloat(), bc.size.height.toFloat())
            }
        val index = ordered.indexOf(node)
        if (index < 0) return false
        val range = SentenceSelection.range(text.text, offset)
        if (range[0] == range[1]) return false
        request = SentenceHitRequest(ordered.map { it.layout!!.layoutInput.text.text }, index, TextRange(range[0], range[1]))
        return true
    }
}

internal data class MessageSentenceBinding(val modifier: Modifier, val onTextLayout: (TextLayoutResult) -> Unit)

/** Observes Initial pass without stealing down/move/single-up from list, links, or the unified long-press menu. */
@Composable
internal fun rememberMessageSentenceBinding(allowDoubleTap: Boolean = true): MessageSentenceBinding {
    val hits = LocalMessageSentenceHits.current ?: return MessageSentenceBinding(Modifier) {}
    val begin = rememberUpdatedState(LocalBeginMessageSelection.current)
    val node = remember(hits) { MessageTextHit() }
    DisposableEffect(hits, node) {
        hits.nodes.add(node)
        onDispose { hits.nodes.remove(node) }
    }
    val modifier = Modifier.onGloballyPositioned { node.coordinates = it }
        .then(if (!allowDoubleTap || begin.value == null) Modifier else Modifier.pointerInput(hits, node) {
            var previousUp = Long.MIN_VALUE
            var previousPosition = Offset.Unspecified
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val gap = down.uptimeMillis - previousUp
                val second = previousUp != Long.MIN_VALUE &&
                    gap in viewConfiguration.doubleTapMinTimeMillis..viewConfiguration.doubleTapTimeoutMillis &&
                    previousPosition.isSpecified && (down.position - previousPosition).getDistance() <= viewConfiguration.touchSlop * 2
                var valid = true
                var upTime = down.uptimeMillis
                var upPosition = down.position
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null || event.changes.size != 1) { valid = false; break }
                    if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop ||
                        change.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis) valid = false
                    if (!change.pressed) {
                        upTime = change.uptimeMillis
                        upPosition = change.position
                        if (valid && second && hits.hit(node, upPosition)) {
                            change.consume()
                            begin.value?.invoke()
                        }
                        break
                    }
                }
                previousUp = if (valid && !second) upTime else Long.MIN_VALUE
                previousPosition = upPosition
            }
        })
    return MessageSentenceBinding(modifier) { node.layout = it }
}
