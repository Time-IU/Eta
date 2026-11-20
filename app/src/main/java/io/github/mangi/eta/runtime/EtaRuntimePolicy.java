package io.github.mangi.eta.runtime;

import java.util.HashMap;
import java.util.Map;

/** Pure production parser/policy. No shell, permissions, Android or process-name trust. */
public final class EtaRuntimePolicy {
    private EtaRuntimePolicy() {}

    public static final class Status {
        public final int uid;
        public final Long rssKb;
        Status(int uid, Long rssKb) { this.uid = uid; this.rssKb = rssKb; }
    }
    public static final class Stat {
        public final int pid;
        public final long ticks, startTicks;
        Stat(int pid, long ticks, long startTicks) {
            this.pid = pid; this.ticks = ticks; this.startTicks = startTicks;
        }
    }
    public static Status parseStatus(String text) {
        if (text == null) return null;
        Integer uid = null;
        Long rss = null;
        try {
            for (String line : text.split("\n")) {
                if (line.startsWith("Uid:")) {
                    if (uid != null) return null;
                    String[] parts = line.substring(4).trim().split("\\s+");
                    if (parts.length != 4) return null;
                    int value = Integer.parseInt(parts[0]);
                    if (value < 0) return null;
                    // Reject mixed real/effective/saved/fs UIDs rather than attribute to Eta.
                    for (String part : parts) if (Integer.parseInt(part) != value) return null;
                    uid = value;
                } else if (line.startsWith("VmRSS:")) {
                    String[] parts = line.substring(6).trim().split("\\s+");
                    if (parts.length == 2 && parts[1].equals("kB")) {
                        long value = Long.parseLong(parts[0]);
                        if (value >= 0) rss = value;
                    }
                }
            }
        } catch (NumberFormatException ex) { return null; }
        return uid == null ? null : new Status(uid, rss);
    }
    public static Stat parseStat(String text) {
        if (text == null) return null;
        try {
            int open = text.indexOf('('), close = text.lastIndexOf(')');
            if (open < 1 || close <= open) return null;
            int pid = Integer.parseInt(text.substring(0, open).trim());
            String[] fields = text.substring(close + 1).trim().split("\\s+");
            if (pid <= 0 || fields.length < 20 || fields[0].length() != 1) return null;
            long user = Long.parseLong(fields[11]), system = Long.parseLong(fields[12]);
            long start = Long.parseLong(fields[19]);
            if (user < 0 || system < 0 || start < 0) return null;
            return new Stat(pid, Math.addExact(user, system), start);
        } catch (NumberFormatException | ArithmeticException ex) { return null; }
    }
    public static boolean belongsToUid(Status status, int uid) {
        return status != null && uid >= 0 && status.uid == uid;
    }
    public static boolean sameIdentity(Stat before, Stat after) {
        return before != null && after != null && before.pid == after.pid
                && before.startTicks == after.startTicks;
    }
    /** Single logical CPU = 100%; multi-threaded processes may exceed 100%. No clamping. */
    public static final class CpuTracker {
        private static final class Point {
            final Stat stat;
            final long nanos;
            Point(Stat stat, long nanos) { this.stat = stat; this.nanos = nanos; }
        }
        private final Map<Integer, Point> previous = new HashMap<>();
        public Double sample(Stat stat, long monotonicNanos, long ticksPerSecond) {
            if (stat == null || monotonicNanos < 0 || ticksPerSecond <= 0) return null;
            Point old = previous.put(stat.pid, new Point(stat, monotonicNanos));
            if (old == null || !sameIdentity(old.stat, stat)
                    || monotonicNanos <= old.nanos || stat.ticks < old.stat.ticks) return null;
            double result = (stat.ticks - old.stat.ticks) * 100.0 * 1_000_000_000.0
                    / ticksPerSecond / (monotonicNanos - old.nanos);
            return Double.isFinite(result) && result >= 0 ? result : null;
        }
        public void retain(int[] pids) {
            java.util.Set<Integer> live = new java.util.HashSet<>();
            for (int pid : pids) live.add(pid);
            previous.keySet().retainAll(live);
        }
        public void clear() { previous.clear(); }
    }
    /** Never turn all-unavailable into an apparent measured zero. */
    public static Long sumKnown(Long[] values) {
        Long result = null;
        try {
            for (Long value : values) {
                if (value == null || value < 0) continue;
                result = Math.addExact(result == null ? 0L : result, value);
            }
            return result;
        } catch (ArithmeticException ex) { return null; }
    }
}
