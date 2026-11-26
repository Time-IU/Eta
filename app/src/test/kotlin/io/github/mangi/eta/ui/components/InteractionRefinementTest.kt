package io.github.mangi.eta.ui.components
import org.junit.Assert.*
import io.github.mangi.eta.data.model.DEFAULT_CONVERSATION_PANE_COMMIT_THRESHOLD_PERCENT
import org.junit.Test
import java.util.Locale
class InteractionRefinementTest {
 @Test fun closedHistoryAcceptsOnlyLeftEdgeAndNotSelectionOrWorkspace() {
  assertTrue(ChatGesturePolicy.canStart(12f,24f,false,false,false,true))
  assertTrue(ChatGesturePolicy.canStart(300f,400f,false,false,false,true))
  assertTrue(ChatGesturePolicy.canStart(399f,400f,false,false,false,true))
  assertFalse(ChatGesturePolicy.canStart(401f,400f,false,false,false,true))
  assertFalse(ChatGesturePolicy.canStart(12f,24f,false,true,false,true))
  assertFalse(ChatGesturePolicy.canStart(12f,24f,false,false,true,true))
  assertFalse(ChatGesturePolicy.acceptsInitialDelta(-8f,false))
  assertTrue(ChatGesturePolicy.shouldClaimHorizontal(20f,2f,10f))
  assertFalse(ChatGesturePolicy.shouldClaimHorizontal(4f,20f,10f))
  assertFalse(ChatGesturePolicy.shouldClaimHorizontal(10f,10f,10f))
 }
 @Test fun openHistoryCanCloseWithoutAnEdgeOrigin() {
  assertTrue(ChatGesturePolicy.canStart(300f,24f,true,false,false,true))
  assertTrue(ChatGesturePolicy.acceptsInitialDelta(-8f,true))
  assertFalse(ChatGesturePolicy.settleOpen(4f,100f,5f))
  assertTrue(ChatGesturePolicy.settleOpen(5f,100f,5f))
  assertFalse(ChatGesturePolicy.settleOpen(2f,100f,3f))
  assertTrue(ChatGesturePolicy.settleOpen(3f,100f,3f))
  assertFalse(ChatGesturePolicy.settleOpen(19f,100f,20f))
  assertTrue(ChatGesturePolicy.settleOpen(20f,100f,20f))
  assertTrue(ChatGesturePolicy.isBeyondCommitThreshold(11.5f,100f,false,11.5f))
  assertFalse(ChatGesturePolicy.isBeyondCommitThreshold(11f,100f,false,11.5f))
  assertTrue(ChatGesturePolicy.isBeyondCommitThreshold(88.5f,100f,true,11.5f))
  assertFalse(ChatGesturePolicy.isBeyondCommitThreshold(89f,100f,true,11.5f))
  assertTrue(ChatGesturePolicy.crossedCommitThreshold(2f, 4f, 100f, false, 3f))
  assertFalse(ChatGesturePolicy.crossedCommitThreshold(5f, 4f, 100f, false, 3f))
  assertTrue(ChatGesturePolicy.crossedCommitThreshold(98f, 96f, 100f, true, 3f))
  assertFalse(ChatGesturePolicy.crossedCommitThreshold(93f, 90f, 100f, true, 3f))
  assertEquals(11.5f, DEFAULT_CONVERSATION_PANE_COMMIT_THRESHOLD_PERCENT, 0.001f)
  assertEquals(11.5f, 3f + (20f - 3f) * 3f / 6f, 0.001f)
  assertEquals(20f, ChatGesturePolicy.scaledDragDelta(10f,2f),0.001f)
  assertEquals(10f, ChatGesturePolicy.scaledDragDelta(10f,1f),0.001f)
  assertEquals(7.5f, ChatGesturePolicy.scaledDragDelta(10f,0.75f),0.001f)
 }
 @Test fun conversationNavigatorKeysRemainUniqueForDuplicateMessageHits() {
  val first = conversationNavigatorRowKey(0, "same-message", false, 12)
  val second = conversationNavigatorRowKey(1, "same-message", false, 12)
  val outline = conversationNavigatorRowKey(0, "same-message", true, null)
  assertNotEquals(first, second)
  assertNotEquals(first, outline)
 }
 @Test fun selectionLocksAreScopedAndCannotUnlockAnotherMessage() {
  val l=ChatSelectionLock();val a=Any();val b=Any()
  l.acquire(a);l.acquire(b);l.release(a);assertTrue(l.active);l.release(b);assertFalse(l.active)
 }
 @Test fun numbersAreFormattedWithoutChangingScopeOrUnknowns() {
  assertEquals("81,032,122 tok",usageDisplayValue("81032122 tok",locale=Locale.US))
  assertEquals("402.41K",usageDisplayValue("402410 tok",true,Locale.US))
  assertEquals("86 min 29 s",usageDisplayValue("5189000 ms",locale=Locale.US))
  assertEquals("15.2 s",usageDisplayValue("15200 ms",locale=Locale.US))
  assertEquals("0 tok",usageDisplayValue("0 tok",locale=Locale.US));assertNull(usageDisplayValue(null))
 }
}
