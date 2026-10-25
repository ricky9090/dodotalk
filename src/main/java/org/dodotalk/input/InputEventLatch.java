package org.dodotalk.input;

/**
 * A monotonic input notification latch.
 * <p>
 * The VM used to rely on a transient {@code screenEvent} boolean: the notifier
 * set it for about 200 nanoseconds and the VM had to observe exactly that
 * window. Notifications could be missed, and a VM that woke up too late kept
 * waiting for the next heartbeat.
 * <p>
 * This latch keeps a monotonically increasing sequence number instead. A signal
 * is never lost: if it arrives before {@link #await(long)}, the next call
 * observes it immediately; if it arrives while waiting, the waiter is notified.
 * Multiple signals may be coalesced into one wakeup, which is exactly what a
 * processor-relinquish primitive needs.
 */
public final class InputEventLatch {

    private long sequence;
    private long observed;

    /**
     * Records an input/timer event and wakes a waiting VM thread.
     */
    public synchronized void signal() {
        sequence++;
        notifyAll();
    }

    /**
     * @return true when at least one signal has not been consumed yet
     */
    public synchronized boolean hasPending() {
        return sequence != observed;
    }

    /**
     * Waits until a signal is available or the timeout expires.
     * <p>
     * Pending signals are consumed (coalesced) by the call. The wait is bounded
     * and uses the monitor's timed wait, so an idle VM does not busy-spin.
     *
     * @param timeoutMillis maximum time to wait in milliseconds; zero means
     *                      "only check for a pending signal"
     * @return true if a signal was observed, false on timeout or interruption
     */
    public synchronized boolean await(long timeoutMillis) {
        if (sequence != observed) {
            observed = sequence;
            return true;
        }
        if (timeoutMillis <= 0) {
            return false;
        }

        final long timeoutNanos = timeoutMillis * 1_000_000L;
        final long deadline = System.nanoTime() + timeoutNanos;
        while (sequence == observed) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return false;
            }
            try {
                wait(remaining / 1_000_000L, (int) (remaining % 1_000_000L));
            } catch (InterruptedException e) {
                // Preserve the pending signal for the caller and let the
                // interrupt policy be decided at the VM boundary.
                Thread.currentThread().interrupt();
                return false;
            }
        }
        observed = sequence;
        return true;
    }
}
