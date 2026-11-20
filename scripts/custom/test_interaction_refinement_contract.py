#!/usr/bin/env python3
"""Source-wiring checks only; not device touch, layout or network acceptance."""
from pathlib import Path
import re,unittest,xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[2]
K=ROOT/'app/src/main/kotlin/io/github/mangi/eta'
def read(p):return (K/p).read_text()
class RefinementContract(unittest.TestCase):
 def test_unknown_mcp_does_not_weaken_local_execution_contract(self):
  ui=read('ui/screens/prompts/ToolSelectionItems.kt');executor=read('agent/tool/AgentToolRequirements.kt')
  self.assertIn('AgentToolRequirements.find(name)?.rootRequirement?.name ?: "EXTERNAL"',ui)
  self.assertIn('"REQUIRED", "EXTERNAL"',ui)
  self.assertIn('requireNotNull(find(name))',executor)
 def test_workspace_is_menu_only_and_history_is_edge_only(self):
  pane=read('ui/components/ConversationSidePaneScaffold.kt');shell=read('ui/app/AgentAppShell.kt')
  self.assertNotIn('ConversationPaneAnchor.Workspace at',pane)
  self.assertNotIn('.anchoredDraggable(',pane)
  self.assertIn('workspaceVisible && workspaceContent != null',pane)
  self.assertIn('ChatGesturePolicy.canStart(',pane)
  self.assertIn('selectionLock.active',pane)
  self.assertIn('6 -> onOpenWorkspaceMenu()',shell)
  self.assertIn('to onOpenWorkspaceMenu)',shell)
  self.assertEqual(2,shell.count('onOpenWorkspaceMenu = onWorkspaceOpen'))
 def test_text_selection_lock_is_scoped_not_global_boolean(self):
  menu=read('ui/components/custom/CustomMessageMenu.kt')
  self.assertIn('DisposableEffect(paneSelectionLock, selectionToken, selecting)',menu)
  self.assertIn('acquire(selectionToken)',menu);self.assertIn('release(selectionToken)',menu)
  self.assertIn('LocalChatSelectionLock provides selectionLock',read('ui/components/ConversationSidePaneScaffold.kt'))
 def test_usage_has_question_marks_and_existing_accounting(self):
  card=read('ui/components/UnifiedUsageSheet.kt')
  self.assertIn('unifiedUsageSections(usage)',card)
  self.assertIn('HelpOutline',card);self.assertIn('usageMetricExplanation(metric)',card)
  self.assertIn('textAlign = TextAlign.End',card)
  self.assertIn('onDismiss(); onCompact()',card)
  self.assertEqual(1,card.count('if (canCompact) UsageAction'))
  self.assertIn('sections.forEach',card)
 def test_status_does_not_replace_long_explanation_on_each_save(self):
  slot=read('ui/screens/tasks/CanvasSettingsStatusSlot.kt');ds=read('data/datastore/SettingsDataStore.kt')
  self.assertIn('Text(stringResource(R.string.refine_canvas_explanation)',slot)
  selected=slot.split('val selected = when {',1)[1].split('}',1)[0]
  self.assertNotIn('saving ->',selected)
  self.assertIn('horizontalAlignment = Alignment.CenterHorizontally',slot)
  self.assertIn('textAlign = TextAlign.Center',slot)
  self.assertNotIn('mutableCanvasGateSync.value = CanvasGateSync.UNAVAILABLE\n                val effective',ds)
  self.assertIn('mutableCanvasGateSync.value = CanvasGateSync.FAILED',ds)
 def test_update_keeps_origin_tls_and_distinct_errors(self):
  checker=read('data/update/AppUpdateChecker.kt');settings=read('ui/SettingsScreen.kt')
  self.assertIn('response.header("X-RateLimit-Remaining") == "0"',checker)
  self.assertIn('.followRedirects(false)',checker)
  self.assertIn('UpdateFailureKind.RATE_LIMIT',settings)
  self.assertIn('openUrl(PersonalDistribution.RELEASES_URL)',settings)
 def test_no_obsolete_workspace_or_white_bar_tutorial(self):
  tour=read('ui/onboarding/GestureCoachScript.kt')
  self.assertNotIn('key = OnboardingKeys.SwipeLeft',tour)
  self.assertNotIn('key = OnboardingKeys.SwipeUp',tour)
  self.assertNotRegex(read('ui/components/AgentChatBody.kt'),r'\bChatNavigatorHandle\s*\(')
 def test_new_resources_cover_three_languages(self):
  keys=[]
  for loc in ['values','values-b+zh+Hans','values-b+zh+Hant']:
   root=ET.parse(ROOT/'app/src/main/res'/loc/'interaction_refinement.xml').getroot()
   names=[x.get('name') for x in root];self.assertEqual(len(names),len(set(names)));keys.append(set(names))
  self.assertEqual(keys[0],keys[1]);self.assertEqual(keys[1],keys[2])
if __name__=='__main__':unittest.main(verbosity=2)
