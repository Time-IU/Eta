package io.github.mangi.eta.agent.display

import io.github.mangi.eta.agent.display.dashboard.ConfirmRiskDetector

/** Immutable evidence, private to one owner/run/route/observation. No global screen-text cache. */
internal data class GuiRiskNode(
    val index: Int, val text: String, val description: String,
    val left: Int, val top: Int, val right: Int, val bottom: Int,
    val clickable: Boolean = false,
) {
    fun contains(x: Int, y: Int) = x >= left && x < right && y >= top && y < bottom
    val area: Long get() = (right.toLong() - left).coerceAtLeast(0) * (bottom.toLong() - top).coerceAtLeast(0)
    val label: String get() = listOf(text, description).filter(String::isNotBlank).distinct().joinToString(" ")
}

internal data class GuiRiskObservation(
    val owner: String, val runId: String, val id: String,
    val displayId: Int, val routeRevision: Long, val capturedAtMs: Long,
    val width: Int, val height: Int, val nodes: List<GuiRiskNode>,
    val packageName: String = "", val windowId: Int? = null,
    val sessionId: String? = null, val manualGeneration: Long = 0,
)

internal object GuiRiskPolicy {
    const val MAX_AGE_MS = 15_000L

    fun validate(evidence: GuiRiskObservation?, owner: String, runId: String, displayId: Int,
        revision: Long, nowMs: Long, requestedId: String? = null): String? = when {
        evidence == null -> "NO_OBSERVATION"
        evidence.owner != owner || evidence.runId != runId || evidence.displayId != displayId ||
            evidence.routeRevision != revision -> "STALE_OBSERVATION"
        requestedId != null && requestedId != evidence.id -> "STALE_OBSERVATION"
        nowMs < evidence.capturedAtMs || nowMs - evidence.capturedAtMs > MAX_AGE_MS -> "STALE_OBSERVATION"
        else -> null
    }

    /** Hit test actual bounds, not proximity to any cached node on any other display. */
    fun target(evidence: GuiRiskObservation, x: Int, y: Int, index: Int? = null): String {
        require(x in 0 until evidence.width && y in 0 until evidence.height) { "INVALID_COORDINATES" }
        val hit = if (index != null) evidence.nodes.firstOrNull { it.index == index }
            ?: error("INVALID_NODE_INDEX")
        else evidence.nodes.filter { it.contains(x, y) && it.label.isNotBlank() }
            .minWithOrNull(compareBy<GuiRiskNode> { it.area }.thenByDescending { it.clickable })
        if (hit == null) return ""
        if (hit.label.isNotBlank()) return hit.label
        // Clickable containers may carry their text in non-clickable descendants.
        return evidence.nodes.filter { node -> node.label.isNotBlank() && node.left >= hit.left &&
            node.right <= hit.right && node.top >= hit.top && node.bottom <= hit.bottom }
            .joinToString(" ") { it.label }
    }

    /** The final detector owns risk classification, including label-free checkout and safe send/search.
     * No duplicated risk vocabulary or stronger preflight can be downgraded by another classifier. */
    fun shouldInspect(target: String, visible: List<String>): Boolean =
        ConfirmRiskDetector.inspect(target, visible) != null
}

/** Display identity is independent of run identity, which is scoped separately in ApprovalOperation.
 * A receiving executor can re-observe the same physical screen after its predecessor terminates.
 * Virtual sessions/revisions remain strict: no transfer to a recreated or different display. */
internal fun approvalDisplayIdentity(displayId: Int, sessionId: String?, revision: Long): String =
    if (displayId == 0 && sessionId.isNullOrEmpty()) "main:0"
    else listOf(displayId.toString(), sessionId.orEmpty(), revision.toString())
        .joinToString("|") { "${it.length}:$it" }
