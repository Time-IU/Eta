package io.github.mangi.eta.agent.runtime
import android.app.Application
import android.app.Notification
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[34])
class LiveUpdateContractTest {
 @Test fun unsupportedApiKeepsOrdinaryNotificationAndDoesNotFabricatePromotion() {
  val b=Notification.Builder(RuntimeEnvironment.getApplication(),"test").setContentTitle("test").setOngoing(true)
  LiveUpdateContract.request(b,"test",true)
  val n=b.build();assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
  assertFalse(n.extras.getBoolean("android.requestPromotedOngoing",false))
 }
 @Test fun disabledPolicyDoesNotRequestPromotion() {
  val b=Notification.Builder(RuntimeEnvironment.getApplication(),"test").setContentTitle("test")
  LiveUpdateContract.request(b,"test",false)
  assertFalse(b.build().extras.getBoolean("android.requestPromotedOngoing",false))
 }
}
