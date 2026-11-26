package io.github.mangi.eta.ui.app

import io.github.mangi.eta.ui.model.AgentMemoryAction
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentMemoryDocumentActionsTest {
    @Test fun noticeDismissFromDocumentRouteReachesMemoryStoreCallback() {
        val calls = mutableListOf<String>()
        dispatchMemoryDocumentAction(
            action = AgentMemoryAction.DismissNotice,
            onDraftChanged = { calls += "draft:$it" },
            onSave = { calls += "save" },
            onClear = { calls += "clear" },
            onNavigateBack = { calls += "back" },
            onDismissNotice = { calls += "dismiss" },
        )
        assertEquals(listOf("dismiss"), calls)
    }
}
