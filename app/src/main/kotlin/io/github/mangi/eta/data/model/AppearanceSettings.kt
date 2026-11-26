package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

const val MIN_INTERFACE_SCALE = 0.8f
const val MAX_INTERFACE_SCALE = 1.1f
const val DEFAULT_INTERFACE_SCALE = 1f
const val MIN_CONVERSATION_PANE_EDGE_WIDTH_DP = 16
const val MAX_CONVERSATION_PANE_EDGE_WIDTH_DP = 4096
// 0 means follow the current window width (half by default); positive values are user-selected dp.
const val DEFAULT_CONVERSATION_PANE_EDGE_WIDTH_DP = 0
const val CONVERSATION_PANE_EDGE_WIDTH_STEP_DP = 4
const val DEFAULT_GESTURE_SENSITIVITY = 1f
const val MIN_GESTURE_SENSITIVITY = 0.75f
const val MAX_GESTURE_SENSITIVITY = 2f
const val DEFAULT_CONVERSATION_PANE_COMMIT_THRESHOLD_PERCENT = 11.5f
const val MIN_CONVERSATION_PANE_COMMIT_THRESHOLD_PERCENT = 3f
const val MAX_CONVERSATION_PANE_COMMIT_THRESHOLD_PERCENT = 20f

@Serializable
data class AppearanceSettings(
    val uiStyle: AppearanceUiStyle = AppearanceUiStyle.COUI,
    val themeMode: AppearanceThemeMode = AppearanceThemeMode.SYSTEM,
    val monetEnabled: Boolean = false,
    val paletteStyle: AppearancePaletteStyle = AppearancePaletteStyle.TONAL_SPOT,
    val accentColor: AppearanceAccentColor = AppearanceAccentColor.SYSTEM,
    val pureBlackEnabled: Boolean = false,
    val blurEnabled: Boolean = true,
    val liquidGlassEnabled: Boolean = true,
    val topBarBlurStyle: AppearanceTopBarBlurStyle = AppearanceTopBarBlurStyle.GAUSSIAN,
    val swipeDismissEnabled: Boolean = true,
    val conversationPaneEdgeWidthDp: Int = DEFAULT_CONVERSATION_PANE_EDGE_WIDTH_DP,
    val gestureSensitivity: Float = DEFAULT_GESTURE_SENSITIVITY,
    val conversationPaneCommitThresholdPercent: Float = DEFAULT_CONVERSATION_PANE_COMMIT_THRESHOLD_PERCENT,
    val predictiveBackEnabled: Boolean = true,
    val interfaceScale: Float = DEFAULT_INTERFACE_SCALE,
) {
    fun normalized(): AppearanceSettings = copy(
        interfaceScale = normalizeInterfaceScale(interfaceScale),
        gestureSensitivity = normalizeGestureSensitivity(gestureSensitivity),
        conversationPaneCommitThresholdPercent = normalizeConversationPaneCommitThreshold(
            conversationPaneCommitThresholdPercent,
        ),
        conversationPaneEdgeWidthDp = if (conversationPaneEdgeWidthDp == DEFAULT_CONVERSATION_PANE_EDGE_WIDTH_DP) {
            DEFAULT_CONVERSATION_PANE_EDGE_WIDTH_DP
        } else {
            conversationPaneEdgeWidthDp.coerceIn(MIN_CONVERSATION_PANE_EDGE_WIDTH_DP, MAX_CONVERSATION_PANE_EDGE_WIDTH_DP)
        },
    )
}

@Serializable
enum class AppearanceUiStyle(val persistedValue: String) {
    COUI("coui"),
    MIUIX("miuix");

    val isCoui: Boolean
        get() = this == COUI

    companion object {
        fun fromPersistedValue(value: String?): AppearanceUiStyle =
            entries.firstOrNull { it.persistedValue == value } ?: COUI
    }
}

@Serializable
enum class AppearanceThemeMode(val persistedValue: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    fun resolveDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        fun fromPersistedValue(value: String?): AppearanceThemeMode =
            entries.firstOrNull { it.persistedValue == value } ?: SYSTEM
    }
}

@Serializable
enum class AppearancePaletteStyle(val persistedValue: String) {
    TONAL_SPOT("tonal_spot"),
    NEUTRAL("neutral"),
    VIBRANT("vibrant"),
    EXPRESSIVE("expressive"),
    RAINBOW("rainbow"),
    FRUIT_SALAD("fruit_salad"),
    MONOCHROME("monochrome"),
    FIDELITY("fidelity"),
    CONTENT("content");

    companion object {
        fun fromPersistedValue(value: String?): AppearancePaletteStyle =
            entries.firstOrNull { it.persistedValue == value } ?: TONAL_SPOT
    }
}

@Serializable
enum class AppearanceAccentColor(val persistedValue: String) {
    SYSTEM("system"),
    BLUE("blue"),
    PURPLE("purple"),
    PINK("pink"),
    RED("red"),
    ORANGE("orange"),
    YELLOW("yellow"),
    GREEN("green"),
    TEAL("teal");

    companion object {
        fun fromPersistedValue(value: String?): AppearanceAccentColor =
            entries.firstOrNull { it.persistedValue == value } ?: SYSTEM
    }
}

@Serializable
enum class AppearanceTopBarBlurStyle(val persistedValue: String) {
    GAUSSIAN("gaussian"),
    PROGRESSIVE("progressive");

    companion object {
        fun fromPersistedValue(value: String?): AppearanceTopBarBlurStyle =
            entries.firstOrNull { it.persistedValue == value } ?: GAUSSIAN
    }
}

fun normalizeInterfaceScale(value: Float): Float =
    if (value.isFinite()) {
        value.coerceIn(MIN_INTERFACE_SCALE, MAX_INTERFACE_SCALE)
    } else {
        DEFAULT_INTERFACE_SCALE
    }

fun normalizeGestureSensitivity(value: Float): Float =
    if (!value.isFinite()) DEFAULT_GESTURE_SENSITIVITY
    else (kotlin.math.round(value.coerceIn(MIN_GESTURE_SENSITIVITY, MAX_GESTURE_SENSITIVITY) * 100f) / 100f)

fun normalizeConversationPaneCommitThreshold(value: Float): Float =
    if (!value.isFinite()) DEFAULT_CONVERSATION_PANE_COMMIT_THRESHOLD_PERCENT
    else (kotlin.math.round(value.coerceIn(
        MIN_CONVERSATION_PANE_COMMIT_THRESHOLD_PERCENT,
        MAX_CONVERSATION_PANE_COMMIT_THRESHOLD_PERCENT,
    ) * 10f) / 10f)

/** Treat the previous shipped 50% default as an unset/default value after lowering the default. */
fun migrateConversationPaneCommitThreshold(value: Float?): Float = when {
    value == null || value == 50f -> DEFAULT_CONVERSATION_PANE_COMMIT_THRESHOLD_PERCENT
    else -> normalizeConversationPaneCommitThreshold(value)
}
