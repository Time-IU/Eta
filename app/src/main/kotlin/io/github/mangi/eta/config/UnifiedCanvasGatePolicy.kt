package io.github.mangi.eta.config

/** Shared wire contract. Missing is NOT the old default-true permission. */
internal object UnifiedCanvasGatePolicy {
    const val EFFECTIVE_KEY = "unified_canvas_effective_enabled_v1"
    fun enabled(effective: Boolean?, legacy: Boolean?): Boolean = effective ?: legacy ?: false

    /** Return true only after persistence and readback; never interpret commit failure as success. */
    fun mirror(effective: Boolean, commit: (Boolean) -> Boolean, readBack: () -> Boolean?) {
        if (!commit(effective)) throw java.io.IOException("Canvas RemotePreferences commit rejected")
        if (readBack() != effective) throw java.io.IOException("Canvas RemotePreferences confirmation lost")
    }
}
