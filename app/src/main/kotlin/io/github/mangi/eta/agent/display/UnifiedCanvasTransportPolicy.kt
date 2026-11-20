package io.github.mangi.eta.agent.display

/** Transport choice never changes owner/session/route. Hook presence permits an attempt, not a
 * claim of privileges: the created display must pass the system's trusted/focus validation.
 */
internal object UnifiedCanvasTransportPolicy {
    enum class Kind { ROOT_PROCESS, HOOK_IN_PROCESS, UNAVAILABLE }
    fun choose(root: Boolean, hook: Boolean): Kind = when {
        root -> Kind.ROOT_PROCESS
        hook -> Kind.HOOK_IN_PROCESS
        else -> Kind.UNAVAILABLE
    }
    fun available(root: Boolean, hook: Boolean): Boolean = choose(root, hook) != Kind.UNAVAILABLE
    fun bypassRootRequirement(name: String, hook: Boolean, virtualRoute: Boolean): Boolean =
        hook && (name == "virtual_screen" || name == "lingscreen_open_app" || virtualRoute && name in VirtualScreenRoutingPolicy.uiTools)
}
