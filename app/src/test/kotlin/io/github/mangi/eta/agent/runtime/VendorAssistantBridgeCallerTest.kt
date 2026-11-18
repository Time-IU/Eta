package io.github.mangi.eta.agent.runtime

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class VendorAssistantBridgeCallerTest {
    private val pm get() = RuntimeEnvironment.getApplication().packageManager
    private val uid = 10123

    @Test fun authenticatesPlatformSignedSystemAssistantAndItsUpdates() {
        install("android", 1000, ApplicationInfo.FLAG_SYSTEM, "aabb")
        for (flags in listOf(ApplicationInfo.FLAG_SYSTEM, ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) {
            install("com.heytap.speechassist", uid, flags, "aabb")
            assertEquals("breeno", VendorAssistantBridgeCaller.source(pm, uid, 10234))
        }
    }

    @Test fun vendorAnchorSupportsNonPlatformSigningButMustItselfBeSystem() {
        install("com.miui.voiceassist", uid, ApplicationInfo.FLAG_SYSTEM, "aabb")
        install("com.miui.securitycenter", 10124, 0, "aabb")
        assertNull(VendorAssistantBridgeCaller.source(pm, uid, 10234))
        install("com.miui.securitycenter", 10124, ApplicationInfo.FLAG_SYSTEM, "aabb")
        assertEquals("xiaoai", VendorAssistantBridgeCaller.source(pm, uid, 10234))
    }

    @Test fun sameNameUserInstallAndDifferentCertificateCannotAuthenticate() {
        install("android", 1000, ApplicationInfo.FLAG_SYSTEM, "aabb")
        install("com.heytap.speechassist", uid, 0, "aabb")
        assertNull(VendorAssistantBridgeCaller.source(pm, uid, 10234))
        install("com.heytap.speechassist", uid, ApplicationInfo.FLAG_SYSTEM, "ccdd")
        assertNull(VendorAssistantBridgeCaller.source(pm, uid, 10234))
    }

    @Test fun dedicatedVendorSigningRequiresFactoryProofAndStillChecksUid() {
        install("com.heytap.speechassist", uid, ApplicationInfo.FLAG_SYSTEM, "ccdd")
        assertNull(VendorAssistantBridgeCaller.source(pm, uid, 10234) { _, _, _ -> false })
        assertEquals("breeno", VendorAssistantBridgeCaller.source(pm, uid, 10234) { _, pkg, app ->
            pkg == "com.heytap.speechassist" && app.uid == uid
        })
        assertNull(VendorAssistantBridgeCaller.source(pm, 10125, 10234) { _, _, _ -> true })
    }

    @Test fun rejectsForgedUidPackagesCrossUserAndUnsignedPackages() {
        install("android", 1000, ApplicationInfo.FLAG_SYSTEM, "aabb")
        install("com.heytap.speechassist", uid, ApplicationInfo.FLAG_SYSTEM, "aabb")
        shadowOf(pm).setPackagesForUid(10125, "com.heytap.speechassist")
        assertNull(VendorAssistantBridgeCaller.source(pm, 10125, 10234))
        assertNull(VendorAssistantBridgeCaller.source(pm, uid, 110234))
        assertNull(VendorAssistantBridgeCaller.source(pm, -1, 10234))
        install("com.heytap.speechassist", uid, ApplicationInfo.FLAG_SYSTEM, null)
        assertNull(VendorAssistantBridgeCaller.source(pm, uid, 10234))
    }

    @Suppress("DEPRECATION")
    private fun install(name: String, appUid: Int, flags: Int, cert: String?) {
        shadowOf(pm).installPackage(PackageInfo().apply {
            packageName = name
            applicationInfo = ApplicationInfo().apply {
                packageName = name
                uid = appUid
                this.flags = flags
            }
            if (cert != null) signatures = arrayOf(Signature(cert))
        })
        shadowOf(pm).setPackagesForUid(appUid, name)
    }
}
