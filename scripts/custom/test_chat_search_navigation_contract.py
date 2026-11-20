#!/usr/bin/env python3
"""Source wiring contracts, not runtime Compose hit-testing/performance measurements."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
UI = ROOT / 'app/src/main/kotlin/io/github/mangi/eta/ui'
def read(path): return (UI / path).read_text()

class SearchNavigationContract(unittest.TestCase):
    def test_menu_has_both_style_entries_and_required_callback(self):
        shell = read('app/AgentAppShell.kt')
        self.assertEqual(5, shell.count('onOpenChatSearch: () -> Unit,'))
        self.assertIn('5 -> onOpenChatSearch()', shell)
        self.assertIn('to onOpenChatSearch)', shell)
    def test_root_home_body_chain_is_state_backed_not_a_registered_hook(self):
        root = read('app/AgentAppRoot.kt')
        home = read('screens/home/AgentHomeScreen.kt')
        body = read('components/AgentChatBody.kt')
        self.assertIn('onOpenChatSearch = { chatSearchRequested = true }', root)
        self.assertIn('searchOpenRequested = chatSearchRequested', root)
        self.assertIn('searchOpenRequested = searchOpenRequested', home)
        self.assertIn('LaunchedEffect(searchOpenRequested)', body)
        self.assertIn('searchState = search', body)
        self.assertIn('onSearchOpenConsumed()', body)
    def test_idle_toolbar_has_no_layout_or_open_button(self):
        toolbar = read('components/ChatSearchToolbar.kt')
        closed = toolbar.split('if (!open)', 1)[1].split('Column(', 1)[0]
        self.assertIn('return', closed)
        self.assertNotIn('Row(', closed)
        self.assertNotIn('ChatSearchButton(', closed)
        body = read('components/AgentChatBody.kt')
        self.assertIn('if (!assistantOverlay && search.open)', body)
    def test_empty_chat_can_open_search(self):
        body = read('components/AgentChatBody.kt')
        self.assertIn('if (!hasMessages && !search.open)', body)
        self.assertLess(body.index('val search = rememberChatSearch(visibleMessages)'), body.index('if (!hasMessages && !search.open)'))
    def test_hidden_search_and_paging_do_not_install_workers(self):
        self.assertIn('if (chatSearchWorkEnabled(state.open, state.query)) LaunchedEffect', read('components/ChatSearchState.kt'))
        self.assertIn('if (open && query.isNotBlank()) LaunchedEffect', read('components/ChatSearchHistoryPaging.kt'))
    def test_close_keeps_stable_timeline_boundaries_and_keys(self):
        body = read('components/AgentChatBody.kt')
        self.assertIn('search.open || timelineCache.searchWasOpened', body)
        self.assertIn('key = { it.key }', body)
        close = body.split('onClose = {', 1)[1].split('},', 1)[0]
        self.assertIn('search.close()', close)
        self.assertNotIn('scrollToItem', close)
        self.assertNotIn('onBottomAnchorChanged(true)', close)
    def test_one_pointer_recognizer_plus_semantic_and_state_delivery(self):
        handle = read('components/ChatNavigatorHandle.kt')
        self.assertIn('awaitEachGesture', handle)
        self.assertNotIn('.clickable(', handle)
        self.assertNotIn('detectVerticalDragGestures', handle)
        self.assertIn('LaunchedEffect(requestVersion)', handle)
        self.assertIn('while (handledVersion < requestVersion)', handle)
        self.assertIn('onClick { currentRequestOpen(); true }', handle)
    def test_topbar_buttons_are_transparent_but_accessible(self):
        shell = read('app/AgentAppShell.kt')
        helper = shell.split('private fun ChatTopBarMenuButton(', 1)[1]
        self.assertIn('size(48.dp).clickable', helper)
        self.assertIn('Role.Button', helper)
        self.assertIn('contentDescription = contentDescription', helper)
        self.assertNotIn('.background(', helper)
        self.assertNotIn('.border(', helper)
        self.assertNotIn('TopAppBarButtonGroup {', shell)
    def test_sheet_hidden_entries_are_not_recomputed(self):
        self.assertIn('if (show) entriesFor(query) else retainedEntries', read('components/ConversationNavigatorSheet.kt'))
    def test_navigator_open_closes_competing_surfaces(self):
        root = read('app/AgentAppRoot.kt')
        arm = root.split('AgentHomeAction.OpenConversationNavigator ->', 1)[1].split('}', 1)[0]
        for statement in ('conversationPaneOpen = false', 'workspaceSheetOpen = false', 'conversationNavigatorOpen = true'):
            self.assertIn(statement, arm)

if __name__ == '__main__': unittest.main(verbosity=2)
