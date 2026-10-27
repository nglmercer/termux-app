package com.termux.app.control.ipc;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded background-to-service-thread handoff. It never opens a socket. */
public final class ServiceThreadDispatch {
    public enum Failure { OVERLOADED, TIMEOUT_NOT_STARTED, OUTCOME_UNKNOWN, EXECUTION_FAILED }
    public static final class DispatchException extends Exception {
        public final Failure reason;
        DispatchException(Failure reason) { super(reason.name()); this.reason = reason; }
    }
    public interface ThreadCheck { boolean isServiceThread(); }
    private static final int PENDING = 0, RUNNING = 1, FINISHED = 2, CANCELLED = 3;
    private final Executor mExecutor;
    private final ThreadCheck mThreadCheck;
    private final Semaphore mSlots;

    public ServiceThreadDispatch(Executor executor, ThreadCheck threadCheck, int maxPending) {
        if (executor == null || threadCheck == null || maxPending < 1 || maxPending > 128)
            throw new IllegalArgumentException("Invalid dispatcher configuration");
        mExecutor = executor;
        mThreadCheck = threadCheck;
        mSlots = new Semaphore(maxPending);
    }

    /**
     * Wait only on a background thread. A timed-out running operation may have side effects;
     * callers must not automatically retry an OUTCOME_UNKNOWN result.
     */
    public <T> T call(Callable<T> action, long timeoutMs) throws DispatchException {
        if (action == null || timeoutMs <= 0 || timeoutMs > 30000)
            throw new IllegalArgumentException("Invalid request");
        if (mThreadCheck.isServiceThread()) throw new IllegalStateException("Cannot wait on service thread");
        if (!mSlots.tryAcquire()) throw new DispatchException(Failure.OVERLOADED);
        Pending<T> pending = new Pending<>(action);
        try {
            mExecutor.execute(() -> {
                try {
                    if (pending.state.compareAndSet(PENDING, RUNNING)) pending.run();
                } finally {
                    pending.done.countDown();
                    mSlots.release();
                }
            });
        } catch (RejectedExecutionException e) {
            mSlots.release();
            throw new DispatchException(Failure.OVERLOADED);
        }
        boolean completed;
        try {
            completed = pending.done.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw timeout(pending);
        }
        if (!completed) {
            if (pending.state.get() != FINISHED) throw timeout(pending);
        }
        if (pending.failure != null) throw new DispatchException(Failure.EXECUTION_FAILED);
        return pending.value;
    }

    private static DispatchException timeout(Pending<?> pending) {
        if (pending.state.compareAndSet(PENDING, CANCELLED)) return new DispatchException(Failure.TIMEOUT_NOT_STARTED);
        return new DispatchException(Failure.OUTCOME_UNKNOWN);
    }

    private static final class Pending<T> {
        final Callable<T> action;
        final AtomicInteger state = new AtomicInteger(PENDING);
        final CountDownLatch done = new CountDownLatch(1);
        volatile T value;
        volatile Throwable failure;
        Pending(Callable<T> action) { this.action = action; }
        void run() {
            try { value = action.call(); }
            catch (Throwable t) { failure = t; }
            finally { state.set(FINISHED); }
            if (failure instanceof Error) throw (Error) failure;
        }
    }
}
