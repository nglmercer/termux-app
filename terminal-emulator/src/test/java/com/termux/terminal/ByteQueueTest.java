package com.termux.terminal;

import junit.framework.TestCase;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class ByteQueueTest extends TestCase {

	private static void assertArrayEquals(byte[] expected, byte[] actual) {
		if (expected.length != actual.length) {
			fail("Difference array length");
		}
		for (int i = 0; i < expected.length; i++) {
			if (expected[i] != actual[i]) {
				fail("Inequals at index=" + i + ", expected=" + (int) expected[i] + ", actual=" + (int) actual[i]);
			}
		}
	}

	public void testCompleteWrites() throws Exception {
		ByteQueue q = new ByteQueue(10);
		assertTrue(q.write(new byte[]{1, 2, 3}, 0, 3));

		byte[] arr = new byte[10];
		assertEquals(3, q.read(arr, true));
		assertArrayEquals(new byte[]{1, 2, 3}, new byte[]{arr[0], arr[1], arr[2]});

		assertTrue(q.write(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10}, 0, 10));
		assertEquals(10, q.read(arr, true));
		assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10}, arr);
	}

	public void testQueueWraparound() throws Exception {
		ByteQueue q = new ByteQueue(10);

		byte[] origArray = new byte[]{1, 2, 3, 4, 5, 6};
		byte[] readArray = new byte[origArray.length];
		for (int i = 0; i < 20; i++) {
			q.write(origArray, 0, origArray.length);
			assertEquals(origArray.length, q.read(readArray, true));
			assertArrayEquals(origArray, readArray);
		}
	}

	public void testWriteNotesClosing() throws Exception {
		ByteQueue q = new ByteQueue(10);
		q.close();
		assertFalse(q.write(new byte[]{1, 2, 3}, 0, 3));
	}

	public void testReadNonBlocking() throws Exception {
		ByteQueue q = new ByteQueue(10);
		assertEquals(0, q.read(new byte[128], false));
	}


    public void testFullQueueBlocksWriterUntilReaderDrains() throws Exception {
        ByteQueue q = new ByteQueue(2);
        assertTrue(q.write(new byte[]{1, 2}, 0, 2));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean result = new AtomicBoolean();
        Thread writer = new Thread(() -> {
            started.countDown();
            result.set(q.write(new byte[]{3, 4}, 0, 2));
            finished.countDown();
        });
        writer.start();
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertFalse("Writer should wait while the queue is full", finished.await(100, TimeUnit.MILLISECONDS));
            byte[] read = new byte[2];
            assertEquals(2, q.read(read, true));
            assertArrayEquals(new byte[]{1, 2}, read);
            assertTrue("Draining the queue should release the writer", finished.await(2, TimeUnit.SECONDS));
            assertTrue(result.get());
            assertEquals(2, q.read(read, true));
            assertArrayEquals(new byte[]{3, 4}, read);
        } finally {
            q.close();
            writer.join(2000);
        }
        assertFalse(writer.isAlive());
    }

    public void testClosingFullQueueReleasesWriter() throws Exception {
        ByteQueue q = new ByteQueue(1);
        assertTrue(q.write(new byte[]{1}, 0, 1));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean result = new AtomicBoolean(true);
        Thread writer = new Thread(() -> {
            started.countDown();
            result.set(q.write(new byte[]{2}, 0, 1));
            finished.countDown();
        });
        writer.start();
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertFalse(finished.await(100, TimeUnit.MILLISECONDS));
            q.close();
            assertTrue("Closing should release a blocked writer", finished.await(2, TimeUnit.SECONDS));
            assertFalse(result.get());
        } finally {
            q.close();
            writer.join(2000);
        }
        assertFalse(writer.isAlive());
    }

}
