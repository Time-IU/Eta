#!/usr/bin/env python3
"""Static wiring/resource regressions; not Compose, ROM navigation or frame-time tests."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
MAIN = ROOT / 'app/src/main'
UI = MAIN / 'kotlin/io/github/mangi/eta/ui'


class SettingsPermissionContract(unittest.TestCase):
    def test_icon_slot_has_no_backplate_and_tints_glyph_not_white(self):
        text = (UI / 'SettingsCleanupHubs.kt').read_text().split('internal fun CleanupIconTile(', 1)[1].split('@Composable', 1)[0]
        self.assertIn('Modifier.size(32.dp)', text)
        self.assertIn('Modifier.size(glyphSize)', text)
        self.assertIn('if (enabled) resolvedTint else resolvedTint.copy', text)
        self.assertNotIn('background(', text)
        self.assertNotIn('Color.White', text)
        self.assertNotIn('LocalUiStyle', text)  # one renderer, same geometry in both styles

    def test_two_settings_styles_share_permission_callback_without_granted_guard(self):
        text = (UI / 'SettingsScreen.kt').read_text()
        self.assertEqual(2, text.count('onClick = { OverlayPermissionNavigation.open(context) }'))
        self.assertNotIn('if (!overlayGranted)', text)
        self.assertNotIn('ACTION_MANAGE_OVERLAY_PERMISSION', text)
        self.assertIn('Lifecycle.Event.ON_RESUME', text)
        self.assertIn('overlayGranted = android.provider.Settings.canDrawOverlays(context)', text)

    def test_permission_centre_intercepts_only_overlay_and_keeps_other_actions(self):
        text = (UI / 'screens/permissions/PermissionHealthScreen.kt').read_text()
        self.assertIn('if (item.id == "overlay") OverlayPermissionNavigation.open(context)', text)
        self.assertIn('else onAction(PermissionHealthAction.OpenItemAction(item.id))', text)
        self.assertEqual(2, text.count('onActionClick = { openItem(item) }'))
        self.assertIn('rememberOverlayPermissionGranted(context)', text)

    def test_resume_checks_actual_permission_and_removes_observer(self):
        text = (UI / 'screens/permissions/OverlayPermissionState.kt').read_text()
        self.assertIn('Lifecycle.Event.ON_RESUME', text)
        self.assertEqual(2, text.count('Settings.canDrawOverlays(appContext)'))
        self.assertIn('removeObserver(observer)', text)
        self.assertNotIn('granted = true', text)

    def test_navigation_reports_failure_and_does_not_authorize(self):
        text = (UI / 'navigation/OverlayPermissionNavigation.kt').read_text()
        self.assertIn('catch (failure: RuntimeException)', text)
        self.assertIn('R.string.overlay_permission_open_failed', text)
        self.assertIn('Intent.FLAG_ACTIVITY_NEW_TASK', text)
        self.assertIn('current is Activity', text)
        self.assertIn('ContextWrapper', text)
        self.assertIn('Settings.ACTION_APPLICATION_DETAILS_SETTINGS', text)
        self.assertNotIn('resolveActivity(', text)
        self.assertNotIn('Settings.canDrawOverlays', text)
        self.assertNotIn('setMode(', text)
        self.assertNotIn('Runtime.getRuntime', text)

    def test_unused_synchronous_capability_snapshot_removed(self):
        text = (UI / 'SettingsScreen.kt').read_text()
        self.assertNotIn('rememberDeviceCapabilities', text)
        self.assertNotIn('AgentToolCapabilities.capture', text)
        self.assertNotIn('runBlocking', text)

    def test_flows_stable_lifecycle_aware_and_provider_mapping_off_main(self):
        text = (UI / 'SettingsScreen.kt').read_text()
        self.assertIn('val providersFlow = remember {', text)
        self.assertIn('val selectedProviderFlow = remember {', text)
        self.assertIn('val selectedModelFlow = remember {', text)
        self.assertIn('flowOn(Dispatchers.Default)', text)
        self.assertEqual(4, text.count('.collectAsStateWithLifecycle('))
        self.assertNotIn('.collectAsState(', text)

    def test_service_sync_is_off_main_and_cancelled_on_dispose(self):
        text = (UI / 'SettingsScreen.kt').read_text().split('DisposableEffect(enhancementHistory)', 1)[1].split('val powerAssistantTargets', 1)[0]
        self.assertIn('val connected = withContext(Dispatchers.IO)', text)
        self.assertIn('Prefs.reconcileAgentPreferences(service)', text)
        self.assertIn('remote?.let(enhancementHistory::captureConnected)', text)
        self.assertIn('refreshJob?.cancel()', text)
        self.assertIn('removeServiceStateListener(listener)', text)

    def test_new_permission_messages_exist_in_all_three_languages(self):
        expected = {'overlay_permission_choose_app', 'overlay_permission_details_fallback', 'overlay_permission_open_failed'}
        for folder in ['values', 'values-b+zh+Hans', 'values-b+zh+Hant']:
            nodes = ET.parse(MAIN / 'res' / folder / 'overlay_permission.xml').getroot()
            self.assertEqual(expected, {node.attrib['name'] for node in nodes})
            self.assertTrue(all(node.text.strip() for node in nodes))

    def test_canvas_hub_delegates_to_one_existing_settings_route(self):
        text = (UI / 'SettingsCleanupHubs.kt').read_text().split('internal fun BackgroundCanvasSettingsScreen', 1)[1].split('@Composable', 1)[0]
        self.assertIn('VirtualScreenSettingsScreen(onBack = onBack)', text)
        self.assertNotIn('SwitchPref(', text)
        self.assertNotIn('AGENT_VIRTUAL_DISPLAY_ENABLED', text)
        self.assertNotIn('AppRoute.VirtualScreen', text)

    def test_settings_sections_not_reallocated_on_each_recomposition(self):
        text = (UI / 'SettingsCleanupHubs.kt').read_text()
        self.assertIn('CleanupSettingsGroups(): List<CleanupSettingsSection> = cleanupSettingsGroups', text)
        self.assertIn('by lazy { listOf(', text)


if __name__ == '__main__':
    unittest.main(verbosity=2)
