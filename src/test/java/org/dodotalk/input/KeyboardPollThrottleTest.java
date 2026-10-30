package org.dodotalk.input;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import java.awt.event.KeyEvent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the bounded keyboard polling introduced for MVC busy
 * loops such as {@code [Sensor keyboardPressed] whileFalse: []} and the
 * {@code Object>>primitiveError:} fallback.
 */
class KeyboardPollThrottleTest {

    private static final JPanel SOURCE = new JPanel();

    @Test
    @DisplayName("idle keyboard peek polling is capped near the configured rate")
    void idlePeekPollingIsCapped() {
        KeyboardQueue queue = new KeyboardQueue();

        long start = System.nanoTime();
        int polls = 0;
        while (System.nanoTime() - start < 100_000_000L) {
            assertEquals(0, queue.pollPeek());
            polls++;
        }
        double hz = polls * 1_000_000_000.0 / (System.nanoTime() - start);

        assertTrue(hz <= KeyboardQueue.POLL_RATE_HZ * 1.5,
                "keyboard polling was not capped: " + hz + " Hz");
        assertTrue(hz >= KeyboardQueue.POLL_RATE_HZ * 0.5,
                "keyboard polling was throttled too aggressively: " + hz + " Hz");
    }

    @Test
    @DisplayName("idle keyboard next polling is capped near the configured rate")
    void idleNextPollingIsCapped() {
        KeyboardQueue queue = new KeyboardQueue();

        long start = System.nanoTime();
        int polls = 0;
        while (System.nanoTime() - start < 100_000_000L) {
            assertEquals(Integer.MIN_VALUE, queue.pollNext());
            polls++;
        }
        double hz = polls * 1_000_000_000.0 / (System.nanoTime() - start);

        assertTrue(hz <= KeyboardQueue.POLL_RATE_HZ * 1.5,
                "keyboard next polling was not capped: " + hz + " Hz");
        assertTrue(hz >= KeyboardQueue.POLL_RATE_HZ * 0.5,
                "keyboard next polling was throttled too aggressively: " + hz + " Hz");
    }

    @Test
    @DisplayName("a queued character bypasses the idle cap")
    void queuedCharacterBypassesIdleCap() {
        KeyboardQueue queue = new KeyboardQueue();
        queue.pollPeek(); // establish the peek poll gate

        queue.keyTyped(typedKey('x'));
        long start = System.nanoTime();
        assertEquals('x', queue.pollPeek());
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;

        assertTrue(elapsedMillis < 20,
                "a queued character was delayed by the idle cap: " + elapsedMillis + "ms");
    }

    @Test
    @DisplayName("a key event wakes an idle poll immediately")
    void keyEventWakesIdlePoll() throws Exception {
        KeyboardQueue queue = new KeyboardQueue();
        queue.pollPeek(); // establish the poll gate

        Thread typist = new Thread(() -> {
            sleepSilently(10);
            queue.keyTyped(typedKey('x'));
        });
        typist.start();

        long start = System.nanoTime();
        int peeked = 0;
        while (peeked == 0 && System.nanoTime() - start < 500_000_000L) {
            peeked = queue.pollPeek();
        }
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;
        typist.join(1000);

        assertEquals('x', peeked, "the key event was not observed");
        assertTrue(elapsedMillis < 200,
                "the key event did not wake the poll promptly: " + elapsedMillis + "ms");
    }

    @Test
    @DisplayName("an interrupted keyboard poll keeps the idle cap")
    void interruptedPollKeepsThrottle() throws Exception {
        KeyboardQueue queue = new KeyboardQueue();
        queue.pollPeek(); // establish the poll gate

        final long[] polls = new long[1];
        Thread poller = new Thread(() -> {
            long start = System.nanoTime();
            while (System.nanoTime() - start < 300_000_000L) {
                queue.pollPeek();
                polls[0]++;
            }
        });
        poller.start();
        sleepSilently(30);
        poller.interrupt();
        poller.join(1000);

        double hz = polls[0] / 0.3;
        assertFalse(poller.isInterrupted(),
                "the poll gate must not leave the VM thread interrupted");
        assertTrue(hz <= KeyboardQueue.POLL_RATE_HZ * 1.5,
                "interruption disabled the keyboard throttle: " + hz + " Hz");
    }

    private static KeyEvent typedKey(char keyChar) {
        return new KeyEvent(SOURCE, KeyEvent.KEY_TYPED, System.currentTimeMillis(),
                0, KeyEvent.VK_UNDEFINED, keyChar);
    }

    private static void sleepSilently(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
