package io.github.mangi.eta.agent.display

/** One physical backend slot. Caller holds VirtualScreenSession's lock across creation/close.
 * Entry names are aliases, never authority: owner + run lease + session identify authority.
 */
internal class UnifiedCanvasSlot {
    data class Session(val owner: String, val id: String, val displayId: Int? = null, val transport: UnifiedCanvasTransportPolicy.Kind? = null)
    var current: Session? = null
        private set
    fun permits(owner: String): Boolean = current?.owner.let { it == null || it == owner }
    fun attach(owner: String, id: String, transport: UnifiedCanvasTransportPolicy.Kind? = null): Boolean {
        if (current != null) return current?.let { it.owner == owner && it.id == id && (transport == null || it.transport == transport) } == true
        current = Session(owner, id, transport = transport)
        return true
    }
    fun activate(owner: String, id: String, displayId: Int): Boolean {
        require(displayId > 0)
        val active = current ?: return false
        if (active.owner != owner || active.id != id) return false
        current = active.copy(displayId = displayId)
        return true
    }
    fun resolve(owner: String): Session? = current?.takeIf { it.owner == owner }
    fun detach(owner: String, id: String): Boolean {
        if (current?.owner != owner || current?.id != id) return false
        current = null
        return true
    }
}

/** Presence-aware migration: DataStore's explicit selection wins, including false.
 * The legacy auto-background switch is retained separately (not promoted to a new permission).
 */
internal object UnifiedCanvasSettingsPolicy {
    fun enabled(dataStore: Boolean?, legacyBackground: Boolean): Boolean = dataStore ?: legacyBackground
    fun background(dataStore: Boolean?, legacyBackground: Boolean): Boolean = dataStore ?: legacyBackground
}

internal data class UnifiedCanvasSettingsSignal(
    val enabled: Boolean,
    val screenOff: Boolean,
    val keepScreenOn: Boolean,
    val idleMinutes: Int,
) {
    fun requiresDispatch(previous: UnifiedCanvasSettingsSignal): Boolean = this != previous
    fun requiresRecreate(existingAllowsScreenOff: Boolean): Boolean = screenOff != existingAllowsScreenOff
}
