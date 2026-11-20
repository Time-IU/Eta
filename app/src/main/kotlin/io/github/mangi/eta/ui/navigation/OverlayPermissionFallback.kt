package io.github.mangi.eta.ui.navigation

internal enum class OverlaySettingsTarget { PACKAGE, LIST, APP_DETAILS }

/** Ordered system UI attempts, stopping on launch only; never indicates permission approval. */
internal inline fun launchOverlaySettingsTarget(launch: (OverlaySettingsTarget) -> Boolean): OverlaySettingsTarget? {
    for (target in OverlaySettingsTarget.entries) if (launch(target)) return target
    return null
}
