package io.github.mangi.eta.agent.display

/** A stale Activity/launch timeout cannot release a successor, even on the same session. */
internal class CanvasTakeoverLease {
    data class Token(val owner: String, val session: String, val id: String)
    @Volatile var current: Token? = null
        private set
    @Volatile private var attached: String? = null
    @Synchronized fun begin(owner: String, session: String, id: String): Token = Token(owner, session, id).also {
        current = it; attached = null
    }
    fun submit(token: Token, start: () -> Boolean, beforeRollback: () -> Unit = {}): Boolean {
        if (current != token) return false
        val submitted = try { start() } catch (_: RuntimeException) { false }
        if (!submitted) { beforeRollback(); end(token) }
        return submitted
    }
    @Synchronized fun attach(token: Token): Boolean {
        if (current != token) return false
        attached = token.id; return true
    }
    @Synchronized fun end(token: Token): Boolean {
        if (current != token) return false
        current = null; attached = null; return true
    }
    @Synchronized fun expireUnattached(token: Token): Boolean = current == token && attached != token.id && end(token)
    fun permitsInput(session: String, id: String): Boolean = current?.let { it.session == session && it.id == id } == true
    @Synchronized fun clearSession(session: String) { current?.takeIf { it.session == session }?.let { end(it) } }
}
