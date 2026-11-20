package io.github.mangi.eta.ui.navigation

import android.app.Activity
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class OverlayPermissionNavigationTest {
    @Test fun packagePageStopsAfterFirstSuccessfulLaunch() {
        val attempts = mutableListOf<Intent>()
        val result = OverlayPermissionNavigation.launchCandidates("io.example") { attempts.add(it); true }
        assertEquals(OverlaySettingsTarget.PACKAGE, result)
        assertEquals(1, attempts.size)
        assertEquals(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, attempts.single().action)
        assertEquals("package:io.example", attempts.single().dataString)
    }

    @Test fun rejectedPackagePageFallsBackToOverlayListWithoutPackageData() {
        val attempts = mutableListOf<Intent>()
        val result = OverlayPermissionNavigation.launchCandidates("io.example") {
            attempts.add(it); it.data == null
        }
        assertEquals(OverlaySettingsTarget.LIST, result)
        assertEquals(2, attempts.size)
        assertEquals(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, attempts.last().action)
        assertNull(attempts.last().data)
    }

    @Test fun rejectedOverlayScreensFallBackToApplicationDetails() {
        val attempts = mutableListOf<Intent>()
        val result = OverlayPermissionNavigation.launchCandidates("io.example") {
            attempts.add(it); it.action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS
        }
        assertEquals(OverlaySettingsTarget.APP_DETAILS, result)
        assertEquals(3, attempts.size)
        assertEquals("package:io.example", attempts.last().dataString)
    }

    @Test fun noSystemHandlerReturnsFailureNotSuccess() {
        var attempts = 0
        val result = OverlayPermissionNavigation.launchCandidates("io.example") { attempts++; false }
        assertNull(result)
        assertEquals(3, attempts)
    }

    @Test fun applicationContextAddsNewTaskWithoutMutatingCandidate() {
        val application = RuntimeEnvironment.getApplication()
        val candidate = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
        val prepared = OverlayPermissionNavigation.prepareIntent(application, candidate)
        assertNotEquals(0, prepared.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
        assertEquals(0, candidate.flags)
    }

    @Test fun wrappedActivityPreservesItsTask() {
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        try {
            val wrapped = ContextWrapper(ContextWrapper(controller.get()))
            val prepared = OverlayPermissionNavigation.prepareIntent(wrapped, Intent("example"))
            assertEquals(0, prepared.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
        } finally { controller.destroy() }
    }

    @Test fun destroyedActivityRequiresNewTask() {
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        val activity = controller.get()
        controller.destroy()
        val prepared = OverlayPermissionNavigation.prepareIntent(activity, Intent("example"))
        assertNotEquals(0, prepared.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
