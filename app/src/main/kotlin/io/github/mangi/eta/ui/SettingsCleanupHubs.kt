package io.github.mangi.eta.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.itos.coui.layout.Group
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiIconDivider
import io.github.mangi.eta.ui.components.coui.CouiPageBottomSpacer
import io.github.mangi.eta.ui.components.coui.CouiPreference
import io.github.mangi.eta.ui.components.coui.CouiScaffoldPage
import io.github.mangi.eta.ui.navigation.AppRoute
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.ArrowPreference

private val CleanupBlue = Color(0xFF4285F4)
private val CleanupGreen = Color(0xFF34A853)
private val CleanupOrange = Color(0xFFFA7B17)
private val CleanupPurple = Color(0xFF8E57C7)

internal data class CleanupSettingsEntry(
    val title: Int,
    val route: AppRoute,
    val icon: ImageVector,
    val color: Color = CleanupBlue,
    val summary: Int? = null,
)

internal data class CleanupSettingsSection(val title: Int, val entries: List<CleanupSettingsEntry>)

// These are separate functional groups. Prompt studio belongs to the existing prompt/extension group.
internal fun CleanupSettingsGroups(): List<CleanupSettingsSection> = cleanupSettingsGroups

private val cleanupSettingsGroups: List<CleanupSettingsSection> by lazy { listOf(
    CleanupSettingsSection(R.string.cleanup_automation_device, listOf(
        CleanupSettingsEntry(R.string.automation_title, AppRoute.Tasks, Icons.Rounded.Dashboard, CleanupOrange),
        CleanupSettingsEntry(R.string.cleanup_background_canvas, AppRoute.BackgroundCanvas, Icons.Rounded.Layers, CleanupBlue),
    )),
    CleanupSettingsSection(R.string.cleanup_accounts_connections, listOf(
        CleanupSettingsEntry(R.string.route_github_account, AppRoute.GitHubAccount, Icons.Rounded.Code, CleanupPurple),
    )),
    CleanupSettingsSection(R.string.cleanup_diagnostics_maintenance, listOf(
        CleanupSettingsEntry(R.string.cleanup_diagnostics_center, AppRoute.DiagnosticsCenter, Icons.Rounded.BugReport, CleanupOrange),
    )),
    CleanupSettingsSection(R.string.cleanup_statistics_storage, listOf(
        CleanupSettingsEntry(R.string.usage_stats_title, AppRoute.TokenUsage, Icons.Rounded.Dashboard, CleanupGreen),
        CleanupSettingsEntry(R.string.data_usage_title, AppRoute.DataUsage, Icons.Rounded.Storage, CleanupBlue),
        CleanupSettingsEntry(R.string.data_backup_title, AppRoute.DataBackup, Icons.Rounded.Description, CleanupPurple),
    )),
    CleanupSettingsSection(R.string.cleanup_notifications_display, listOf(
        CleanupSettingsEntry(R.string.cleanup_notifications_fluid, AppRoute.NotificationDisplaySettings, Icons.Rounded.Notifications, CleanupGreen),
    )),
) }

/** Transparent 32dp alignment slot; tint the actual glyph, identically in both styles. */
@Composable
internal fun CleanupIconTile(icon: ImageVector, tint: Color = settingsIconTint(icon), enabled: Boolean = true) {
    val resolvedTint = if (tint == Color.Unspecified) settingsIconTint(icon) else tint
    val glyphSize = if (icon == Icons.AutoMirrored.Rounded.MenuBook) 22.dp else 24.dp
    Box(modifier = Modifier.size(32.dp), contentAlignment = Alignment.Center) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(glyphSize),
            tint = if (enabled) resolvedTint else resolvedTint.copy(alpha = resolvedTint.alpha * 0.38f),
        )
    }
}

@Composable
internal fun CleanupSettingsRow(entry: CleanupSettingsEntry, onNavigate: (AppRoute) -> Unit) {
    val title = stringResource(entry.title)
    val summary = entry.summary?.let { stringResource(it) }
    if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
        CouiPreference(title = title, summary = summary,
            icon = { CleanupIconTile(entry.icon, entry.color) }, onClick = { onNavigate(entry.route) })
    } else {
        ArrowPreference(title = title, summary = summary,
            startAction = { SettingsIcon(entry.icon, tint = entry.color) }, onClick = { onNavigate(entry.route) })
    }
}

@Composable
internal fun CleanupSettingsRows(entries: List<CleanupSettingsEntry>, onNavigate: (AppRoute) -> Unit) {
    entries.forEachIndexed { index, entry ->
        if (index > 0 && LocalUiStyle.current == AppearanceUiStyle.COUI) CouiIconDivider()
        CleanupSettingsRow(entry, onNavigate)
    }
}

internal data class CleanupHubSection(val title: Int, val content: @Composable () -> Unit)

@Composable
private fun CleanupHubPage(title: Int, onBack: () -> Unit, sections: List<CleanupHubSection>) {
    if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
        CouiScaffoldPage(title = stringResource(title), onBack = onBack) {
            sections.forEach { section -> Group(title = stringResource(section.title)) { section.content() } }
            CouiPageBottomSpacer()
        }
    } else {
        MiuixScaffoldPage(title = stringResource(title), onBack = onBack) {
            sections.forEach { section ->
                item(key = section.title) {
                    SmallTitle(stringResource(section.title))
                    SettingsGroupCard { section.content() }
                }
            }
        }
    }
}

/** The old route is an alias of the same unified settings page; never a second engine switch. */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun BackgroundCanvasSettingsScreen(onBack: () -> Unit, onNavigate: (AppRoute) -> Unit) {
    // Canvas agent keeps this legacy function as an alias of UnifiedCanvasSettingsScreen.
    // Keep our existing route signature so Root navigation needs no ownership-crossing edit.
    io.github.mangi.eta.ui.screens.tasks.VirtualScreenSettingsScreen(onBack = onBack)
}

@Composable
internal fun NotificationDisplaySettingsScreen(onBack: () -> Unit) {
    CleanupHubPage(R.string.cleanup_notifications_fluid, onBack, listOf(
        CleanupHubSection(R.string.compact_fluid_status) { FluidPromotionStatus() },
        CleanupHubSection(R.string.cleanup_task_running) { FluidNotificationSettings(pinned = false) },
        CleanupHubSection(R.string.cleanup_idle_pinned) { FluidNotificationSettings(pinned = true) },
    ))
}
