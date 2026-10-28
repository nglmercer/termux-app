package com.termux.app.terminal.clipboard;

import com.termux.shared.termux.settings.properties.ClipboardImageLimits;

/** Tracks the smallest applicable encoded-byte quota across one image batch. */
final class ImageImportBudget {
    private final long fileLimit;
    private final boolean cacheLimited;
    private long remaining;
    ImageImportBudget(ClipboardImageLimits limits, long cacheRemaining) throws ImageImportException {
        if (limits == null || cacheRemaining < 0) throw new IllegalArgumentException("Invalid budget");
        if (cacheRemaining < 12) throw new ImageImportException(ImageImportException.Reason.CACHE);
        fileLimit = limits.fileBytes();
        cacheLimited = cacheRemaining < limits.batchBytes();
        remaining = Math.min(limits.batchBytes(), cacheRemaining);
    }
    long nextLimit() throws ImageImportException {
        if (remaining < 12) throw new ImageImportException(cacheLimited ? ImageImportException.Reason.CACHE : ImageImportException.Reason.SIZE);
        return Math.min(fileLimit, remaining);
    }
    ImageImportException mapSizeFailure(ImageImportException error) {
        return error.getReason() == ImageImportException.Reason.SIZE && cacheLimited && remaining < fileLimit
            ? new ImageImportException(ImageImportException.Reason.CACHE) : error;
    }
    void consume(long bytes) throws ImageImportException {
        if (bytes < 0 || bytes > nextLimit()) throw new ImageImportException(ImageImportException.Reason.SIZE);
        remaining -= bytes;
    }
}
