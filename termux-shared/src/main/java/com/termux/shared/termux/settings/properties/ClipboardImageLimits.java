package com.termux.shared.termux.settings.properties;

/** Validated clipboard image quotas in MiB. Independent limits combine by the smallest remaining quota. */
public final class ClipboardImageLimits {
    public static final int DEFAULT_FILE_MIB = 20, DEFAULT_BATCH_MIB = 40, DEFAULT_CACHE_MIB = 100;
    public static final int MAX_FILE_MIB = 64, MAX_BATCH_MIB = 128, MAX_CACHE_MIB = 256;
    private static final long MIB = 1024L * 1024;
    public final int fileMiB, batchMiB, cacheMiB;
    private ClipboardImageLimits(int fileMiB, int batchMiB, int cacheMiB) {
        this.fileMiB = fileMiB; this.batchMiB = batchMiB; this.cacheMiB = cacheMiB;
    }
    public static ClipboardImageLimits of(int fileMiB, int batchMiB, int cacheMiB) {
        if (!valid(fileMiB, MAX_FILE_MIB) || !valid(batchMiB, MAX_BATCH_MIB) || !valid(cacheMiB, MAX_CACHE_MIB))
            throw new IllegalArgumentException("Invalid clipboard image limits");
        return new ClipboardImageLimits(fileMiB, batchMiB, cacheMiB);
    }
    public static int parseFile(String value) { return parse(value, DEFAULT_FILE_MIB, MAX_FILE_MIB); }
    public static int parseBatch(String value) { return parse(value, DEFAULT_BATCH_MIB, MAX_BATCH_MIB); }
    public static int parseCache(String value) { return parse(value, DEFAULT_CACHE_MIB, MAX_CACHE_MIB); }
    private static int parse(String value, int fallback, int max) {
        if (value == null) return fallback;
        try {
            int parsed = Integer.parseInt(value.trim());
            return valid(parsed, max) ? parsed : fallback;
        } catch (NumberFormatException e) { return fallback; }
    }
    private static boolean valid(int value, int max) { return value >= 1 && value <= max; }
    public long fileBytes() { return fileMiB * MIB; }
    public long batchBytes() { return batchMiB * MIB; }
    public long cacheBytes() { return cacheMiB * MIB; }
}
