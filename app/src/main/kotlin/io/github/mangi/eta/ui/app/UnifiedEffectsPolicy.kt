package io.github.mangi.eta.ui.app

/** No shader/material in the unsupported service-overlay host; never override saved choice. */
internal fun unifiedEffectsEnabled(requested: Boolean, supported: Boolean, safeHost: Boolean): Boolean =
    requested && supported && safeHost
