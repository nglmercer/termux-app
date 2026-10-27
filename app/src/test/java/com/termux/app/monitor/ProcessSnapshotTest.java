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
