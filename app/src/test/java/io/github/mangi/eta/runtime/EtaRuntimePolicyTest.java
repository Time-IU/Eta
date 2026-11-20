package io.github.mangi.eta.runtime;

import org.junit.Test;
import static org.junit.Assert.*;

/** Exercises the production parser and CPU/UID policy, not a duplicated test implementation. */
public class EtaRuntimePolicyTest {
    private static String stat(int pid, long user, long kernel, long start) {
        return pid + " (worker ) name) S 1 2 3 4 5 6 7 8 9 10 " + user + " " + kernel
                + " 0 0 0 0 1 0 " + start + " 0 0\n";
    }
    @Test public void statusParsesUidAndRss() {
        EtaRuntimePolicy.Status value = EtaRuntimePolicy.parseStatus("Name:\tprivate\nUid:\t10474 10474 10474 10474\nVmRSS:\t2048 kB\n");
        assertNotNull(value); assertEquals(10474, value.uid); assertEquals(Long.valueOf(2048), value.rssKb);
    }
    @Test public void mixedUidIsNotApp() {
        assertNull(EtaRuntimePolicy.parseStatus("Uid: 10474 0 10474 10474\n"));
    }
    @Test public void missingUidRejectsEvenMatchingProcessName() {
        assertFalse(EtaRuntimePolicy.belongsToUid(EtaRuntimePolicy.parseStatus("Name: io.github.mangi.eta\nVmRSS: 10 kB"),10474));
    }
    @Test public void otherUidNeverIncluded() {
        assertFalse(EtaRuntimePolicy.belongsToUid(EtaRuntimePolicy.parseStatus("Uid: 0 0 0 0"),10474));
    }
    @Test public void missingRssNotZero() {
        assertNull(EtaRuntimePolicy.parseStatus("Uid: 1 1 1 1").rssKb);
    }
    @Test public void wrongRssUnitNotAccepted() {
        assertNull(EtaRuntimePolicy.parseStatus("Uid: 1 1 1 1\nVmRSS: 20 MB").rssKb);
    }
    @Test public void duplicateUidRejected() {
        assertNull(EtaRuntimePolicy.parseStatus("Uid: 1 1 1 1\nUid: 2 2 2 2"));
    }
    @Test public void malformedAndOverflowStatusRejected() {
        assertNull(EtaRuntimePolicy.parseStatus(null));
        assertNull(EtaRuntimePolicy.parseStatus("Uid: nope"));
        assertNull(EtaRuntimePolicy.parseStatus("Uid: 99999999999999 1 1 1"));
    }
    @Test public void statUsesLastParenAndCorrectFields() {
        EtaRuntimePolicy.Stat value = EtaRuntimePolicy.parseStat(stat(42, 20, 5, 1234));
        assertNotNull(value); assertEquals(42, value.pid); assertEquals(25, value.ticks); assertEquals(1234, value.startTicks);
    }
    @Test public void truncatedMalformedAndOverflowStatRejected() {
        assertNull(EtaRuntimePolicy.parseStat("4 (x) S 0"));
        assertNull(EtaRuntimePolicy.parseStat(null));
        assertNull(EtaRuntimePolicy.parseStat(stat(42, Long.MAX_VALUE, 1, 1)));
        assertNull(EtaRuntimePolicy.parseStat(stat(42, -1, 1, 1)));
        assertNull(EtaRuntimePolicy.parseStat(stat(0, 1, 1, 1)));
    }
    @Test public void firstSampleWaits() {
        assertNull(new EtaRuntimePolicy.CpuTracker().sample(EtaRuntimePolicy.parseStat(stat(42,1,1,1)), 1_000_000_000L,100));
    }
    @Test public void correctSingleCoreAndKernelDelta() {
        EtaRuntimePolicy.CpuTracker cpu = new EtaRuntimePolicy.CpuTracker();
        cpu.sample(EtaRuntimePolicy.parseStat(stat(42,100,10,1)), 1_000_000_000L,100);
        assertEquals(100.0,cpu.sample(EtaRuntimePolicy.parseStat(stat(42,250,60,1)), 3_000_000_000L,100),0.00001);
    }
    @Test public void multicoreNotClamped() {
        EtaRuntimePolicy.CpuTracker cpu = new EtaRuntimePolicy.CpuTracker();
        cpu.sample(EtaRuntimePolicy.parseStat(stat(42,0,0,1)),0,100);
        assertEquals(250.0,cpu.sample(EtaRuntimePolicy.parseStat(stat(42,250,0,1)),1_000_000_000L,100),0.00001);
    }
    @Test public void measuredIdleZeroIsValid() {
        EtaRuntimePolicy.CpuTracker cpu = new EtaRuntimePolicy.CpuTracker();
        cpu.sample(EtaRuntimePolicy.parseStat(stat(42,2,0,1)),0,100);
        assertEquals(0.0,cpu.sample(EtaRuntimePolicy.parseStat(stat(42,2,0,1)),1_000_000_000L,100),0.0);
    }
    @Test public void pidReuseResets() {
        EtaRuntimePolicy.CpuTracker cpu = new EtaRuntimePolicy.CpuTracker();
        cpu.sample(EtaRuntimePolicy.parseStat(stat(42,2,0,1)),0,100);
        assertNull(cpu.sample(EtaRuntimePolicy.parseStat(stat(42,999,0,2)),1_000_000_000L,100));
        assertEquals(1.0,cpu.sample(EtaRuntimePolicy.parseStat(stat(42,1000,0,2)),2_000_000_000L,100),0.00001);
    }
    @Test public void rollbackAndSameTimeWait() {
        EtaRuntimePolicy.CpuTracker cpu = new EtaRuntimePolicy.CpuTracker();
        cpu.sample(EtaRuntimePolicy.parseStat(stat(42,20,0,1)),100,100);
        assertNull(cpu.sample(EtaRuntimePolicy.parseStat(stat(42,21,0,1)),100,100));
        assertNull(cpu.sample(EtaRuntimePolicy.parseStat(stat(42,22,0,1)),99,100));
        assertNull(cpu.sample(EtaRuntimePolicy.parseStat(stat(42,1,0,1)),200,100));
    }
    @Test public void invalidClockDoesNotProduceZero() {
        EtaRuntimePolicy.CpuTracker cpu = new EtaRuntimePolicy.CpuTracker();
        assertNull(cpu.sample(EtaRuntimePolicy.parseStat(stat(42,1,0,1)),0,0));
        assertNull(cpu.sample(EtaRuntimePolicy.parseStat(stat(42,1,0,1)),-1,100));
    }
    @Test public void removedProcessesHaveNoStaleCpu() {
        EtaRuntimePolicy.CpuTracker cpu = new EtaRuntimePolicy.CpuTracker();
        cpu.sample(EtaRuntimePolicy.parseStat(stat(42,1,0,1)),0,100);
        cpu.retain(new int[] {43});
        assertNull(cpu.sample(EtaRuntimePolicy.parseStat(stat(42,100,0,1)),1_000_000_000L,100));
    }
    @Test public void unavailableTotalNotFakeZero() {
        assertNull(EtaRuntimePolicy.sumKnown(new Long[] {null,null}));
        assertNull(EtaRuntimePolicy.sumKnown(new Long[] {}));
        assertEquals(Long.valueOf(10),EtaRuntimePolicy.sumKnown(new Long[] {null,4L,6L}));
        assertEquals(Long.valueOf(0),EtaRuntimePolicy.sumKnown(new Long[] {0L}));
        assertNull(EtaRuntimePolicy.sumKnown(new Long[] {Long.MAX_VALUE,1L}));
    }
    @Test public void stableIdentityRequiresBothPidAndStart() {
        assertFalse(EtaRuntimePolicy.sameIdentity(EtaRuntimePolicy.parseStat(stat(42,0,0,1)),EtaRuntimePolicy.parseStat(stat(43,0,0,1))));
        assertFalse(EtaRuntimePolicy.sameIdentity(null,null));
    }
}
