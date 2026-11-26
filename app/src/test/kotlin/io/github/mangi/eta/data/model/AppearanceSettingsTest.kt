package io.github.mangi.eta.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceSettingsTest {
    @Test
    fun defaultsPreserveExistingAppearanceBehaviorAndDefaultToCoui() {
        val settings = AppearanceSettings()

        assertEquals(AppearanceUiStyle.COUI, settings.uiStyle)
        assertEquals(AppearanceThemeMode.SYSTEM, settings.themeMode)
        assertFalse(settings.monetEnabled)
        assertTrue(settings.blurEnabled)
        assertTrue(settings.swipeDismissEnabled)
        assertEquals(DEFAULT_CONVERSATION_PANE_EDGE_WIDTH_DP, settings.conversationPaneEdgeWidthDp)
        assertTrue(settings.predictiveBackEnabled)
        assertEquals(DEFAULT_CONVERSATION_PANE_EDGE_WIDTH_DP, settings.conversationPaneEdgeWidthDp)
        assertEquals(DEFAULT_INTERFACE_SCALE, settings.interfaceScale)
        assertEquals(DEFAULT_GESTURE_SENSITIVITY, settings.gestureSensitivity)
    }

    @Test
    fun invalidPersistedEnumsFallBackToStableDefaults() {
        assertEquals(AppearanceUiStyle.COUI, AppearanceUiStyle.fromPersistedValue(null))
        assertEquals(AppearanceUiStyle.COUI, AppearanceUiStyle.fromPersistedValue("unknown"))
        assertEquals(AppearanceUiStyle.COUI, AppearanceUiStyle.fromPersistedValue("coui"))
        assertEquals(AppearanceUiStyle.MIUIX, AppearanceUiStyle.fromPersistedValue("miuix"))
        assertEquals(AppearanceThemeMode.SYSTEM, AppearanceThemeMode.fromPersistedValue("unknown"))
        assertEquals(AppearancePaletteStyle.TONAL_SPOT, AppearancePaletteStyle.fromPersistedValue(null))
        assertEquals(AppearanceAccentColor.SYSTEM, AppearanceAccentColor.fromPersistedValue(""))
        assertEquals(
            AppearanceTopBarBlurStyle.GAUSSIAN,
            AppearanceTopBarBlurStyle.fromPersistedValue("future_style"),
        )
    }

    @Test
    fun gestureSensitivityIsFiniteClampedAndPersistableInAppearanceSettings() {
        assertEquals(MIN_GESTURE_SENSITIVITY, normalizeGestureSensitivity(0.1f))
        assertEquals(MAX_GESTURE_SENSITIVITY, normalizeGestureSensitivity(3f))
        assertEquals(2f, MAX_GESTURE_SENSITIVITY)
        assertEquals(DEFAULT_GESTURE_SENSITIVITY, normalizeGestureSensitivity(Float.NaN))
        assertEquals(1.15f, normalizeGestureSensitivity(1.15f))
        assertEquals(1.15f, AppearanceSettings(gestureSensitivity = 1.15f).normalized().gestureSensitivity)
    }

    @Test
    fun conversationPaneCommitThresholdClampsToThreeThroughTwentyPercent() {
        assertEquals(3f, normalizeConversationPaneCommitThreshold(0f))
        assertEquals(20f, normalizeConversationPaneCommitThreshold(80f))
        assertEquals(3f, MIN_CONVERSATION_PANE_COMMIT_THRESHOLD_PERCENT)
        assertEquals(20f, MAX_CONVERSATION_PANE_COMMIT_THRESHOLD_PERCENT)
        assertEquals(DEFAULT_CONVERSATION_PANE_COMMIT_THRESHOLD_PERCENT,
            AppearanceSettings().conversationPaneCommitThresholdPercent)
        assertEquals(11.5f, normalizeConversationPaneCommitThreshold(11.5f))
        assertEquals(DEFAULT_CONVERSATION_PANE_COMMIT_THRESHOLD_PERCENT,
            normalizeConversationPaneCommitThreshold(Float.NaN))
        assertEquals(DEFAULT_CONVERSATION_PANE_COMMIT_THRESHOLD_PERCENT,
            migrateConversationPaneCommitThreshold(null))
        assertEquals(DEFAULT_CONVERSATION_PANE_COMMIT_THRESHOLD_PERCENT,
            migrateConversationPaneCommitThreshold(50f))
        assertEquals(11.5f, MIN_CONVERSATION_PANE_COMMIT_THRESHOLD_PERCENT +
            (MAX_CONVERSATION_PANE_COMMIT_THRESHOLD_PERCENT - MIN_CONVERSATION_PANE_COMMIT_THRESHOLD_PERCENT) * 3f / 6f)
        assertEquals(10f, migrateConversationPaneCommitThreshold(10f))
    }

    @Test
    fun interfaceScaleIsFiniteAndClamped() {
        assertEquals(MIN_INTERFACE_SCALE, normalizeInterfaceScale(0.2f))
        assertEquals(MAX_INTERFACE_SCALE, normalizeInterfaceScale(2f))
        assertEquals(DEFAULT_INTERFACE_SCALE, normalizeInterfaceScale(Float.NaN))
        assertEquals(DEFAULT_INTERFACE_SCALE, normalizeInterfaceScale(Float.POSITIVE_INFINITY))
        assertEquals(0.95f, normalizeInterfaceScale(0.95f))
    }
}
