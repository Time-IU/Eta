package io.github.mangi.eta.agent.display.dashboard

import android.app.Application
import android.content.Context
import io.github.mangi.eta.testsupport.EtaRobolectricTestRunner
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/** Framework-backed runner. The direct JVM boundary harness reuses the exact same contract tests. */
@RunWith(EtaRobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AgentConfirmGuardEntryTest : ApprovalGuardEntryContract() {
    override val context: Context get() = RuntimeEnvironment.getApplication()
    override fun advanceObservationClock() { ShadowSystemClock.advanceBy(Duration.ofMillis(5)) }
}
