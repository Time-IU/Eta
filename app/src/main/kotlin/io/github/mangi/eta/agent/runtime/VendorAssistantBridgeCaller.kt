package io.github.mangi.eta.agent.runtime

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.UserHandle

/** Resolve identity only from PackageManager and the kernel-supplied sending UID. */
internal object VendorAssistantBridgeCaller {
    private val trustAnchors = mapOf(
        "com.heytap.speechassist" to listOf("android", "com.oplus.aimemory", "com.coloros.assistantscreen"),
        "com.miui.voiceassist" to listOf("android", "com.miui.securitycenter"),
    )

    fun source(
        pm: PackageManager,
        uid: Int,
        serviceUid: Int,
        factoryVerifier: (PackageManager, String, ApplicationInfo) -> Boolean = VendorAssistantBridgeSignatures::matchesFactory,
    ): String? = runCatching {
        if (uid < 0 || UserHandle.getUserHandleForUid(uid) != UserHandle.getUserHandleForUid(serviceUid)) return null
        val packages = pm.getPackagesForUid(uid).orEmpty()
        for ((pkg, anchors) in trustAnchors) {
            if (pkg !in packages) continue
            val app = pm.getApplicationInfo(pkg, 0)
            val signed = anchors.any { anchor ->
                runCatching {
                    pm.getApplicationInfo(anchor, 0).isSystemPackage() &&
                        pm.checkSignatures(pkg, anchor) == PackageManager.SIGNATURE_MATCH
                }.getOrDefault(false)
            }
            VendorAssistantBridgePolicy.source(
                pkg, app.uid == uid, app.isSystemPackage(), signed || factoryVerifier(pm, pkg, app), true,
            )?.let { return it }
        }
        null
    }.getOrNull()

    /** Cheap prefilter before queuing work; never sufficient for dispatch without source(). */
    fun candidateSource(pm: PackageManager, uid: Int, serviceUid: Int): String? = runCatching {
        if (uid < 0 || UserHandle.getUserHandleForUid(uid) != UserHandle.getUserHandleForUid(serviceUid)) return null
        val packages = pm.getPackagesForUid(uid).orEmpty()
        trustAnchors.keys.forEach { pkg ->
            if (pkg !in packages) return@forEach
            val app = pm.getApplicationInfo(pkg, 0)
            VendorAssistantBridgePolicy.source(pkg, app.uid == uid, app.isSystemPackage(), true, true)?.let { return it }
        }
        null
    }.getOrNull()

    private fun ApplicationInfo.isSystemPackage(): Boolean =
        flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
}
