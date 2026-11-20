package io.github.mangi.eta.agent.security
import org.junit.Assert.*
import org.junit.Test
class PromptSafetyContractTest {
 @Test fun actualProductionBlockSatisfiesItsOwnContract() {
  assertEquals(PromptSafetyContract.Result.CURRENT_VALID, PromptSafetyContract.assess(PromptSafetyContract.systemBlock,1,true))
 }
 @Test fun everyMissingRuleStillFailsCritically() {
  PromptSafetyContract.anchors.forEach { anchor ->
   val damaged=PromptSafetyContract.systemBlock.replace(anchor,"")
   assertEquals(PromptSafetyContract.Result.CURRENT_MISSING, PromptSafetyContract.assess(damaged,1,true))
   assertTrue(PromptSafetyContract.missing(damaged).contains(anchor))
  }
 }
 @Test fun oldSnapshotsAreNotMisreportedAsTamperingOrHealthy() {
  assertEquals(PromptSafetyContract.Result.LEGACY_REQUIRES_NEW_SNAPSHOT,PromptSafetyContract.assess("old",-1,true))
 }
 @Test fun chatAndSummaryHaveExplicitDifferentScope() {
  assertEquals(PromptSafetyContract.Result.NON_AGENT_REQUEST,PromptSafetyContract.assess("chat",1,false))
 }
}
