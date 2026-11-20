package io.github.mangi.eta.ui.navigation

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import io.github.mangi.eta.R

/** Opens system UI only. A successful launch is NOT permission approval: recheck on resume. */
internal object OverlayPermissionNavigation {
    /** Shared by settings, the permission centre and the canvas controller. Never uses appops. */
    fun open(context: Context): Boolean {
        val result = launchCandidates(context.packageName) { candidate ->
            try {
                context.startActivity(prepareIntent(context, candidate))
                true
            } catch (failure: RuntimeException) {
                // Includes ROM ActivityNotFoundException / SecurityException and bad contexts.
                Log.w("OverlayPermission", "System permission screen unavailable: ${candidate.action}", failure)
                false
            }
        }
        when (result) {
            OverlaySettingsTarget.PACKAGE -> Unit
            OverlaySettingsTarget.LIST -> Toast.makeText(
                context.applicationContext, R.string.overlay_permission_choose_app, Toast.LENGTH_LONG,
            ).show()
            OverlaySettingsTarget.APP_DETAILS -> Toast.makeText(
                context.applicationContext, R.string.overlay_permission_details_fallback, Toast.LENGTH_LONG,
            ).show()
            null -> Toast.makeText(
                context.applicationContext, R.string.overlay_permission_open_failed, Toast.LENGTH_LONG,
            ).show()
        }
        return result != null
    }

    /** Try the generic system list if a ROM rejects the package-specific overlay page. */
    internal fun launchCandidates(
        packageName: String,
        launch: (Intent) -> Boolean,
    ): OverlaySettingsTarget? {
        return launchOverlaySettingsTarget { target ->
            val intent = when (target) {
                OverlaySettingsTarget.PACKAGE -> Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"),
                )
                OverlaySettingsTarget.LIST -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                OverlaySettingsTarget.APP_DETAILS -> Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"),
                )
            }
            // Do not preflight resolveActivity: package visibility can hide a valid system handler.
            launch(intent)
        }
    }

    internal fun prepareIntent(context: Context, intent: Intent): Intent = Intent(intent).apply {
        if (context.findActivity() == null) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun Context.findActivity(): Activity? {
        var current = this
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Context, Boolean>())
        while (seen.add(current)) {
            if (current is Activity) return current.takeUnless { it.isFinishing || it.isDestroyed }
            current = (current as? ContextWrapper)?.baseContext ?: return null
        }
        return null
    }
}

