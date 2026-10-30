package org.dodotalk.input;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import java.awt.event.MouseEvent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the bounded Sensor polling introduced for MVC busy
 * loops such as {@code [Sensor anyButtonPressed] whileFalse: []}.
 */
class MousePollThrottleTest {

    private static final JPanel SOURCE = new JPanel();

    @Test
    @DisplayName("idle button polling is capped near the configured rate")
    void idleButtonPollingIsCapped() {
        MouseStatus status = new MouseStatus();

        long start = System.nanoTime();
        int polls = 0;
        while (System.nanoTime() - start < 100_000_000L) {
            status.pollMouseButtons();
            polls++;
        }
        long elapsed = System.nanoTime() - start;
        double hz = polls * 1_000_000_000.0 / elapsed;

        assertTrue(hz <= MouseStatus.POLL_RATE_HZ * 1.5,
                "polling was not capped: " + hz + " Hz");
        assertTrue(hz >= MouseStatus.POLL_RATE_HZ * 0.5,
                "polling was throttled too aggressively: " + hz + " Hz");
    }

    @Test
    @DisplayName("normal frame cadence is not delayed by the idle cap")
    void normalFrameCadenceIsNotDelayed() throws Exception {
        MouseStatus status = new MouseStatus();
        status.pollMouseButtons();

        Thread.sleep(20);
        long start = System.nanoTime();
        status.pollMouseButtons();
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;

        assertTrue(elapsedMillis < 20,
                "a 50 fps poll was delayed by the idle cap: " + elapsedMillis + "ms");
    }

    @Test
    @DisplayName("point and button primitives have independent poll gates")
    void pointAndButtonGatesAreIndependent() {
        MouseStatus status = new MouseStatus();
        status.pollMousePoint();

        long start = System.nanoTime();
        status.pollMouseButtons();
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;

        assertTrue(elapsedMillis < 20,
                "paired point/button polls were delayed: " + elapsedMillis + "ms");
    }

    @Test
    @DisplayName("a mouse press wakes an idle poll immediately")
    void mousePressWakesIdlePoll() throws Exception {
        MouseStatus status = new MouseStatus();
        status.pollMouseButtons(); // establish the poll gate

        Thread presser = new Thread(() -> {
            sleepSilently(10);
            status.mousePressed(mouseEvent(MouseEvent.BUTTON1));
        });
        presser.start();

        long start = System.nanoTime();
        int buttons = 0;
        while (buttons == 0 && System.nanoTime() - start < 500_000_000L) {
            buttons = status.pollMouseButtons();
        }
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;
        presser.join(1000);

        assertEquals(MouseStatus.RED, buttons, "the press was not observed");
        assertTrue(elapsedMillis < 200,
                "the press did not wake the poll promptly: " + elapsedMillis + "ms");
    }

    @Test
    @DisplayName("a queued transition bypasses the idle cap")
    void queuedTransitionBypassesIdleCap() {
        MouseStatus status = new MouseStatus();
        status.mousePressed(mouseEvent(MouseEvent.BUTTON1));

        assertEquals(MouseStatus.RED, status.pollMouseButtons());
        long start = System.nanoTime();
        var point = status.pollMousePoint();
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;

        assertEquals(10, point.x, "the queued point was not returned");
        assertEquals(20, point.y, "the queued point was not returned");
        assertTrue(elapsedMillis < 20,
                "a queued transition was delayed by the idle cap: " + elapsedMillis + "ms");
    }

    @Test
    @DisplayName("an interrupted mouse poll keeps the idle cap")
    void interruptedPollKeepsThrottle() throws Exception {
        MouseStatus status = new MouseStatus();
        status.pollMouseButtons(); // establish the poll gate

        final long[] polls = new long[1];
        Thread poller = new Thread(() -> {
            long start = System.nanoTime();
            while (System.nanoTime() - start < 300_000_000L) {
                status.pollMouseButtons();
                polls[0]++;
            }
        });
        poller.start();
        Thread.sleep(30);
        poller.interrupt();
        poller.join(1000);

        double hz = polls[0] / 0.3;
        assertFalse(poller.isInterrupted(),
                "the poll gate must not leave the VM thread interrupted");
        assertTrue(hz <= MouseStatus.POLL_RATE_HZ * 1.5,
                "interruption disabled the mouse throttle: " + hz + " Hz");
    }

    private static MouseEvent mouseEvent(int button) {
        return new MouseEvent(SOURCE, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(),
                0, 10, 20, 1, false, button);
    }

    private static void sleepSilently(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
