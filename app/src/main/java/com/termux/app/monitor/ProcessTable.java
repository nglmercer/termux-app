package com.termux.app.monitor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Immutable, local presentation data; unknown measurements sort after known values. */
public final class ProcessTable {
    public enum Sort { PID, NAME, CPU, RSS }
    public enum Scope { OWNED, SESSION, TASK, ALL_VISIBLE }
    public static final class Row {
        public final ProcessSnapshot.Entry entry;
        public final Long rssKiB;
        public final Double cpuPercent;
        public final boolean session, task;
        private Row(ProcessSnapshot.Entry entry, Long rssKiB, Double cpuPercent, boolean session, boolean task) {
            this.entry = entry; this.rssKiB = rssKiB; this.cpuPercent = cpuPercent; this.session = session; this.task = task;
        }
    }
    private final Map<Sort, List<Row>> sorted;
    public ProcessTable(ProcessSnapshot current, ProcessSnapshot previous, long elapsedMillis, long ticksPerSecond, long pageSize) {
        this(current, previous, elapsedMillis, ticksPerSecond, pageSize, entryPids(current.entries), Collections.emptySet());
    }
    public ProcessTable(ProcessSnapshot current, ProcessSnapshot previous, long elapsedMillis, long ticksPerSecond, long pageSize,
                        java.util.Set<Integer> sessionRoots, java.util.Set<Integer> taskRoots) {
        Map<Integer, ProcessSnapshot.Entry> old = new HashMap<>();
        if (previous != null) for (ProcessSnapshot.Entry entry : previous.visibleEntries) old.put(entry.pid, entry);
        Map<Integer, List<Integer>> children = new HashMap<>();
        java.util.Set<Integer> visible = new java.util.HashSet<>();
        for (ProcessSnapshot.Entry entry : current.visibleEntries) {
            visible.add(entry.pid);
            children.computeIfAbsent(entry.ppid, ignored -> new ArrayList<>()).add(entry.pid);
        }
        java.util.Set<Integer> sessions = descendants(sessionRoots, visible, children);
        java.util.Set<Integer> tasks = descendants(taskRoots, visible, children);
        List<Row> values = new ArrayList<>();
        for (ProcessSnapshot.Entry entry : current.visibleEntries)
            values.add(new Row(entry, ProcessSnapshot.rssKiB(entry.rssPages, pageSize),
                ProcessSnapshot.cpuPercent(old.get(entry.pid), entry, elapsedMillis, ticksPerSecond),
                sessions.contains(entry.pid), tasks.contains(entry.pid)));
        Map<Sort, List<Row>> ordering = new java.util.EnumMap<>(Sort.class);
        for (Sort sort : Sort.values()) {
            List<Row> copy = new ArrayList<>(values);
            Collections.sort(copy, comparator(sort));
            ordering.put(sort, Collections.unmodifiableList(copy));
        }
        sorted = Collections.unmodifiableMap(ordering);
    }
    private static java.util.Set<Integer> entryPids(List<ProcessSnapshot.Entry> entries) {
        java.util.Set<Integer> pids = new java.util.HashSet<>();
        for (ProcessSnapshot.Entry entry : entries) pids.add(entry.pid);
        return pids;
    }
    private static java.util.Set<Integer> descendants(java.util.Set<Integer> roots, java.util.Set<Integer> visible,
                                                     Map<Integer, List<Integer>> children) {
        java.util.Set<Integer> selected = new java.util.HashSet<>();
        java.util.ArrayDeque<Integer> pending = new java.util.ArrayDeque<>(roots);
        while (!pending.isEmpty()) {
            int pid = pending.removeFirst();
            if (!visible.contains(pid) || !selected.add(pid)) continue;
            List<Integer> childPids = children.get(pid);
            if (childPids != null) pending.addAll(childPids);
        }
        return selected;
    }
    public List<Row> select(String query, Sort sort) { return select(query, sort, Scope.OWNED); }
    public List<Row> select(String query, Sort sort, Scope scope) {
        if (scope == null) scope = Scope.OWNED;
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<Row> selected = new ArrayList<>();
        for (Row row : sorted.get(sort == null ? Sort.PID : sort)) {
            if (scope == Scope.OWNED && !row.session && !row.task || scope == Scope.SESSION && !row.session ||
                scope == Scope.TASK && !row.task) continue;
            ProcessSnapshot.Entry e = row.entry;
            if (needle.isEmpty() || e.name.toLowerCase(Locale.ROOT).contains(needle) ||
                Integer.toString(e.pid).contains(needle) || Integer.toString(e.ppid).contains(needle)) selected.add(row);
        }
        return selected;
    }
    private static Comparator<Row> comparator(Sort sort) {
        Comparator<Row> comparator;
        switch (sort == null ? Sort.PID : sort) {
            case NAME: comparator = (a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.entry.name, b.entry.name); break;
            case CPU: comparator = (a, b) -> compareDescending(a.cpuPercent, b.cpuPercent); break;
            case RSS: comparator = (a, b) -> compareDescending(a.rssKiB, b.rssKiB); break;
            default: comparator = (a, b) -> Integer.compare(a.entry.pid, b.entry.pid);
        }
        return (a, b) -> {
            int compared = comparator.compare(a, b);
            return compared != 0 ? compared : Integer.compare(a.entry.pid, b.entry.pid);
        };
    }
    private static <T extends Comparable<T>> int compareDescending(T a, T b) {
        if (a == null) return b == null ? 0 : 1;
        if (b == null) return -1;
        return b.compareTo(a);
    }
}
