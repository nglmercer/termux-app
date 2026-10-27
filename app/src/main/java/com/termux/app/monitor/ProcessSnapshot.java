package com.termux.app.monitor;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Read-only, best-effort view of same-UID descendants of known session PIDs. */
public final class ProcessSnapshot {
    private static final int MAX_ENTRIES = 4096, MAX_LINE = 16384;
    public final List<Entry> entries;
    public final boolean incomplete;
    public final int missingRoots;

    private ProcessSnapshot(List<Entry> entries, boolean incomplete, int missingRoots) {
        this.entries = Collections.unmodifiableList(entries);
        this.incomplete = incomplete;
        this.missingRoots = missingRoots;
    }

    public static final class Entry {
        public final int pid, ppid;
        public final String name;
        public final char state;
        public final long startTicks, userTicks, systemTicks, rssPages;
        Entry(int pid, int ppid, String name, char state, long startTicks, long userTicks, long systemTicks, long rssPages) {
            this.pid = pid; this.ppid = ppid; this.name = name; this.state = state;
            this.startTicks = startTicks; this.userTicks = userTicks; this.systemTicks = systemTicks; this.rssPages = rssPages;
        }
    }

    /** Percent of one CPU over the interval; null means no reliable comparison. */
    public static Double cpuPercent(Entry before, Entry after, long elapsedMillis, long ticksPerSecond) {
        if (before == null || after == null || before.pid != after.pid || before.startTicks != after.startTicks ||
            elapsedMillis <= 0 || ticksPerSecond <= 0 || after.userTicks < before.userTicks ||
            after.systemTicks < before.systemTicks) return null;
        double ticks = (double) (after.userTicks - before.userTicks) + (after.systemTicks - before.systemTicks);
        return 100000.0 * ticks / ticksPerSecond / elapsedMillis;
    }

    /** Convert pages to KiB without overflowing. Null means the value is unavailable. */
    public static Long rssKiB(long pages, long pageSize) {
        if (pages < 0 || pageSize <= 0 || pages > Long.MAX_VALUE / pageSize) return null;
        return pages * pageSize / 1024;
    }

    /** The proc directory and UID are injectable to allow deterministic tests. */
    public static ProcessSnapshot collect(File proc, int uid, Set<Integer> roots) {
        String[] names;
        try { names = proc.list(); }
        catch (SecurityException e) { return new ProcessSnapshot(new ArrayList<>(), true, roots.size()); }
        if (names == null) return new ProcessSnapshot(new ArrayList<>(), true, roots.size());
        Map<Integer, Entry> candidates = new HashMap<>();
        boolean incomplete = false;
        int visited = 0;
        for (String name : names) {
            int pid;
            try { pid = Integer.parseInt(name); } catch (NumberFormatException e) { continue; }
            if (pid <= 0 || !Integer.toString(pid).equals(name)) continue;
            if (++visited > MAX_ENTRIES) { incomplete = true; break; }
            File directory = new File(proc, name);
            try {
                if (readUid(new File(directory, "status")) != uid) continue;
                Entry entry = parseStat(readLine(new File(directory, "stat")));
                if (entry.pid == pid) candidates.put(pid, entry);
                else incomplete = true;
            } catch (IOException | IllegalArgumentException | SecurityException e) {
                // Process churn and Android restrictions make completeness unknowable.
                incomplete = true;
            }
        }
        int missing = 0;
        Set<Integer> existingRoots = new HashSet<>();
        for (int root : roots) {
            if (candidates.containsKey(root)) existingRoots.add(root);
            else missing++;
        }
        Map<Integer, List<Entry>> children = new HashMap<>();
        for (Entry entry : candidates.values())
            children.computeIfAbsent(entry.ppid, ignored -> new ArrayList<>()).add(entry);
        List<Entry> selected = new ArrayList<>();
        ArrayDeque<Integer> pending = new ArrayDeque<>(existingRoots);
        Set<Integer> visitedPids = new HashSet<>();
        while (!pending.isEmpty()) {
            int pid = pending.removeFirst();
            if (!visitedPids.add(pid)) continue;
            Entry entry = candidates.get(pid);
            if (entry == null) continue;
            selected.add(entry);
            List<Entry> descendants = children.get(pid);
            if (descendants != null) for (Entry child : descendants) pending.addLast(child.pid);
        }
        Collections.sort(selected, (a, b) -> Integer.compare(a.pid, b.pid));
        return new ProcessSnapshot(selected, incomplete, missing);
    }

    private static int readUid(File status) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(status), StandardCharsets.US_ASCII))) {
            int total = 0;
            StringBuilder line = new StringBuilder();
            int c;
            while ((c = reader.read()) != -1 && ++total <= MAX_LINE) {
                if (c == '\n') {
                    if (line.toString().startsWith("Uid:")) {
                        String[] values = line.substring(4).trim().split("\\s+");
                        try { return Integer.parseInt(values[0]); } catch (RuntimeException e) { throw new IOException("Invalid UID", e); }
                    }
                    line.setLength(0);
                } else line.append((char) c);
            }
        }
        throw new IOException("UID unavailable");
    }

    private static String readLine(File file) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            StringBuilder value = new StringBuilder();
            int c;
            while ((c = reader.read()) != -1 && c != '\n') {
                if (value.length() >= MAX_LINE) throw new IOException("Stat too long");
                value.append((char) c);
            }
            return value.toString();
        }
    }

    /** Parses Linux /proc/PID/stat without splitting command names at spaces or parentheses. */
    public static Entry parseStat(String value) {
        int open = value.indexOf('('), close = value.lastIndexOf(')');
        if (open <= 0 || close <= open || close + 2 >= value.length()) throw new IllegalArgumentException("Invalid stat");
        try {
            int pid = Integer.parseInt(value.substring(0, open).trim());
            String name = value.substring(open + 1, close);
            String[] fields = value.substring(close + 1).trim().split("\\s+");
            if (pid <= 0 || fields.length < 22 || fields[0].length() != 1) throw new IllegalArgumentException("Invalid stat");
            int ppid = Integer.parseInt(fields[1]);
            long user = Long.parseLong(fields[11]), system = Long.parseLong(fields[12]);
            long start = Long.parseLong(fields[19]), rss = Long.parseLong(fields[21]);
            if (ppid < 0 || user < 0 || system < 0 || start < 0 || rss < 0) throw new IllegalArgumentException("Invalid stat");
            return new Entry(pid, ppid, name, fields[0].charAt(0), start, user, system, rss);
        } catch (IndexOutOfBoundsException | NumberFormatException e) { throw new IllegalArgumentException("Invalid stat", e); }
    }
}
