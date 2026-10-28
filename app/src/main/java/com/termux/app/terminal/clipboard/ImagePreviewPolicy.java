package com.termux.app.terminal.clipboard;

/** Conservative bounds for an optional thumbnail; these do not limit path-only imports. */
public final class ImagePreviewPolicy {
    private static final int MAX_EDGE = 100000, THUMB_EDGE = 128;
    private static final long MAX_PIXELS = 100_000_000L;
    private ImagePreviewPolicy() { }

    /** Zero means decline preview; otherwise a power-of-two decoder sample size. */
    public static int sampleSize(int width, int height) {
        if (width <= 0 || height <= 0 || width > MAX_EDGE || height > MAX_EDGE ||
            (long) width * height > MAX_PIXELS) return 0;
        int sample = 1;
        while (((long) width + sample - 1) / sample > THUMB_EDGE ||
               ((long) height + sample - 1) / sample > THUMB_EDGE) sample *= 2;
        return sample;
    }
}
