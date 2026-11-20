package io.github.mangi.eta.ui

import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.components.EtaPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

@Composable
internal fun FluidPromotionStatus() {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var status by remember { mutableStateOf(R.string.compact_fluid_wait) }
    LaunchedEffect(lifecycle, context) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                status = withContext(Dispatchers.IO) {
                    val manager = context.getSystemService(NotificationManager::class.java)
                    try {
                        when {
                            Build.VERSION.SDK_INT < 36 -> R.string.compact_fluid_old_sdk
                            !manager.areNotificationsEnabled() -> R.string.compact_fluid_notifications_off
                            !manager.canPostPromotedNotifications() -> R.string.compact_fluid_not_allowed
                            manager.activeNotifications.any { it.notification.flags and Notification.FLAG_PROMOTED_ONGOING != 0 } -> R.string.compact_fluid_promoted
                            manager.activeNotifications.any { it.notification.extras.getBoolean("android.requestPromotedOngoing",false) && it.notification.hasPromotableCharacteristics() } -> R.string.compact_fluid_requested
                            manager.activeNotifications.any { it.notification.extras.getBoolean("android.requestPromotedOngoing",false) } -> R.string.compact_fluid_ineligible
                            else -> R.string.compact_fluid_no_request
                        }
                    } catch (_: RuntimeException) { R.string.compact_fluid_unknown }
                }
                delay(3000)
            }
        }
    }
    EtaPreference(title = stringResource(R.string.compact_fluid_status), summary = stringResource(status))
}
