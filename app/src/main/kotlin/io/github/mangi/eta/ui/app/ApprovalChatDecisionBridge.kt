package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.display.dashboard.AgentConfirmGuard
import io.github.mangi.eta.agent.display.dashboard.ApprovalDecisionSubmitter
import java.lang.ref.WeakReference

/** Guard/confirmation Activity share one adapter; it never creates another state or selects a chat. */
internal object ApprovalChatDecisionBridge {
    @Volatile private var owner = WeakReference<AgentAppState>(null)
    private val submitter = ApprovalDecisionSubmitter { request -> owner.get()?.submitApprovalDecision(request) }

    @Synchronized fun attach(state: AgentAppState) {
        owner = WeakReference(state)
        AgentConfirmGuard.installDecisionSubmitter(submitter)
    }
    @Synchronized fun detach(state: AgentAppState) {
        if (owner.get() === state) owner.clear()
        // Keep the stable adapter installed. No live owner -> no ACK; an older ViewModel cannot
        // remove a newer owner's adapter during configuration changes.
    }
}
