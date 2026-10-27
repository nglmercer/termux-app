package com.termux.terminal;

import junit.framework.TestCase;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class OutputReplayBufferTest extends TestCase {
    public void testOffsetsWrapAndGap() {
        OutputReplayBuffer buffer = new OutputReplayBuffer(5);
        assertTrue(buffer.append(new byte[]{1, 2, 3}, 0, 3));
        OutputReplayBuffer.ReadResult first = buffer.read(0, 2);
        assertTrue(Arrays.equals(new byte[]{1, 2}, first.data));
        assertEquals(2, first.nextOffset); assertEquals(3, first.endOffset); assertFalse(first.gap);
        assertTrue(buffer.append(new byte[]{4, 5, 6, 7}, 0, 4));
        OutputReplayBuffer.ReadResult next = buffer.read(0, 5);
        assertTrue(next.gap); assertEquals(2, next.offset); assertEquals(7, next.nextOffset);
        assertTrue(Arrays.equals(new byte[]{3, 4, 5, 6, 7}, next.data));
        assertEquals(0, buffer.read(7, 1).data.length);
    }
    public void testLargeChunkAndRevocation() {
        OutputReplayBuffer buffer = new OutputReplayBuffer(3);
        assertTrue(buffer.append(new byte[]{0, 1, 2, 3, 4, 5}, 0, 6));
        assertTrue(Arrays.equals(new byte[]{3, 4, 5}, buffer.read(0, 3).data));
        buffer.close();
        assertFalse(buffer.append(new byte[]{1}, 0, 1));
        try { buffer.read(0, 1); fail(); } catch (IllegalStateException expected) { }
    }
    public void testInvalidOffsetsAndLimits() {
        OutputReplayBuffer buffer = new OutputReplayBuffer(3);
        try { buffer.read(1, 1); fail(); } catch (IllegalArgumentException expected) { }
        try { buffer.read(0, OutputReplayBuffer.MAX_READ + 1); fail(); } catch (IllegalArgumentException expected) { }
        try { new OutputReplayBuffer(OutputReplayBuffer.MAX_CAPACITY + 1); fail(); } catch (IllegalArgumentException expected) { }
    }
    public void testConcurrentSnapshotsMatchPublishedOffsets() throws Exception {
        OutputReplayBuffer buffer = new OutputReplayBuffer(257);
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1);
        Thread producer = new Thread(() -> {
            try {
                start.await();
                for (int offset = 0; offset < 50000; offset += 50) {
                    byte[] chunk = new byte[50];
                    for (int i = 0; i < chunk.length; i++) chunk[i] = (byte) (offset + i);
                    buffer.append(chunk, 0, chunk.length);
                }
            } catch (Throwable e) { failure.set(e); }
        });
        Thread reader = new Thread(() -> {
            try {
                start.await();
                for (int n = 0; n < 1000; n++) {
                    OutputReplayBuffer.ReadResult result = buffer.read(0, 257);
                    for (int i = 0; i < result.data.length; i++)
                        if (result.data[i] != (byte) (result.offset + i)) throw new AssertionError("Wrong byte at offset");
                }
            } catch (Throwable e) { failure.set(e); }
        });
        producer.start(); reader.start(); start.countDown();
        producer.join(3000); reader.join(3000);
        assertFalse(producer.isAlive()); assertFalse(reader.isAlive());
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

    public void testProducerCompletesWithoutReader() throws Exception {
        OutputReplayBuffer buffer = new OutputReplayBuffer(32);
        CountDownLatch finished = new CountDownLatch(1);
        Thread producer = new Thread(() -> {
            byte[] chunk = new byte[128];
            for (int i = 0; i < 1000; i++) buffer.append(chunk, 0, chunk.length);
            finished.countDown();
        });
        producer.start();
        assertTrue(finished.await(2, TimeUnit.SECONDS));
        producer.join(2000);
        OutputReplayBuffer.ReadResult result = buffer.read(0, 32);
        assertTrue(result.gap); assertEquals(128000, result.endOffset); assertEquals(32, result.data.length);
    }
}
