package io.github.mangi.eta.runtime;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;
import static io.github.mangi.eta.runtime.EtaRuntimeDisplay.*;

/** Executes the exact production display projection used by EtaRuntimeSheet; no source-string assertions. */
public class EtaRuntimeDisplayTest {
    private Input input(int pid, Long pss, Long rss, Double cpu, boolean readable, Long age) {
        return new Input(pid, 10L, "process", pid == 1, pss, rss, cpu, readable, age);
    }
    private View view(Input... rows) { return project(Arrays.asList(rows), true, true, false); }
    private Metric metric(View view, Kind kind) {
        return view.summary.stream().filter(m -> m.kind == kind).findFirst().orElse(null);
    }
    @Test public void allUnknownProducesEmptyNotZeros() {
        View v = view(input(1,null,null,null,false,null),input(2,null,null,null,false,null));
        assertEquals(State.EMPTY,v.state); assertTrue(v.summary.isEmpty()); assertTrue(v.rows.isEmpty());
    }
    @Test public void noProcessesProducesEmpty() { assertEquals(State.EMPTY,view().state); }
    @Test public void firstCpuSampleHasLoadingStateNotFakeZero() {
        View v = view(input(1,null,null,null,true,null));
        assertEquals(State.LOADING,v.state); assertTrue(v.summary.isEmpty());
    }
    @Test public void validMemoryAndFirstCpuSampleShowOnlyMemory() {
        View v = view(input(1,32L,64L,null,true,0L));
        assertEquals(State.READY,v.state); assertEquals(2,v.summary.size()); assertNull(metric(v,Kind.CPU));
    }
    @Test public void eachUnavailableMetricIsOmittedIndependently() {
        View v = view(input(1,null,64L,null,false,null));
        assertEquals(1,v.rows.get(0).metrics.size()); assertEquals(Kind.RSS,v.summary.get(0).kind);
    }
    @Test public void partialCountsIncludeObservedUnknownProcesses() {
        View v = view(input(1,32L,null,5.0,true,0L),input(2,null,64L,null,false,null),input(3,null,null,null,false,null));
        assertEquals(3,v.observedProcesses); assertEquals(2,v.rows.size());
        for(Metric m:v.summary) { assertEquals(1,m.available); assertEquals(3,m.total); }
    }
    @Test public void differentPartialSetsHaveIndependentCounts() {
        View v = view(input(1,32L,64L,null,true,0L),input(2,null,20L,12.0,true,null));
        assertEquals(1,metric(v,Kind.PSS).available); assertEquals(2,metric(v,Kind.RSS).available);
        assertEquals(1,metric(v,Kind.CPU).available); assertEquals(84L,metric(v,Kind.RSS).value.longValue());
    }
    @Test public void idleCpuZeroAndRssZeroRemainMeasurements() {
        View v = view(input(1,null,0L,0.0,true,null));
        assertEquals(0.0,metric(v,Kind.CPU).value.doubleValue(),0); assertEquals(0L,metric(v,Kind.RSS).value.longValue());
    }
    @Test public void androidZeroedPssNotAMeasurement() {
        assertNull(metric(view(input(1,0L,0L,0.0,true,0L)),Kind.PSS));
    }
    @Test public void negativeValuesHidden() {
        View v = view(input(1,-1L,-1L,-1.0,true,0L));
        assertTrue(v.rows.isEmpty()); assertEquals(State.EMPTY,v.state);
    }
    @Test public void nanCpuHiddenWithoutHidingMemory() {
        View v = view(input(1,32L,null,Double.NaN,true,0L));
        assertNull(metric(v,Kind.CPU)); assertNotNull(metric(v,Kind.PSS));
    }
    @Test public void infiniteCpuHidden() {
        assertNull(metric(view(input(1,null,null,Double.POSITIVE_INFINITY,true,null)),Kind.CPU));
        assertNull(metric(view(input(1,null,null,Double.NEGATIVE_INFINITY,true,null)),Kind.CPU));
    }
    @Test public void unreadableCpuNeverRetainsOldNumber() {
        assertNull(metric(view(input(1,null,null,25.0,false,null)),Kind.CPU));
    }
    @Test public void multicoreCpuNotClamped() {
        assertEquals(375.0,metric(view(input(1,null,null,375.0,true,null)),Kind.CPU).value.doubleValue(),0);
    }
    @Test public void cpuAggregateOverflowNotInfinity() {
        View v = view(input(1,null,null,Double.MAX_VALUE,true,null),input(2,null,null,Double.MAX_VALUE,true,null));
        assertNull(metric(v,Kind.CPU)); assertEquals(2,v.rows.size());
    }
    @Test public void memoryAggregateOverflowNotNegative() {
        View v = view(input(1,Long.MAX_VALUE,Long.MAX_VALUE,null,false,0L),input(2,1L,1L,null,false,0L));
        assertTrue(v.summary.isEmpty()); assertEquals(2,v.rows.size());
    }
    @Test public void preservesExactLongMemoryValue() {
        long exact=9_007_199_254_740_993L;
        assertEquals(exact,metric(view(input(1,exact,null,null,false,0L)),Kind.PSS).value.longValue());
    }
    @Test public void cachedPssAgeRetainedAndMaxAgeInSummary() {
        View v = view(input(1,32L,null,null,false,1500L),input(2,20L,null,null,false,4500L));
        assertEquals(Long.valueOf(4500),metric(v,Kind.PSS).requestAgeMs);
        assertEquals(Long.valueOf(1500),v.rows.get(0).metrics.get(0).requestAgeMs);
    }
    @Test public void stalePssHiddenWithoutHidingLiveRssAndCpu() {
        View v = view(input(1,32L,64L,2.0,true,6001L));
        assertNull(metric(v,Kind.PSS)); assertNotNull(metric(v,Kind.RSS)); assertNotNull(metric(v,Kind.CPU));
    }
    @Test public void unknownOrNegativePssAgeHidden() {
        assertNull(metric(view(input(1,32L,null,null,false,null)),Kind.PSS));
        assertNull(metric(view(input(1,32L,null,null,false,-1L)),Kind.PSS));
    }
    @Test public void pssRefreshBoundaryStillVisible() {
        assertNotNull(metric(view(input(1,32L,null,null,false,6000L)),Kind.PSS));
    }
    @Test public void stoppedSessionCannotShowOldData() {
        View v=project(Collections.singletonList(input(1,32L,64L,3.0,true,0L)),false,true,false);
        assertEquals(State.LOADING,v.state); assertTrue(v.rows.isEmpty()); assertTrue(v.summary.isEmpty());
    }
    @Test public void unsampledSessionCannotShowOldData() {
        View v=project(Collections.singletonList(input(1,32L,64L,3.0,true,0L)),true,false,false);
        assertEquals(State.LOADING,v.state); assertTrue(v.rows.isEmpty());
    }
    @Test public void failedSampleCannotShowOldData() {
        View v=project(Collections.singletonList(input(1,32L,64L,3.0,true,0L)),true,true,true);
        assertEquals(State.EMPTY,v.state); assertTrue(v.rows.isEmpty());
    }
    @Test public void replacementProjectionDoesNotCarryMissingMetricsOrProcess() {
        View old=view(input(1,32L,64L,3.0,true,0L));
        View next=view(input(2,null,5L,null,true,null));
        assertEquals(1,old.rows.size()); assertEquals(2,next.rows.get(0).process.pid);
        assertNull(metric(next,Kind.PSS)); assertNull(metric(next,Kind.CPU));
    }
    @Test public void selectedCaptionSurvivesNullTransitionWithoutKeepingOldValue() {
        for (Kind kind : Kind.values()) {
            View available = view(input(1,32L,64L,3.0,true,0L));
            MetricDetail before = metricDetail(available, kind);
            assertEquals(kind,before.caption); assertFalse(before.isEmpty());
            View missing = view(input(1,null,null,null,false,null));
            MetricDetail after = metricDetail(missing, kind);
            assertEquals(kind,after.caption); assertTrue(after.isEmpty()); assertNull(after.current);
            assertTrue(missing.summary.isEmpty()); // detail caption must not restore main-panel rows
        }
    }
    @Test public void failedAndInactiveDetailsKeepCaptionButNotNumbers() {
        for (Kind kind : Kind.values()) {
            for (View missing : Arrays.asList(
                    project(Collections.singletonList(input(1,32L,64L,3.0,true,0L)),true,true,true),
                    project(Collections.singletonList(input(1,32L,64L,3.0,true,0L)),false,true,false))) {
                MetricDetail detail=metricDetail(missing,kind);
                assertEquals(kind,detail.caption); assertNull(detail.current); assertTrue(detail.isEmpty());
            }
        }
    }
    @Test public void partialTransitionDoesNotSubstituteAnotherMetric() {
        View rssOnly=view(input(1,null,64L,null,false,null));
        assertTrue(metricDetail(rssOnly,Kind.PSS).isEmpty());
        assertTrue(metricDetail(rssOnly,Kind.CPU).isEmpty());
        assertEquals(64L,metricDetail(rssOnly,Kind.RSS).current.value.longValue());
        assertEquals(1,rssOnly.summary.size());
    }
    @Test public void validZeroDetailIsNotEmptyAndCanRecoverAfterNull() {
        View zero=view(input(1,null,0L,0.0,true,null));
        for (Kind kind : Arrays.asList(Kind.RSS,Kind.CPU)) {
            assertTrue(metricDetail(view(),kind).isEmpty());
            MetricDetail recovered=metricDetail(zero,kind);
            assertEquals(kind,recovered.caption); assertFalse(recovered.isEmpty());
            assertEquals(0.0,recovered.current.value.doubleValue(),0);
        }
    }
}
