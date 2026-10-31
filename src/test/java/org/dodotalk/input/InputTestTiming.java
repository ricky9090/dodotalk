package org.dodotalk.input;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Timing helpers for the input poll throttle tests.
 * <p>
 * These tests must not use a wall-clock average: a scheduling pause inflates
 * the elapsed time without adding polls. The minimum interval between two
 * consecutive polls is stable in one direction: a descheduled thread can only
 * make an interval longer, never shorter.
 */
final class InputTestTiming {

    /** Nominal throttle interval derived from the production constant. */
    static final long EXPECTED_INTERVAL_NANOS =
            1_000_000_000L / MouseStatus.POLL_RATE_HZ;

    /** Number of intervals sampled by each throttle/bypass assertion. */
    static final int SAMPLES = 20;

    private InputTestTiming() {
    }

    /**
     * Verifies that consecutive polls are separated by roughly the configured
     * interval. The lower bound proves the gate is active; the upper bound
     * proves it has not been slowed down to less than half the target rate.
     */
    static void assertThrottled(String label, Runnable poll) {
        poll.run(); // establish the gate
        long previous = System.nanoTime();
        long minimum = Long.MAX_VALUE;
        for (int i = 0; i < SAMPLES; i++) {
            poll.run();
            long now = System.nanoTime();
            minimum = Math.min(minimum, now - previous);
            previous = now;
        }

        assertTrue(minimum >= EXPECTED_INTERVAL_NANOS * 3 / 4,
                label + " polled faster than the idle cap: " + minimum + "ns");
        assertTrue(minimum < EXPECTED_INTERVAL_NANOS * 2,
                label + " was throttled below half the target rate: " + minimum + "ns");
    }

    /**
     * Verifies that an operation which should bypass the idle gate does so at
     * least once. A broken bypass waits for the full interval; the minimum of
     * several attempts stays well below that even under scheduling load.
     */
    static void assertBypassesThrottle(String label, Runnable action) {
        long minimum = minimumNanos(SAMPLES, action);
        assertTrue(minimum < EXPECTED_INTERVAL_NANOS * 3 / 4,
                label + " waited for the idle gate: " + minimum + "ns");
    }

    /**
     * Returns the shortest duration of {@code samples} executions of the
     * action. The minimum is robust against occasional scheduling pauses.
     */
    static long minimumNanos(int samples, Runnable action) {
        long minimum = Long.MAX_VALUE;
        for (int i = 0; i < samples; i++) {
            long start = System.nanoTime();
            action.run();
            minimum = Math.min(minimum, System.nanoTime() - start);
        }
        return minimum;
    }
}
