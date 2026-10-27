package com.termux.terminal;

import junit.framework.TestCase;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class TerminalInputQueueTest extends TestCase {
    public void testCapacityRejectionIsAtomicAndDoesNotWait() {
        TerminalInputQueue q = new TerminalInputQueue(5);
        assertTrue(q.offer(new byte[]{1, 2, 3}, 0, 3));
        assertFalse(q.offer(new byte[]{4, 5, 6}, 0, 3));
        assertTrue(q.offer(new byte[]{7, 8}, 0, 2));
        byte[] result = new byte[5];
        assertEquals(5, q.read(result));
        assertTrue(java.util.Arrays.equals(new byte[]{1, 2, 3, 7, 8}, result));
        q.close();
        assertFalse(q.offer(new byte[]{9}, 0, 1));
    }

    public void testInteractiveReservePreservesFifoAndBound() {
        TerminalInputQueue q = new TerminalInputQueue(8, 3);
        assertTrue(q.offer(new byte[]{1, 2, 3, 4, 5}, 0, 5));
        assertFalse(q.offer(new byte[]{6}, 0, 1));
        assertTrue(q.offerInteractive(new byte[]{3}, 0, 1)); // Ctrl-C remains an ordinary byte.
        assertTrue(q.offerInteractive(new byte[]{26, 7}, 0, 2));
        assertFalse(q.offerInteractive(new byte[]{8}, 0, 1));
        byte[] result = new byte[8];
        assertEquals(8, q.read(result));
        assertTrue(java.util.Arrays.equals(new byte[]{1, 2, 3, 4, 5, 3, 26, 7}, result));
        assertTrue(q.offer(new byte[]{9}, 0, 1));
        q.close();
    }

    public void testWrapAndResizePreserveOrderingAndCopyInput() {
        TerminalInputQueue q = new TerminalInputQueue(8192);
        byte[] initial = new byte[4000];
        java.util.Arrays.fill(initial, (byte) 1);
        assertTrue(q.offer(initial, 0, initial.length));
        java.util.Arrays.fill(initial, (byte) 9);
        assertEquals(3000, q.read(new byte[3000]));
        byte[] second = new byte[3500];
        java.util.Arrays.fill(second, (byte) 2);
        assertTrue(q.offer(second, 0, second.length));
        byte[] result = new byte[4500];
        assertEquals(4500, q.read(result));
        for (int i = 0; i < result.length; i++) assertEquals(i < 1000 ? 1 : 2, result[i]);
    }

    public void testConcurrentProducerAndSlowReaderPreserveBytes() throws Exception {
        TerminalInputQueue q = new TerminalInputQueue(128);
        byte[] expected = new byte[20000];
        for (int i = 0; i < expected.length; i++) expected[i] = (byte) (i * 31);
        byte[] actual = new byte[expected.length];
        AtomicInteger readCount = new AtomicInteger();
        Thread reader = new Thread(() -> {
            byte[] buffer = new byte[31];
            while (readCount.get() < actual.length) {
                int n = q.read(buffer);
                if (n < 0) return;
                System.arraycopy(buffer, 0, actual, readCount.get(), n);
                readCount.addAndGet(n);
                Thread.yield();
            }
        });
        reader.start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            int offset = 0;
            while (offset < expected.length && System.nanoTime() < deadline) {
                int n = Math.min(37, expected.length - offset);
                if (q.offer(expected, offset, n)) offset += n;
                else Thread.yield();
            }
            assertEquals("Producer timed out", expected.length, offset);
            reader.join(2000);
            assertFalse("Reader timed out", reader.isAlive());
            assertEquals(expected.length, readCount.get());
            assertTrue(java.util.Arrays.equals(expected, actual));
        } finally {
            q.close();
            reader.join(2000);
        }
    }

    public void testCloseWakesWaitingReader() throws Exception {
        TerminalInputQueue q = new TerminalInputQueue(5);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicInteger result = new AtomicInteger();
        Thread reader = new Thread(() -> {
            started.countDown();
            result.set(q.read(new byte[1]));
            finished.countDown();
        });
        reader.start();
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS));
            q.close();
            assertTrue(finished.await(2, TimeUnit.SECONDS));
            assertEquals(-1, result.get());
        } finally {
            q.close();
            reader.join(2000);
        }
        assertFalse(reader.isAlive());
    }
}
