package com.termux.app.control.ipc;

/** Per-connection token bucket. Waiting occurs only on the socket worker. */
final class RequestRateLimiter {
    private static final long TOKEN = 1_000_000_000L;
    private static final long MAX_CREDIT = 100 * TOKEN;
    private static final long TOKENS_PER_SECOND = 50;
    private long credit = MAX_CREDIT, lastNanos;
    RequestRateLimiter(long nowNanos) { lastNanos = nowNanos; }

    /** Returns zero after consuming a token, otherwise the nanoseconds until retry. */
    long delayNanos(long nowNanos) {
        long elapsed = nowNanos - lastNanos;
        if (elapsed < 0) elapsed = 0;
        lastNanos = nowNanos;
        if (elapsed >= MAX_CREDIT / TOKENS_PER_SECOND) credit = MAX_CREDIT;
        else credit = Math.min(MAX_CREDIT, credit + elapsed * TOKENS_PER_SECOND);
        if (credit >= TOKEN) { credit -= TOKEN; return 0; }
        return (TOKEN - credit + TOKENS_PER_SECOND - 1) / TOKENS_PER_SECOND;
    }
    void await() throws InterruptedException {
        for (;;) {
            long delay = delayNanos(System.nanoTime());
            if (delay == 0) return;
            Thread.sleep(delay / 1_000_000L, (int) (delay % 1_000_000L));
        }
    }
}
