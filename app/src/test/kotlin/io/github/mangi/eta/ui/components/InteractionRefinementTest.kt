package io.github.mangi.eta.ui.components
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale
class InteractionRefinementTest {
 @Test fun closedHistoryAcceptsOnlyLeftEdgeAndNotSelectionOrWorkspace() {
  assertTrue(ChatGesturePolicy.canStart(12f,24f,false,false,false,true))
  assertFalse(ChatGesturePolicy.canStart(300f,24f,false,false,false,true))
  assertFalse(ChatGesturePolicy.canStart(12f,24f,false,true,false,true))
  assertFalse(ChatGesturePolicy.canStart(12f,24f,false,false,true,true))
  assertFalse(ChatGesturePolicy.acceptsInitialDelta(-8f,false))
 }
 @Test fun openHistoryCanCloseWithoutAnEdgeOrigin() {
  assertTrue(ChatGesturePolicy.canStart(300f,24f,true,false,false,true))
  assertTrue(ChatGesturePolicy.acceptsInitialDelta(-8f,true))
  assertFalse(ChatGesturePolicy.settleOpen(30f,100f))
  assertTrue(ChatGesturePolicy.settleOpen(60f,100f))
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
