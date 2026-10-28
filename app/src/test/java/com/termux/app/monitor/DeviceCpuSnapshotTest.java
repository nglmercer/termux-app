package com.termux.app.monitor;

import org.junit.Test;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class DeviceCpuSnapshotTest {
    @Test public void calculatesAggregateBusyAndExcludesGuestCounters() {
        DeviceCpuSnapshot before = DeviceCpuSnapshot.parse("cpu 100 20 30 200 10 5 5 10 1000 500");
        DeviceCpuSnapshot after = DeviceCpuSnapshot.parse("cpu 130 20 40 240 20 5 5 20 3000 500");
        assertNotNull(before); assertNotNull(after);
        assertEquals(50.0, DeviceCpuSnapshot.percent(before, after), 0.0001);
        assertNull(DeviceCpuSnapshot.percent(null, after));
        assertNull(DeviceCpuSnapshot.percent(before, before));
        assertNull(DeviceCpuSnapshot.percent(after, before));
    }
    @Test public void handlesShortCountersAndRejectsInvalidOrOverflowingDeltas() {
        assertEquals(50.0, DeviceCpuSnapshot.percent(DeviceCpuSnapshot.parse("cpu 1 1 1 1"),
            DeviceCpuSnapshot.parse("cpu 2 1 1 2")), 0.0001);
        assertNull(DeviceCpuSnapshot.parse("cpu0 1 2 3 4"));
        assertNull(DeviceCpuSnapshot.parse("cpu 1 2 3"));
        assertNull(DeviceCpuSnapshot.parse("cpu 1 -2 3 4"));
        assertNull(DeviceCpuSnapshot.parse("cpu 1 2 3 nope"));
        assertNull(DeviceCpuSnapshot.percent(DeviceCpuSnapshot.parse("cpu 0 0 0 0"),
            DeviceCpuSnapshot.parse("cpu 9223372036854775807 1 0 0")));
        assertNull(DeviceCpuSnapshot.percent(DeviceCpuSnapshot.parse("cpu 1 2 3 10 4"),
            DeviceCpuSnapshot.parse("cpu 2 2 3 11 3")));
    }
    @Test public void readsOnlyBoundedAggregateLineAndHandlesMissingFile() throws Exception {
        File f = File.createTempFile("cpu-snapshot", ".stat");
        try {
            Files.write(f.toPath(), "cpu 10 2 3 4\ncpu0 2 2 2 2\n".getBytes(StandardCharsets.US_ASCII));
            assertNotNull(DeviceCpuSnapshot.collect(f));
            char[] huge = new char[1025]; java.util.Arrays.fill(huge, '1');
            Files.write(f.toPath(), ("cpu " + new String(huge)).getBytes(StandardCharsets.US_ASCII));
            assertNull(DeviceCpuSnapshot.collect(f));
        } finally { assertTrue(f.delete()); }
        assertNull(DeviceCpuSnapshot.collect(f));
    }
}
