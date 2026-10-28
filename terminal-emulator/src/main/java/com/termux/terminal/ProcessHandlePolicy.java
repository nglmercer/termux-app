package com.termux.terminal;

/** Tokens bind session creation to one explicit process-handle opt-in interval. */
final class ProcessHandlePolicy {
    private long generation, active;
    synchronized void setEnabled(boolean enabled) {
        if (enabled) {
            if (++generation == 0) ++generation;
            active = generation;
        } else active = 0;
    }
    synchronized long capture() { return active; }
    synchronized boolean permits(long token) { return token != 0 && token == active; }
}
