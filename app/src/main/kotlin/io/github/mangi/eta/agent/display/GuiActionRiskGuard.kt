package io.github.mangi.eta.agent.display

import android.content.Context
import android.os.SystemClock
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.agent.display.dashboard.AgentConfirmGuard

/** The same final-target risk decision for main, legacy canvas, owned display and raw actions. */
internal data class GuiRiskInspection(
    val observation: GuiRiskObservation, val target: String, val visible: List<String>,
    val action: String, val x: Int, val y: Int, val nodeIndex: Int?,
)

internal object GuiActionRiskGuard {
    fun fromSnapshot(owner: String, runId: String, revision: Long, width: Int, height: Int,
        snapshot: AgentAccessibilityService.NodeSnapshot, sessionId: String? = null,
        manualGeneration: Long = 0): GuiRiskObservation = GuiRiskObservation(
        owner, runId, snapshot.id, snapshot.displayId, revision, snapshot.capturedAtElapsedMs,
        width, height, snapshot.nodes.map { node ->
            GuiRiskNode(node.index, node.text, node.desc, node.bounds.left, node.bounds.top,
                node.bounds.right, node.bounds.bottom, node.clickable)
        }, snapshot.packageName, snapshot.windowId, sessionId, manualGeneration,
    )

    /** Returns the complete blocked JSON; null is the only permission to continue this injection.
     * Stable dashboard API is serialized with its legacy text cache, and cleared even on failure.
     * No unrelated observation may seed checkout fallback or approval details. */
    fun inspect(context: Context?, evidence: GuiRiskObservation?, owner: String, runId: String,
        displayId: Int, revision: Long, x: Int, y: Int, action: String,
        requestedId: String? = null, index: Int? = null,
        nowMs: Long = SystemClock.elapsedRealtime(),
        capture: () -> AgentAccessibilityService.NodeSnapshot? = {
            AgentAccessibilityService.current()?.captureNodeSnapshot(120, displayId)
        },
        reviewer: (GuiRiskInspection) -> String? = { review(context, it) },
    ): String? {
        val invalid = GuiRiskPolicy.validate(evidence, owner, runId, displayId, revision,
            nowMs, requestedId)
        if (invalid != null) return failure(invalid, "目标观察缺失、已过期或不属于本次运行/画布；请先重新观察")
        val current = requireNotNull(evidence)
        val target = try {
            GuiRiskPolicy.target(current, x, y, index)
        } catch (error: IllegalArgumentException) {
            return failure("INVALID_COORDINATES", "目标坐标不在本次观察内")
        } catch (error: IllegalStateException) {
            return failure("INVALID_NODE_INDEX", "目标节点不在本次观察内")
        }
        // Coordinates cannot inherit a previous window's text even within the TTL.
        val live = capture()
        if (live != null && (live.displayId != displayId ||
            (current.packageName.isNotBlank() && live.packageName != current.packageName) ||
            (current.windowId != null && live.windowId != current.windowId))) {
            return failure("STALE_WINDOW", "目标窗口已变化，请重新观察")
        }
        val visible = (live?.nodes?.map { listOf(it.text, it.desc).filter(String::isNotBlank).distinct().joinToString(" ") }
            ?: current.nodes.map { it.label }).filter(String::isNotBlank).distinct()
        if (live != null) {
            val liveEvidence = fromSnapshot(owner, runId, revision, current.width, current.height, live)
            val observedNode = index?.let { wanted -> current.nodes.firstOrNull { it.index == wanted } }
            val liveIndex = observedNode?.let { before -> liveEvidence.nodes.firstOrNull { after ->
                before.left == after.left && before.top == after.top && before.right == after.right &&
                    before.bottom == after.bottom && before.label == after.label
            }?.index }
            if ((index != null && liveIndex == null) || GuiRiskPolicy.target(liveEvidence, x, y, liveIndex) != target) {
                return failure("STALE_CONTENT", "目标控件已变化，请重新观察；未重放旧坐标")
            }
        }
        if (!GuiRiskPolicy.shouldInspect(target, visible)) return null
        return reviewer(GuiRiskInspection(current, target, visible, action, x, y, index))
    }

    private fun review(context: Context?, request: GuiRiskInspection): String? {
        val current = request.observation
        val displayKey = approvalDisplayIdentity(current.displayId, current.sessionId, current.routeRevision)
        val operation = org.json.JSONObject().put("action", request.action).put("x", request.x).put("y", request.y)
            .put("package", current.packageName).put("window", current.windowId ?: org.json.JSONObject.NULL)
            .put("node_index", request.nodeIndex ?: org.json.JSONObject.NULL).put("target", request.target).toString()
        return synchronized(AgentConfirmGuard) {
            AgentConfirmGuard.remember(emptyList(), request.visible, current.runId, current.id, displayKey,
                capturedAtElapsedMs = current.capturedAtMs)
            try {
                AgentConfirmGuard.inspect(context, request.target, request.action, current.runId, current.id, displayKey, operation)
                    ?.let(AgentConfirmGuard::blockedResult)
            } finally {
                AgentConfirmGuard.remember(emptyList(), emptyList())
            }
        }
    }

    private fun failure(code: String, message: String): String =
        org.json.JSONObject().put("ok", false).put("code", code).put("message", message).toString()
}
