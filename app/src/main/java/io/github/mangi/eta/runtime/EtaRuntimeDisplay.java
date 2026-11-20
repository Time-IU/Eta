package io.github.mangi.eta.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Display-only projection of already UID/PID/start-verified monitor rows. Never discovers processes. */
public final class EtaRuntimeDisplay {
    private EtaRuntimeDisplay() {}
    public enum Kind { PSS, RSS, CPU }
    public enum State { READY, LOADING, EMPTY }
    // Matches the monitor's existing PSS refresh interval, not a new sampling timer.
    public static final long MAX_PSS_REQUEST_AGE_MS = 6_000;
    public static final class Input {
        public final int pid;
        public final Long startTicks;
        public final String name;
        public final boolean main, cpuReadable;
        public final Long pssKb, rssKb, memoryAgeMs;
        public final Double cpuPercent;
        public Input(int pid, Long startTicks, String name, boolean main, Long pssKb,
                Long rssKb, Double cpuPercent, boolean cpuReadable, Long memoryAgeMs) {
            this.pid = pid; this.startTicks = startTicks; this.name = name; this.main = main;
            this.pssKb = pssKb; this.rssKb = rssKb; this.cpuPercent = cpuPercent;
            this.cpuReadable = cpuReadable; this.memoryAgeMs = memoryAgeMs;
        }
    }
    public static final class Metric {
        public final Kind kind;
        public final Number value;
        public final int available, total;
        public final Long requestAgeMs;
        Metric(Kind kind, Number value, int available, int total, Long age) {
            this.kind = kind; this.value = value; this.available = available;
            this.total = total; this.requestAgeMs = age;
        }
    }
    public static final class Row {
        public final Input process;
        public final List<Metric> metrics;
        Row(Input process, List<Metric> metrics) {
            this.process = process; this.metrics = Collections.unmodifiableList(metrics);
        }
    }
    public static final class View {
        public final State state;
        public final List<Metric> summary;
        public final List<Row> rows;
        public final int observedProcesses;
        View(State state, List<Metric> summary, List<Row> rows, int observedProcesses) {
            this.state = state; this.summary = Collections.unmodifiableList(summary);
            this.rows = Collections.unmodifiableList(rows); this.observedProcesses = observedProcesses;
        }
    }
    /** Selection caption survives unavailable samples; the current value never does. */
    public static final class MetricDetail {
        public final Kind caption;
        public final Metric current;
        MetricDetail(Kind caption, Metric current) { this.caption = caption; this.current = current; }
        public boolean isEmpty() { return current == null; }
    }
    public static MetricDetail metricDetail(View view, Kind selection) {
        if (selection == null) throw new IllegalArgumentException("Metric selection required");
        Metric current = null;
        for (Metric metric : view.summary) if (metric.kind == selection) { current = metric; break; }
        return new MetricDetail(selection, current);
    }
    public static View project(List<Input> inputs, boolean active, boolean sampled, boolean failed) {
        if (failed) return new View(State.EMPTY, new ArrayList<>(), new ArrayList<>(), 0);
        if (!active || !sampled) return new View(State.LOADING, new ArrayList<>(), new ArrayList<>(), 0);
        List<Row> rows = new ArrayList<>();
        boolean awaitingCpu = false;
        for (Input input : inputs) {
            List<Metric> metrics = new ArrayList<>();
            // Android denied/zeroed PSS must not become measured zero. RSS/idle CPU zero are valid.
            if (input.pssKb != null && input.pssKb > 0 && input.memoryAgeMs != null
                    && input.memoryAgeMs >= 0 && input.memoryAgeMs <= MAX_PSS_REQUEST_AGE_MS) {
                metrics.add(new Metric(Kind.PSS, input.pssKb, 1, 1, input.memoryAgeMs));
            }
            if (input.rssKb != null && input.rssKb >= 0)
                metrics.add(new Metric(Kind.RSS, input.rssKb, 1, 1, null));
            if (input.cpuReadable && input.cpuPercent != null && Double.isFinite(input.cpuPercent)
                    && input.cpuPercent >= 0)
                metrics.add(new Metric(Kind.CPU, input.cpuPercent, 1, 1, null));
            awaitingCpu |= input.cpuReadable && input.cpuPercent == null;
            if (!metrics.isEmpty()) rows.add(new Row(input, metrics));
        }
        List<Metric> summary = new ArrayList<>();
        for (Kind kind : Kind.values()) {
            int count = 0;
            long memory = 0;
            double cpu = 0;
            Long age = null;
            boolean overflow = false;
            for (Row row : rows) for (Metric metric : row.metrics) if (metric.kind == kind) {
                count++;
                if (kind == Kind.CPU) cpu += metric.value.doubleValue();
                else try { memory = Math.addExact(memory, metric.value.longValue()); }
                catch (ArithmeticException ex) { overflow = true; }
                if (metric.requestAgeMs != null)
                    age = age == null ? metric.requestAgeMs : Math.max(age, metric.requestAgeMs);
            }
            if (count > 0 && !overflow && (kind != Kind.CPU || Double.isFinite(cpu))) {
                Number value;
                if (kind == Kind.CPU) value = Double.valueOf(cpu);
                else value = Long.valueOf(memory);
                summary.add(new Metric(kind, value, count, inputs.size(), age));
            }
        }
        State state = !rows.isEmpty() ? State.READY : awaitingCpu ? State.LOADING : State.EMPTY;
        return new View(state, summary, rows, inputs.size());
    }
}
