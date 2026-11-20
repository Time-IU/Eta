package io.github.mangi.eta.ui.navigation

import org.junit.Assert.*
import org.junit.Test

class OverlayPermissionFallbackTest {
    @Test fun packageSuccessDoesNotLaunchMoreScreens() {
        val attempts = mutableListOf<OverlaySettingsTarget>()
        assertEquals(OverlaySettingsTarget.PACKAGE, launchOverlaySettingsTarget { attempts.add(it); true })
        assertEquals(listOf(OverlaySettingsTarget.PACKAGE), attempts)
    }
    @Test fun rejectedPackageUsesSystemList() {
        val attempts = mutableListOf<OverlaySettingsTarget>()
        assertEquals(OverlaySettingsTarget.LIST, launchOverlaySettingsTarget {
            attempts.add(it); it == OverlaySettingsTarget.LIST
        })
        assertEquals(listOf(OverlaySettingsTarget.PACKAGE, OverlaySettingsTarget.LIST), attempts)
    }
    @Test fun rejectedOverlayPagesUseDetailsLast() {
        val attempts = mutableListOf<OverlaySettingsTarget>()
        assertEquals(OverlaySettingsTarget.APP_DETAILS, launchOverlaySettingsTarget {
            attempts.add(it); it == OverlaySettingsTarget.APP_DETAILS
        })
        assertEquals(OverlaySettingsTarget.entries.toList(), attempts)
    }
    @Test fun noHandlerIsNotReportedAsSuccessfulLaunch() {
        val attempts = mutableListOf<OverlaySettingsTarget>()
        assertNull(launchOverlaySettingsTarget { attempts.add(it); false })
        assertEquals(OverlaySettingsTarget.entries.toList(), attempts)
    }
}
