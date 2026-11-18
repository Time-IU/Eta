package io.github.mangi.eta.agent.runtime

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Some ROMs sign the assistant independently from the platform and other vendor apps.
 * Anchor those packages to the same package's APK on an immutable system partition,
 * NEVER to the installed /data APK itself. For updates, hasSigningCertificate also
 * accepts the factory certificate in the package manager's verified signing lineage.
 */
internal object VendorAssistantBridgeSignatures {
    private data class Key(val pkg: String, val source: String, val updatedAt: Long)
    private val verified = ConcurrentHashMap<Key, Boolean>()
    private val roots = listOf("/system", "/system_ext", "/product", "/vendor", "/odm", "/oem")

    fun matchesFactory(pm: PackageManager, pkg: String, app: ApplicationInfo): Boolean = runCatching {
        val installed = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
        val key = Key(pkg, app.sourceDir.orEmpty(), installed.lastUpdateTime)
        verified[key]?.let { return it }
        val matched = factoryApks(pkg, app).any { apk ->
            val factory = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
            if (factory?.packageName != pkg) return@any false
            val signers = factory.signingInfo?.apkContentsSigners.orEmpty()
            signers.isNotEmpty() && signers.all { signer ->
                pm.hasSigningCertificate(pkg, MessageDigest.getInstance("SHA-256").digest(signer.toByteArray()),
                    PackageManager.CERT_INPUT_SHA256)
            }
        }
        // Bound the small cache even across repeated system-package updates.
        if (verified.size >= 8) verified.clear()
        verified[key] = matched
        matched
    }.getOrDefault(false)

    private fun factoryApks(pkg: String, app: ApplicationInfo): Sequence<File> = sequence {
        app.sourceDir?.let { path ->
            val apk = File(path).canonicalFile
            if (isProtectedApk(apk.path) && apk.isFile) yield(apk)
        }
        val labels = when (pkg) {
            "com.heytap.speechassist" -> listOf("speechassist", "breeno")
            "com.miui.voiceassist" -> listOf("voiceassist", "xiaoai")
            else -> return@sequence
        }
        // Search only conventional assistant directories, not every system APK or shared storage.
        for (root in roots) for (kind in listOf("app", "priv-app")) {
            for (dir in File(root, kind).listFiles().orEmpty()) {
                if (labels.none { dir.name.contains(it, ignoreCase = true) }) continue
                if (dir.isFile && isProtectedApk(dir.canonicalPath)) yield(dir.canonicalFile)
                if (!dir.isDirectory) continue
                for (file in dir.listFiles().orEmpty()) {
                    val apk = file.canonicalFile
                    if (isProtectedApk(apk.path) && apk.isFile) yield(apk)
                }
            }
        }
    }

    internal fun isProtectedApk(path: String): Boolean =
        path.endsWith(".apk", ignoreCase = true) && roots.any { root -> path.startsWith("$root/") } &&
            !path.split('/').any { it == ".." || it == "." }
}
