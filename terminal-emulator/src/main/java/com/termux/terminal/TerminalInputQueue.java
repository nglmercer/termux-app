package com.termux.terminal;

/** Bounded FIFO for terminal input. Producers never wait for the PTY writer. */
final class TerminalInputQueue {
    private final int mCapacity;
    private final int mInteractiveReserve;
    private byte[] mBuffer;
    private int mHead;
    private int mBytes;
    private boolean mOpen = true;

    TerminalInputQueue(int capacity) { this(capacity, 0); }

    TerminalInputQueue(int capacity, int interactiveReserve) {
        if (capacity <= 0 || interactiveReserve < 0 || interactiveReserve >= capacity)
            throw new IllegalArgumentException("invalid capacity or interactive reserve");
        mCapacity = capacity;
        mInteractiveReserve = interactiveReserve;
        mBuffer = new byte[Math.min(capacity, 4096)];
    }

    /** Enqueue a whole write or reject it without enqueuing any bytes. */
    synchronized boolean offer(byte[] data, int offset, int count) {
        return offer(data, offset, count, false);
    }

    /** Keyboard code points use reserved space but never overtake previously queued data. */
    synchronized boolean offerInteractive(byte[] data, int offset, int count) {
        return offer(data, offset, count, true);
    }

    private boolean offer(byte[] data, int offset, int count, boolean interactive) {
        if (offset < 0 || count < 0 || offset > data.length - count)
            throw new IndexOutOfBoundsException();
        int limit = interactive ? mCapacity : mCapacity - mInteractiveReserve;
        if (!mOpen || count > limit - mBytes) return false;
        if (count == 0) return true;
        int required = mBytes + count;
        if (required > mBuffer.length) {
            int size = Math.min(mCapacity, Math.max(required, mBuffer.length * 2));
            byte[] expanded = new byte[size];
            copyOut(expanded, mBytes);
            mBuffer = expanded;
            mHead = 0;
        }
        int tail = (mHead + mBytes) % mBuffer.length;
        int first = Math.min(count, mBuffer.length - tail);
        System.arraycopy(data, offset, mBuffer, tail, first);
        System.arraycopy(data, offset + first, mBuffer, 0, count - first);
        mBytes += count;
        notifyAll();
        return true;
    }

    private void copyOut(byte[] destination, int count) {
        int first = Math.min(count, mBuffer.length - mHead);
        System.arraycopy(mBuffer, mHead, destination, 0, first);
        System.arraycopy(mBuffer, 0, destination, first, count - first);
    }

    /** Wait for data on the PTY writer thread, or return -1 after close. */
    synchronized int read(byte[] destination) {
        if (destination.length == 0) throw new IllegalArgumentException("empty destination");
        while (mOpen && mBytes == 0) {
            try {
                wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            }
        }
        if (!mOpen) return -1;
        int count = Math.min(destination.length, mBytes);
        copyOut(destination, count);
        mHead = (mHead + count) % mBuffer.length;
        mBytes -= count;
        if (mBytes == 0 && mBuffer.length > 4096) {
            mBuffer = new byte[Math.min(mCapacity, 4096)];
            mHead = 0;
        }
        return count;
    }

    synchronized void close() {
        mOpen = false;
        mBuffer = new byte[0];
        mBytes = 0;
        mHead = 0;
        notifyAll();
    }
}
