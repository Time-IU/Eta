package io.github.mangi.eta.ui.app

import io.github.mangi.eta.ui.model.AgentMemoryAction

/** Keeps the document editor's complete action routing explicit at its navigation boundary. */
internal fun dispatchMemoryDocumentAction(
    action: AgentMemoryAction,
    onDraftChanged: (String) -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
    onNavigateBack: () -> Unit,
    onDismissNotice: () -> Unit,
) {
    when (action) {
        is AgentMemoryAction.DraftChanged -> onDraftChanged(action.content)
        AgentMemoryAction.Save -> onSave()
        AgentMemoryAction.Clear -> onClear()
        AgentMemoryAction.NavigateBack -> onNavigateBack()
        AgentMemoryAction.DismissNotice -> onDismissNotice()
        else -> Unit
    }
}
