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
    public static final class Row {
        public final ProcessSnapshot.Entry entry;
        public final Long rssKiB;
        public final Double cpuPercent;
        private Row(ProcessSnapshot.Entry entry, Long rssKiB, Double cpuPercent) {
            this.entry = entry; this.rssKiB = rssKiB; this.cpuPercent = cpuPercent;
        }
    }
    private final Map<Sort, List<Row>> sorted;
    public ProcessTable(ProcessSnapshot current, ProcessSnapshot previous, long elapsedMillis, long ticksPerSecond, long pageSize) {
        Map<Integer, ProcessSnapshot.Entry> old = new HashMap<>();
        if (previous != null) for (ProcessSnapshot.Entry entry : previous.entries) old.put(entry.pid, entry);
        List<Row> values = new ArrayList<>();
        for (ProcessSnapshot.Entry entry : current.entries)
            values.add(new Row(entry, ProcessSnapshot.rssKiB(entry.rssPages, pageSize),
                ProcessSnapshot.cpuPercent(old.get(entry.pid), entry, elapsedMillis, ticksPerSecond)));
        Map<Sort, List<Row>> ordering = new java.util.EnumMap<>(Sort.class);
        for (Sort sort : Sort.values()) {
            List<Row> copy = new ArrayList<>(values);
            Collections.sort(copy, comparator(sort));
            ordering.put(sort, Collections.unmodifiableList(copy));
        }
        sorted = Collections.unmodifiableMap(ordering);
    }
    public List<Row> select(String query, Sort sort) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<Row> selected = new ArrayList<>();
        for (Row row : sorted.get(sort == null ? Sort.PID : sort)) {
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
