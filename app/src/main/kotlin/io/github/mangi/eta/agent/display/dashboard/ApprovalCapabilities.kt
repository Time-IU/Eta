package io.github.mangi.eta.agent.display.dashboard

/** Authorization identity is deliberately not a display string, prefix, hash, or UI selection. */
internal data class ApprovalOperation(
    val conversation: String,
    val run: String,
    val risk: String,
    val action: String,
    val description: String,
    val gui: Boolean = false,
    val display: String = "",
) {
    val scoped: Boolean get() = conversation.isNotBlank() && run.isNotBlank() &&
        description.isNotBlank() && (!gui || display.isNotBlank())
}

/** Pure production authorization ledger; all checking and one-shot consumption share one lock. */
internal class ApprovalCapabilities(private val now: () -> Long = System::currentTimeMillis) {
    private data class Grant(val until: Long, val reusable: Boolean, val afterObservation: String, val afterVersion: Long)
    private val grants = mutableMapOf<ApprovalOperation, Grant>()

    @Synchronized
    fun grant(operation: ApprovalOperation, remember: Boolean, observation: String = "", observationVersion: Long = 0): Boolean {
        if (!operation.scoped || (operation.gui && (observation.isBlank() || observationVersion <= 0))) return false
        // Money can never be remembered, including callers bypassing the UI.
        val reusable = remember && operation.risk != "MONEY"
        grants[operation] = Grant(now() + if (reusable) 86_400_000L else 600_000L, reusable, observation, observationVersion)
        return true
    }

    @Synchronized
    fun consume(operation: ApprovalOperation, observation: String = "", observationVersion: Long = 0): Boolean {
        val grant = grants[operation] ?: return false
        if (now() >= grant.until) {
            grants.remove(operation)
            return false
        }
        // A fresh observation is mandatory for *every* GUI authorization, money or otherwise.
        if (operation.gui && (observation.isBlank() || observation == grant.afterObservation || observationVersion <= grant.afterVersion)) return false
        if (!grant.reusable) grants.remove(operation)
        else if (operation.gui) grants[operation] = grant.copy(afterObservation = observation, afterVersion = observationVersion)
        return true
    }

    @Synchronized fun clear() = grants.clear()
}

/** Length framing prevents delimiter/prefix collisions without truncating any reviewed evidence. */
internal fun approvalDescription(parts: List<String>): String =
    parts.joinToString("") { "${it.length}:$it" }

/** Serializes all entrances for one pending token; failure releases it without consuming it. */
internal class ApprovalDecisionClaims {
    private val submitting = mutableSetOf<String>()
    private val consumed = mutableSetOf<String>()
    @Synchronized fun begin(token: String): Boolean =
        token !in consumed && submitting.add(token)
    @Synchronized fun finish(token: String, accepted: Boolean) {
        submitting.remove(token)
        if (accepted) consumed.add(token)
    }
}
