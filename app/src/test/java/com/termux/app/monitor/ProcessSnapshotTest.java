package com.termux.app.monitor;

import org.junit.Test;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import static org.junit.Assert.*;

public class ProcessSnapshotTest {
    private static String stat(int pid, String name, int ppid, long start) {
        return pid + " (" + name + ") S " + ppid + " 0 0 0 0 0 0 0 0 0 10 20 0 0 0 0 0 0 " + start + " 0 7";
    }
    @Test public void parsesNamesAndFields() {
        ProcessSnapshot.Entry e = ProcessSnapshot.parseStat(stat(123, "odd ) name", 42, 88));
        assertEquals(123, e.pid); assertEquals(42, e.ppid); assertEquals("odd ) name", e.name);
        assertEquals(10, e.userTicks); assertEquals(20, e.systemTicks); assertEquals(88, e.startTicks); assertEquals(7, e.rssPages);
    }
    @Test public void selectsOnlyOwnedDescendantsAndReportsMissingRoot() throws Exception {
        File root = Files.createTempDirectory("proc-test").toFile();
        try {
            add(root, 10, 1, 1000); add(root, 11, 10, 1000); add(root, 12, 11, 2000); add(root, 13, 1, 1000);
            ProcessSnapshot snapshot = ProcessSnapshot.collect(root, 1000, Collections.singleton(10));
            assertEquals(2, snapshot.entries.size()); assertEquals(10, snapshot.entries.get(0).pid); assertEquals(11, snapshot.entries.get(1).pid);
            assertEquals(0, snapshot.missingRoots); assertFalse(snapshot.incomplete);
            java.util.Set<Integer> bothRoots = new java.util.HashSet<>();
            bothRoots.add(10); bothRoots.add(13);
            ProcessSnapshot combined = ProcessSnapshot.collect(root, 1000, bothRoots);
            assertEquals(3, combined.entries.size()); assertEquals(13, combined.entries.get(2).pid);
            ProcessSnapshot missing = ProcessSnapshot.collect(root, 1000, Collections.singleton(99));
            assertEquals(1, missing.missingRoots); assertTrue(missing.entries.isEmpty());
        } finally {
            for (File dir : root.listFiles()) { for (File f : dir.listFiles()) f.delete(); dir.delete(); } root.delete();
        }
    }
    @Test public void processTableSearchesAndSortsBeforeLimitingDisplay() throws Exception {
        File root = Files.createTempDirectory("proc-table").toFile();
        try {
            add(root, 10, 1, 1000); add(root, 11, 10, 1000); add(root, 12, 10, 1000);
            writeStat(root, 10, "zsh", 1, 55, 10, 5);
            writeStat(root, 11, "Build", 10, 55, 10, 10);
            ProcessSnapshot previous = ProcessSnapshot.collect(root, 1000, Collections.singleton(10));
            writeStat(root, 10, "zsh", 1, 55, 30, 5);
            writeStat(root, 11, "Build", 10, 55, 60, 10);
            writeStat(root, 12, "agent", 10, 56, 100, 20); // Reused PID has unknown CPU.
            ProcessSnapshot current = ProcessSnapshot.collect(root, 1000, Collections.singleton(10));
            ProcessTable table = new ProcessTable(current, previous, 1000, 100, 4096);
            assertEquals(11, table.select("bUi", ProcessTable.Sort.PID).get(0).entry.pid);
            assertEquals(3, table.select("10", ProcessTable.Sort.PID).size());
            assertEquals(12, table.select("", ProcessTable.Sort.NAME).get(0).entry.pid);
            assertEquals(11, table.select("", ProcessTable.Sort.CPU).get(0).entry.pid);
            assertEquals(12, table.select("", ProcessTable.Sort.CPU).get(2).entry.pid);
            assertEquals(12, table.select("", ProcessTable.Sort.RSS).get(0).entry.pid);
            assertTrue(table.select("missing", ProcessTable.Sort.PID).isEmpty());
        } finally {
            for (File dir : root.listFiles()) { for (File f : dir.listFiles()) f.delete(); dir.delete(); } root.delete();
        }
    }
    @Test public void filtersSessionsTasksAndAllVisibleWithoutOtherUids() throws Exception {
        File root = Files.createTempDirectory("proc-table-scopes").toFile();
        try {
            add(root, 10, 1, 1000); add(root, 11, 10, 1000); add(root, 12, 11, 1000);
            add(root, 13, 1, 1000); add(root, 14, 1, 1000); add(root, 15, 10, 2000);
            java.util.Set<Integer> sessions = Collections.singleton(10);
            java.util.Set<Integer> tasks = new java.util.HashSet<>(java.util.Arrays.asList(11, 13));
            java.util.Set<Integer> roots = new java.util.HashSet<>(sessions); roots.addAll(tasks);
            ProcessSnapshot current = ProcessSnapshot.collect(root, 1000, roots);
            assertEquals(4, current.entries.size()); assertEquals(5, current.visibleEntries.size());
            ProcessTable table = new ProcessTable(current, null, 0, 100, 4096, sessions, tasks);
            assertEquals(4, table.select("", ProcessTable.Sort.PID, ProcessTable.Scope.OWNED).size());
            assertEquals(3, table.select("", ProcessTable.Sort.PID, ProcessTable.Scope.SESSION).size());
            assertEquals(3, table.select("", ProcessTable.Sort.PID, ProcessTable.Scope.TASK).size());
            assertEquals(5, table.select("", ProcessTable.Sort.PID, ProcessTable.Scope.ALL_VISIBLE).size());
            assertEquals(4, table.select("", ProcessTable.Sort.PID, null).size());
            assertEquals(4, new ProcessTable(current, null, 0, 100, 4096).select("", ProcessTable.Sort.PID).size());
            ProcessTable.Row overlap = table.select("11", ProcessTable.Sort.PID, ProcessTable.Scope.OWNED).get(0);
            assertTrue(overlap.session); assertTrue(overlap.task);
            assertTrue(table.select("14", ProcessTable.Sort.PID, ProcessTable.Scope.OWNED).isEmpty());
            assertEquals(14, table.select("14", ProcessTable.Sort.PID, ProcessTable.Scope.ALL_VISIBLE).get(0).entry.pid);
            assertTrue(table.select("15", ProcessTable.Sort.PID, ProcessTable.Scope.ALL_VISIBLE).isEmpty());
            ProcessTable missingRoot = new ProcessTable(current, null, 0, 100, 4096, Collections.singleton(99), Collections.emptySet());
            assertTrue(missingRoot.select("", ProcessTable.Sort.PID, ProcessTable.Scope.OWNED).isEmpty());
            assertEquals(5, missingRoot.select("", ProcessTable.Sort.PID, ProcessTable.Scope.ALL_VISIBLE).size());
        } finally {
            for (File dir : root.listFiles()) { for (File f : dir.listFiles()) f.delete(); dir.delete(); } root.delete();
        }
    }
    @Test public void searchFindsEntriesBeyondDisplayLimit() throws Exception {
        File root = Files.createTempDirectory("proc-table-many").toFile();
        try {
            for (int pid = 1; pid <= 140; pid++) add(root, pid, pid == 1 ? 0 : 1, 1000);
            writeStat(root, 140, "unique-target", 1, 55, 10, 1000);
            ProcessSnapshot current = ProcessSnapshot.collect(root, 1000, Collections.singleton(1));
            ProcessTable table = new ProcessTable(current, null, 0, 100, 4096);
            assertEquals(140, table.select("", ProcessTable.Sort.PID).size());
            assertEquals(140, table.select("unique-target", ProcessTable.Sort.PID).get(0).entry.pid);
            assertEquals(140, table.select("", ProcessTable.Sort.RSS).get(0).entry.pid);
            assertNull(table.select("", ProcessTable.Sort.CPU).get(0).cpuPercent);
        } finally {
            for (File dir : root.listFiles()) { for (File f : dir.listFiles()) f.delete(); dir.delete(); } root.delete();
        }
    }
    @Test public void treeGroupsVisibleParentsAndPromotesFilteredOrMissingParents() throws Exception {
        File root = Files.createTempDirectory("proc-tree").toFile();
        try {
            add(root, 10, 1, 1000); add(root, 11, 10, 1000); add(root, 12, 10, 1000);
            add(root, 13, 11, 1000); add(root, 20, 99, 1000); add(root, 21, 20, 2000);
            writeStat(root, 11, "child", 10, 55, 1, 1);
            writeStat(root, 13, "child-helper", 11, 55, 1, 1);
            ProcessSnapshot snapshot = ProcessSnapshot.collect(root, 1000, Collections.singleton(10));
            ProcessTable table = new ProcessTable(snapshot, null, 0, 100, 4096, Collections.singleton(10), Collections.emptySet());
            java.util.List<ProcessTable.TreeRow> rows = table.selectTree("", ProcessTable.Scope.ALL_VISIBLE);
            assertEquals(5, rows.size());
            int[] ids = {10, 11, 13, 12, 20}; int[] depths = {0, 1, 2, 1, 0};
            for (int i = 0; i < ids.length; i++) { assertEquals(ids[i], rows.get(i).row.entry.pid); assertEquals(depths[i], rows.get(i).depth); }
            rows = table.selectTree("child", ProcessTable.Scope.OWNED);
            assertEquals(2, rows.size()); assertEquals(11, rows.get(0).row.entry.pid); assertEquals(0, rows.get(0).depth);
            assertEquals(13, rows.get(1).row.entry.pid); assertEquals(1, rows.get(1).depth);
            assertEquals(4, table.selectTree("", ProcessTable.Scope.OWNED).size());
            assertTrue(table.selectTree("missing", ProcessTable.Scope.ALL_VISIBLE).isEmpty());
        } finally {
            for (File dir : root.listFiles()) { for (File f : dir.listFiles()) f.delete(); dir.delete(); } root.delete();
        }
    }
    @Test public void treeBreaksCyclesAndLimitsIndentation() throws Exception {
        File root = Files.createTempDirectory("proc-tree-cycles").toFile();
        try {
            add(root, 1, 2, 1000); add(root, 2, 1, 1000); add(root, 3, 3, 1000);
            for (int i = 4; i <= 30; i++) add(root, i, i == 4 ? 3 : i - 1, 1000);
            ProcessSnapshot snapshot = ProcessSnapshot.collect(root, 1000, Collections.singleton(3));
            ProcessTable table = new ProcessTable(snapshot, null, 0, 100, 4096);
            java.util.List<ProcessTable.TreeRow> rows = table.selectTree("", ProcessTable.Scope.ALL_VISIBLE);
            assertEquals(30, rows.size());
            java.util.Set<Integer> visited = new java.util.HashSet<>();
            for (ProcessTable.TreeRow row : rows) { assertTrue(visited.add(row.row.entry.pid)); assertTrue(row.depth <= 8); }
            assertEquals(3, rows.get(0).row.entry.pid); assertEquals(0, rows.get(0).depth);
            assertEquals(1, rows.get(28).row.entry.pid); assertEquals(0, rows.get(28).depth);
            assertEquals(2, rows.get(29).row.entry.pid); assertEquals(1, rows.get(29).depth);
        } finally {
            for (File dir : root.listFiles()) { for (File f : dir.listFiles()) f.delete(); dir.delete(); } root.delete();
        }
    }
    private static void writeStat(File root, int pid, String name, int ppid, long start, long user, long rss) throws Exception {
        String line = pid + " (" + name + ") S " + ppid + " 0 0 0 0 0 0 0 0 0 " + user + " 20 0 0 0 0 0 0 " + start + " 0 " + rss;
        Files.write(new File(new File(root, String.valueOf(pid)), "stat").toPath(), line.getBytes(StandardCharsets.UTF_8));
    }
    @Test public void cpuDeltaRequiresStableIdentityAndMonotonicCounters() {
        ProcessSnapshot.Entry before = new ProcessSnapshot.Entry(4, 1, "x", 'S', 9, 10, 20, 2);
        ProcessSnapshot.Entry after = new ProcessSnapshot.Entry(4, 1, "x", 'S', 9, 60, 70, 3);
        assertEquals(50.0, ProcessSnapshot.cpuPercent(before, after, 2000, 100), 0.001);
        assertNull(ProcessSnapshot.cpuPercent(before, new ProcessSnapshot.Entry(4, 1, "x", 'S', 10, 60, 70, 3), 2000, 100));
        assertNull(ProcessSnapshot.cpuPercent(before, new ProcessSnapshot.Entry(4, 1, "x", 'S', 9, 9, 70, 3), 2000, 100));
        assertNull(ProcessSnapshot.cpuPercent(before, after, 0, 100));
        assertNull(ProcessSnapshot.cpuPercent(before, after, 1000, 0));
    }
    @Test public void rssConversionRejectsOverflowAndMissingPageSize() {
        assertEquals(Long.valueOf(12), ProcessSnapshot.rssKiB(3, 4096));
        assertNull(ProcessSnapshot.rssKiB(Long.MAX_VALUE, 4096));
        assertNull(ProcessSnapshot.rssKiB(1, -1));
    }
    @Test public void deviceMemoryRejectsInvalidAndReportsDifference() {
        assertEquals(Long.valueOf(70), DeviceSnapshot.memoryUsedBytes(100, 30));
        assertNull(DeviceSnapshot.memoryUsedBytes(0, 0));
        assertNull(DeviceSnapshot.memoryUsedBytes(100, 101));
        assertNull(DeviceSnapshot.memoryUsedBytes(100, -1));
    }
    @Test public void rejectsMalformedStat() {
        try { ProcessSnapshot.parseStat("1 (broken) S 0"); fail(); } catch (IllegalArgumentException expected) { }
    }
    private static void add(File root, int pid, int ppid, int uid) throws Exception {
        File dir = new File(root, String.valueOf(pid)); assertTrue(dir.mkdir());
        Files.write(new File(dir, "status").toPath(), ("Name:\ttest\nUid:\t" + uid + "\t" + uid + "\n").getBytes(StandardCharsets.US_ASCII));
        Files.write(new File(dir, "stat").toPath(), stat(pid, "test", ppid, 55).getBytes(StandardCharsets.UTF_8));
    }
}
