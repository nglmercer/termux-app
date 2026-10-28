package com.termux.app.control.ipc;

import java.io.IOException;
import java.io.InputStream;

/** Enforces one absolute deadline per frame, including idle time and partial reads. */
final class FrameDeadlineInput extends InputStream implements FrameCodec.FrameStart {
    interface Clock { long nowMillis(); }
    interface Timeout { void setMillis(int value) throws IOException; }
    private final InputStream input;
    private final Clock clock;
    private final Timeout timeout;
    private final int frameMillis;
    private long deadline = -1;

    FrameDeadlineInput(InputStream input, Clock clock, Timeout timeout, int frameMillis) {
        if (input == null || clock == null || timeout == null || frameMillis <= 0) throw new IllegalArgumentException();
        this.input = input; this.clock = clock; this.timeout = timeout; this.frameMillis = frameMillis;
    }
    @Override public void beginFrame() throws IOException {
        long now = clock.nowMillis();
        if (now < 0 || now > Long.MAX_VALUE - frameMillis) throw new IOException("Clock unavailable");
        deadline = now + frameMillis;
    }
    private void prepare() throws IOException {
        long now = clock.nowMillis();
        if (deadline < 0 || now < 0 || now >= deadline) throw new IOException("Frame deadline exceeded");
        timeout.setMillis((int) Math.max(1, Math.min((long) frameMillis, deadline - now)));
    }
    private void checkDeadline() throws IOException {
        long now = clock.nowMillis();
        if (now < 0 || now > deadline) throw new IOException("Frame deadline exceeded");
    }
    @Override public int read() throws IOException {
        prepare(); int value = input.read(); checkDeadline(); return value;
    }
    @Override public int read(byte[] buffer, int offset, int length) throws IOException {
        prepare(); int count = input.read(buffer, offset, length); checkDeadline(); return count;
    }
    @Override public void close() throws IOException { input.close(); }
}
