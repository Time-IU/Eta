package io.github.mangi.eta.ui.screens.permissions

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Returning without granting (or revoking an existing grant) must remain truthful. */
@Composable
internal fun rememberOverlayPermissionGranted(context: Context): Boolean {
    val appContext = context.applicationContext
    var granted by remember(appContext) { mutableStateOf(Settings.canDrawOverlays(appContext)) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, appContext) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = Settings.canDrawOverlays(appContext)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return granted
}
