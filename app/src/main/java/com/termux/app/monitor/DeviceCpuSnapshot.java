package com.termux.app.monitor;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

/** Aggregate Linux CPU counters, sampled on a worker thread. */
public final class DeviceCpuSnapshot {
    private static final int MAX_LINE = 1024;
    private final long[] counters;

    private DeviceCpuSnapshot(long[] counters) { this.counters = counters; }

    /** Null means procfs is unavailable or malformed. */
    public static DeviceCpuSnapshot collect(File procStat) {
        try (BufferedReader reader = new BufferedReader(new FileReader(procStat))) {
            StringBuilder line = new StringBuilder();
            int c;
            while ((c = reader.read()) != -1 && c != '\n') {
                if (line.length() >= MAX_LINE) return null;
                line.append((char) c);
            }
            return parse(line.toString());
        } catch (IOException | SecurityException e) { return null; }
    }

    /** Guest fields are excluded because Linux already includes them in user and nice. */
    public static DeviceCpuSnapshot parse(String line) {
        if (line == null || line.length() > MAX_LINE) return null;
        String[] fields = line.trim().split("\\s+");
        if (fields.length < 5 || !"cpu".equals(fields[0])) return null;
        long[] values = new long[8];
        try {
            for (int i = 0; i < values.length && i + 1 < fields.length; i++) {
                values[i] = Long.parseLong(fields[i + 1]);
                if (values[i] < 0) return null;
            }
        } catch (NumberFormatException e) { return null; }
        return new DeviceCpuSnapshot(values);
    }

    /** Returns busy percentage across all CPUs, or null when counters cannot be compared. */
    public static Double percent(DeviceCpuSnapshot previous, DeviceCpuSnapshot current) {
        if (previous == null || current == null) return null;
        long total = 0, idle = 0;
        for (int i = 0; i < 8; i++) {
            if (current.counters[i] < previous.counters[i]) return null;
            long delta = current.counters[i] - previous.counters[i];
            if (delta > Long.MAX_VALUE - total) return null;
            total += delta;
            if (i == 3 || i == 4) {
                if (delta > Long.MAX_VALUE - idle) return null;
                idle += delta;
            }
        }
        if (total == 0 || idle > total) return null;
        return 100.0 * (total - idle) / total;
    }
}
