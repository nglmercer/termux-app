package com.termux.app.control.ipc;

import junit.framework.TestCase;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class ServiceThreadDispatchTest extends TestCase {
    private static final class ManualExecutor implements Executor {
        final Queue<Runnable> queue = new ArrayDeque<>();
        @Override public synchronized void execute(Runnable task) { queue.add(task); notifyAll(); }
        synchronized Runnable take() throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (queue.isEmpty() && System.nanoTime() < deadline) wait(10);
            return queue.poll();
        }
    }

    public void testTimeoutBeforeStartCancelsOperationAndKeepsQueueBounded() throws Exception {
        ManualExecutor executor = new ManualExecutor();
        ServiceThreadDispatch dispatch = new ServiceThreadDispatch(executor, () -> false, 1);
        AtomicBoolean ran = new AtomicBoolean();
        try { dispatch.call(() -> { ran.set(true); return 1; }, 20); fail(); }
        catch (ServiceThreadDispatch.DispatchException e) { assertEquals(ServiceThreadDispatch.Failure.TIMEOUT_NOT_STARTED, e.reason); }
        try { dispatch.call(() -> 2, 20); fail(); }
        catch (ServiceThreadDispatch.DispatchException e) { assertEquals(ServiceThreadDispatch.Failure.OVERLOADED, e.reason); }
        Runnable queued = executor.take();
        assertNotNull(queued);
        queued.run();
        assertFalse(ran.get());
    }

    public void testTimeoutAfterStartReportsUnknownOutcome() throws Exception {
        ManualExecutor executor = new ManualExecutor();
        ServiceThreadDispatch dispatch = new ServiceThreadDispatch(executor, () -> false, 1);
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<ServiceThreadDispatch.Failure> result = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try { dispatch.call(() -> { started.countDown(); release.await(); return 1; }, 500); }
            catch (ServiceThreadDispatch.DispatchException e) { result.set(e.reason); }
        });
        caller.start();
        Runnable queued = executor.take();
        assertNotNull(queued);
        Thread service = new Thread(queued);
        service.start();
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS));
            caller.join(2000);
            assertFalse(caller.isAlive());
            assertEquals(ServiceThreadDispatch.Failure.OUTCOME_UNKNOWN, result.get());
        } finally {
            release.countDown();
            service.join(2000);
            caller.join(2000);
        }
    }

    public void testTwentyConcurrentCallersCompleteOnOneServiceThread() throws Exception {
        ExecutorService service = Executors.newSingleThreadExecutor();
        ExecutorService clients = Executors.newFixedThreadPool(20);
        ServiceThreadDispatch dispatch = new ServiceThreadDispatch(service, () -> false, 20);
        CountDownLatch start = new CountDownLatch(1), done = new CountDownLatch(20);
        AtomicInteger successful = new AtomicInteger();
        try {
            for (int i = 0; i < 20; i++) {
                clients.execute(() -> {
                    try {
                        start.await();
                        if (dispatch.call(() -> 1, 5000) == 1) successful.incrementAndGet();
                    } catch (InterruptedException | ServiceThreadDispatch.DispatchException e) {
                        // Count failures in the assertion below.
                    } finally { done.countDown(); }
                });
            }
            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS));
            assertEquals(20, successful.get());
        } finally {
            start.countDown();
            clients.shutdownNow();
            service.shutdownNow();
            clients.awaitTermination(2, TimeUnit.SECONDS);
            service.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    public void testCannotBlockServiceThread() throws Exception {
        ServiceThreadDispatch dispatch = new ServiceThreadDispatch(Runnable::run, () -> true, 1);
        try { dispatch.call(() -> 1, 100); fail(); } catch (IllegalStateException expected) { }
    }
}
