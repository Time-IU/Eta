#!/usr/bin/env python3
"""Source integration contracts only, not visual/device acceptance."""
from pathlib import Path
import unittest,xml.etree.ElementTree as E
R=Path(__file__).resolve().parents[2]
K=R/'app/src/main/kotlin/io/github/mangi/eta'
def read(p):return (K/p).read_text()
class CompactContract(unittest.TestCase):
 def test_runtime_wraps_and_keeps_real_sampler(self):
  s=read('ui/components/EtaRuntimeSheet.kt')
  self.assertNotIn('.height(availableHeight * 0.58f)',s)
  self.assertIn('ComposerPanelDialog(show, onDismiss',s)
  self.assertIn('RuntimeScrollColumn(',s)
  self.assertIn('repeatOnLifecycle(Lifecycle.State.STARTED)',s)
  self.assertIn('snapshot = monitor.sample()',s)
  self.assertIn('CompactMetricRow(',s)
  row=read('ui/components/CompactMetricRow.kt')
  self.assertIn('Spacer(Modifier.widthIn(min = 12.dp).weight(1f))',row)
  self.assertIn('eta_footprint_entry',read('ui/components/AgentChatInputBar.kt'))
 def test_usage_does_not_stack_every_row_for_fontscale(self):
  s=read('ui/components/UnifiedUsageSheet.kt')
  self.assertNotIn('fontScale > 1.35f',s)
  self.assertIn('CompactMetricRow(label, value',s)
  self.assertNotIn('val stacked',s)
  self.assertIn('compactUsageMetricLabel',s)
  self.assertIn('usageMetricExplanation(metric)',s)
  self.assertNotIn('Text(stringResource(note)',s)
  self.assertEqual(1,s.count('if (canCompact) UsageAction'))
 def test_tools_routes_share_host_without_resetting_stores(self):
  root=read('ui/app/AgentAppRoot.kt')
  self.assertEqual(2,root.count('io.github.mangi.eta.ui.screens.tools.ToolManagementScreen('))
  host=read('ui/screens/tools/ToolManagementScreen.kt')
  self.assertIn('CouiSegmentedTabs(',host);self.assertIn('basic.TabRow(',host)
  self.assertIn('if (dirty)',host)
  self.assertNotIn('AgentToolPolicyStore.save',host)
  self.assertNotIn('Prefs.setTool',host)
  self.assertIn('onDirtyChanged = { dirty = it }',host)
 def test_prompt_contract_shared_and_versioned_not_disabled(self):
  s=read('agent/model/AgentPromptBuilder.kt')
  self.assertIn('PromptSafetyContract.systemBlock',s)
  guard=read('agent/security/PromptIntegrityGuard.kt')
  self.assertIn('PromptSafetyContract.assess',guard)
  self.assertIn('IntegrityLevel.CRITICAL',guard)
  self.assertIn('PROMPT_SNAPSHOT_LEGACY',guard)
  self.assertIn('scanPlantedFiles(context)',guard)
  self.assertIn('scanLatestSnapshot(context)',guard)
  snap=read('agent/model/AgentRequestSnapshot.kt')
  for field in ['prompt_contract_version','app_version','agent_guard_applicable']:
   self.assertIn(field,snap)
 def test_promotion_is_request_not_forged_system_confirmation(self):
  s=read('agent/runtime/LiveUpdateContract.kt')
  self.assertIn('liveUpdateRequiresColorization',s)
  self.assertIn('setColorized(candidate).build().hasPromotableCharacteristics()',s)
  self.assertNotIn('setColorized(true)',s)
  self.assertIn('setRequestPromotedOngoing(true)',s)
  self.assertNotIn('FLAG_PROMOTED_ONGOING',s)
  diagnostic=read('ui/FluidPromotionStatus.kt')
  self.assertIn('manager.activeNotifications',diagnostic)
  self.assertIn('hasPromotableCharacteristics()',diagnostic)
  self.assertNotIn('.notify(',diagnostic)
  self.assertNotIn('appops',diagnostic)
 def test_locales_have_consistent_new_keys(self):
  sets=[]
  for loc in ['values','values-b+zh+Hans','values-b+zh+Hant']:
   root=E.parse(R/'app/src/main/res'/loc/'ui_compact.xml').getroot()
   keys=[x.get('name') for x in root];self.assertEqual(len(keys),len(set(keys)));sets.append(set(keys))
  self.assertEqual(sets[0],sets[1]);self.assertEqual(sets[1],sets[2])
if __name__=='__main__':unittest.main(verbosity=2)
