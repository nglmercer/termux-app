package com.termux.app.control.ipc;

import junit.framework.TestCase;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class FrameDeadlineInputTest extends TestCase {
    private static final class Clock implements FrameDeadlineInput.Clock {
        long now;
        public long nowMillis() { return now; }
    }
    private static final class SlowInput extends ByteArrayInputStream {
        final Clock clock; final long step;
        SlowInput(byte[] bytes, Clock clock, long step) { super(bytes); this.clock = clock; this.step = step; }
        @Override public synchronized int read() { int value = super.read(); clock.now += step; return value; }
        @Override public synchronized int read(byte[] bytes, int off, int len) {
            int count = super.read(bytes, off, Math.min(len, 1)); clock.now += step; return count;
        }
    }
    public void testSlowTrickleCannotExtendFrameDeadline() throws Exception {
        Clock clock = new Clock(); List<Integer> timeouts = new ArrayList<>();
        FrameDeadlineInput input = new FrameDeadlineInput(new SlowInput(new byte[]{0, 0, 0, 1, 42}, clock, 1200), clock, timeouts::add, 5000);
        try { new FrameCodec(32).read(input); fail(); } catch (IOException expected) { }
        assertEquals(5000, (int) timeouts.get(0));
        assertTrue(timeouts.get(timeouts.size() - 1) < timeouts.get(0));
    }
    public void testEveryFrameGetsFreshDeadline() throws Exception {
        Clock clock = new Clock();
        FrameDeadlineInput input = new FrameDeadlineInput(new SlowInput(new byte[]{0,0,0,1,42,0,0,0,1,43}, clock, 600), clock, ignored -> {}, 5000);
        FrameCodec codec = new FrameCodec(32);
        assertEquals(42, codec.read(input)[0]);
        assertEquals(43, codec.read(input)[0]);
        assertTrue(clock.now > 5000);
    }
    public void testZeroReadCannotBypassDeadline() throws Exception {
        Clock clock = new Clock();
        java.io.InputStream input = new java.io.InputStream() {
            int index;
            final byte[] header = {0, 0, 0, 1, 42};
            @Override public int read() { clock.now += 1100; return index < header.length ? header[index++] : -1; }
            @Override public int read(byte[] b, int off, int len) { clock.now += 1100; return 0; }
        };
        FrameDeadlineInput bounded = new FrameDeadlineInput(input, clock, ignored -> {}, 5000);
        try { new FrameCodec(32).read(bounded); fail(); } catch (IOException expected) { }
    }
}
