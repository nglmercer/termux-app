package com.termux.app.control.ipc;

import junit.framework.TestCase;

public class RequestRateLimiterTest extends TestCase {
    public void testBurstAndRefillAreBounded() {
        RequestRateLimiter limiter = new RequestRateLimiter(0);
        for (int i = 0; i < 100; i++) assertEquals(0, limiter.delayNanos(0));
        assertEquals(20_000_000L, limiter.delayNanos(0));
        assertEquals(0, limiter.delayNanos(20_000_000L));
        assertEquals(20_000_000L, limiter.delayNanos(20_000_000L));
        assertEquals(0, limiter.delayNanos(2_020_000_000L));
        for (int i = 0; i < 99; i++) assertEquals(0, limiter.delayNanos(2_020_000_000L));
        assertEquals(20_000_000L, limiter.delayNanos(2_020_000_000L));
    }
    public void testClockRegressionDoesNotCreateCredits() {
        RequestRateLimiter limiter = new RequestRateLimiter(100);
        for (int i = 0; i < 100; i++) assertEquals(0, limiter.delayNanos(100));
        assertTrue(limiter.delayNanos(10) > 0);
    }
}
