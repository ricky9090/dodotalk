package org.dodotalk.input;

import org.dodotalk.uilts.SqueakLogger;

import javax.swing.event.MouseInputAdapter;
import java.awt.Point;
import java.awt.event.MouseEvent;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

/**
 * Mouse state plus a small queue of button transitions.
 * <p>
 * Squeak 2.2's InputSensor polls {@code primMouseButtons} and {@code
 * primMousePoint}; it has no getNextEvent primitive. A click that starts and
 * ends between two polls used to be invisible because only the current button
 * bitmask was stored. The transition queue keeps every press/release until the
 * VM consumes it, while the current point is still available for dragging.
 * <p>
 * {@link #peekMousePoint()} pairs the point with the next queued transition so
 * that the image sees the coordinates of the event it is about to consume.
 */
public class MouseStatus extends MouseInputAdapter {

    public static final int RED = 4;
    public static final int YELLOW = 2;
    public static final int BLUE = 1;

    /**
     * Maximum idle polling rate for the Squeak point/button primitives.
     * <p>
     * Squeak 2.2 MVC menus (including the missing-sources popup) contain
     * unbounded loops such as {@code [Sensor anyButtonPressed] whileFalse: []}.
     * Without a limit, the VM calls Primitive 107 millions of times per second
     * and pegs one CPU core. 250 Hz matches current high-end touch sampling and
     * is far above what a 30-50 fps UI needs. Mouse input wakes the wait
     * immediately, so this limits only idle polling, not response latency.
     */
    public static final int POLL_RATE_HZ = 250;

    private static final long POLL_INTERVAL_NANOS = 1_000_000_000L / POLL_RATE_HZ;
    private static final int MAX_BUTTON = 8;
    private static final int MAX_QUEUED_EVENTS = 1024;

    private final Runnable inputSignal;
    private final Object lock = new Object();

    private final PollGate pointPollGate = new PollGate();
    private final PollGate buttonsPollGate = new PollGate();
    private long inputSequence;

    private final Deque<MouseEventRecord> events = new ArrayDeque<>();
    private final boolean[] physicalDown = new boolean[MAX_BUTTON + 1];
    private final int[] squeakButtonByPhysical = new int[MAX_BUTTON + 1];
    private final int[] squeakPressCounts = new int[8];

    /**
     * The transition currently being presented to the image. A transition is
     * removed only after both {@link #peekMousePoint()} and
     * {@link #consumeButtons()} have observed it. This keeps the point and the
     * button state paired across the two separate Squeak primitives.
     */
    private MouseEventRecord currentEvent;
    private boolean currentPointObserved;
    private boolean currentButtonsObserved;
    private int currentButtonsOnlyReads;

    private volatile int fX;
    private volatile int fY;
    private volatile int fButtons;
    private boolean overflowLogged;

    public MouseStatus() {
        this(() -> {
        });
    }

    public MouseStatus(Runnable inputSignal) {
        this.inputSignal = inputSignal == null ? () -> {
        } : inputSignal;
    }

    @Override
    public void mouseMoved(MouseEvent evt) {
        if (updatePoint(evt)) {
            signalInput();
        }
    }

    @Override
    public void mouseDragged(MouseEvent evt) {
        if (updatePoint(evt)) {
            signalInput();
        }
    }

    @Override
    public void mousePressed(MouseEvent evt) {
        updatePoint(evt);
        int physical = evt.getButton();
        if (physical <= 0 || physical > MAX_BUTTON) {
            return;
        }

        int squeakButton = mapButton(evt);
        if (squeakButton == 0) {
            return;
        }

        synchronized (lock) {
            if (physicalDown[physical]) {
                return; // duplicate press event
            }
            physicalDown[physical] = true;
            squeakButtonByPhysical[physical] = squeakButton;

            int before = fButtons;
            if (squeakPressCounts[squeakButton]++ == 0) {
                fButtons |= squeakButton;
            }
            if (fButtons != before) {
                enqueue(Type.PRESS, evt.getX(), evt.getY(), fButtons);
            }
        }
        signalInput();
    }

    @Override
    public void mouseReleased(MouseEvent evt) {
        updatePoint(evt);
        int physical = evt.getButton();
        if (physical <= 0 || physical > MAX_BUTTON) {
            return;
        }

        synchronized (lock) {
            if (!physicalDown[physical]) {
                return; // release without a matching press
            }
            physicalDown[physical] = false;
            int squeakButton = squeakButtonByPhysical[physical];
            squeakButtonByPhysical[physical] = 0;
            if (squeakButton == 0) {
                return;
            }

            int before = fButtons;
            if (squeakPressCounts[squeakButton] > 0
                    && --squeakPressCounts[squeakButton] == 0) {
                fButtons &= ~squeakButton;
            }
            if (fButtons != before) {
                enqueue(Type.RELEASE, evt.getX(), evt.getY(), fButtons);
            }
        }
        signalInput();
    }

    /**
     * Clears all button state, e.g. when the window loses focus and the release
     * event would otherwise be delivered to another window.
     */
    public void releaseAllButtons() {
        boolean hadButtons;
        boolean hadPending;
        synchronized (lock) {
            hadButtons = fButtons != 0;
            hadPending = currentEvent != null || !events.isEmpty();
            if (!hadButtons && !hadPending) {
                return;
            }

            Arrays.fill(physicalDown, false);
            Arrays.fill(squeakButtonByPhysical, 0);
            Arrays.fill(squeakPressCounts, 0);
            fButtons = 0;
            events.clear();
            currentEvent = null;
            currentPointObserved = false;
            currentButtonsObserved = false;
            currentButtonsOnlyReads = 0;
            if (hadButtons) {
                enqueue(Type.RELEASE, fX, fY, 0);
            }
        }
        signalInput();
    }

    /**
     * @return the current physical mouse button bitmask (without modifiers)
     */
    public int getButtons() {
        return fButtons;
    }

    public Point getPoint() {
        return new Point(fX, fY);
    }

    public int getX() {
        return fX;
    }

    public int getY() {
        return fY;
    }

    /**
     * @return the point of the transition currently being presented, or the
     * current point when no transition is pending
     */
    public Point peekMousePoint() {
        synchronized (lock) {
            MouseEventRecord event = currentEventOrNext();
            if (event == null) {
                return new Point(fX, fY);
            }
            currentPointObserved = true;
            currentButtonsOnlyReads = 0;
            return new Point(event.x, event.y);
        }
    }

    /**
     * Primitive 90 entry point. Like {@link #peekMousePoint()}, but limits
     * idle polls to {@link #POLL_RATE_HZ}. A mouse event wakes the wait
     * immediately, so drag and click latency is not tied to this rate.
     */
    public Point pollMousePoint() {
        awaitPollSlot(pointPollGate);
        return peekMousePoint();
    }

    /**
     * Consumes the button state of the transition currently being presented.
     * If no transition is pending, the current physical state is returned,
     * which keeps drag polling working.
     */
    public int consumeButtons() {
        synchronized (lock) {
            MouseEventRecord event = currentEventOrNext();
            if (event == null) {
                return fButtons;
            }

            if (!currentPointObserved && ++currentButtonsOnlyReads >= 3) {
                // A caller is polling only buttons (e.g. Sensor waitButton).
                // Let the transition advance after a few reads so such a loop
                // cannot get stuck on an old press event.
                currentPointObserved = true;
            }
            if (currentPointObserved) {
                currentButtonsObserved = true;
            }
            return event.buttons;
        }
    }

    /**
     * Primitive 107 entry point. Like {@link #consumeButtons()}, but limits
     * idle polls to {@link #POLL_RATE_HZ}. Any queued transition bypasses the
     * wait, and mouse input wakes a waiting poll immediately.
     */
    public int pollMouseButtons() {
        awaitPollSlot(buttonsPollGate);
        return consumeButtons();
    }

    /**
     * Adds a bounded wait before a sensor poll when the image is spinning.
     * The wait ends early when a mouse event arrives, so the image sees input
     * immediately instead of waiting for the next poll interval.
     */
    private void awaitPollSlot(PollGate gate) {
        synchronized (lock) {
            long now = System.nanoTime();
            long earliest = gate.lastPollNanos + POLL_INTERVAL_NANOS;
            long observed = gate.observedSequence;
            if (gate.initialized && now < earliest
                    && inputSequence == observed && !hasPendingTransitionLocked()) {
                while (now < earliest && inputSequence == observed
                        && !hasPendingTransitionLocked()) {
                    long remaining = earliest - now;
                    try {
                        lock.wait(remaining / 1_000_000L,
                                (int) (remaining % 1_000_000L));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    now = System.nanoTime();
                }
            }
            gate.lastPollNanos = now;
            gate.observedSequence = inputSequence;
            gate.initialized = true;
        }
    }

    private boolean hasPendingTransitionLocked() {
        return !events.isEmpty()
                || (currentEvent != null && !(currentPointObserved && currentButtonsObserved));
    }

    /**
     * Number of transitions that have not been observed by both primitives.
     */
    public int queueSize() {
        synchronized (lock) {
            int pending = events.size();
            if (currentEvent != null && !(currentPointObserved && currentButtonsObserved)) {
                pending++;
            }
            return pending;
        }
    }

    static int mapButton(MouseEvent evt) {
        switch (evt.getButton()) {
            case MouseEvent.BUTTON1:
                if (evt.isControlDown()) {
                    return YELLOW;
                }
                if (evt.isAltDown()) {
                    return BLUE;
                }
                return RED;
            case MouseEvent.BUTTON2:
                return BLUE; // middle (frame menu)
            case MouseEvent.BUTTON3:
                return YELLOW; // right (pane menu)
            default:
                return 0;
        }
    }

    private boolean updatePoint(MouseEvent evt) {
        int newX = evt.getX();
        int newY = evt.getY();
        if (newX == fX && newY == fY) {
            return false;
        }
        fX = newX;
        fY = newY;
        return true;
    }

    private MouseEventRecord currentEventOrNext() {
        if (currentEvent != null && currentPointObserved && currentButtonsObserved) {
            // Both Squeak primitives have seen this transition; advance on the
            // next call, never in the middle of a point/buttons pair.
            currentEvent = null;
            currentPointObserved = false;
            currentButtonsObserved = false;
            currentButtonsOnlyReads = 0;
        }
        if (currentEvent == null) {
            currentEvent = events.pollFirst();
            currentPointObserved = false;
            currentButtonsObserved = false;
            currentButtonsOnlyReads = 0;
        }
        return currentEvent;
    }

    private void enqueue(Type type, int x, int y, int buttons) {
        if (events.size() >= MAX_QUEUED_EVENTS) {
            events.removeFirst();
            if (!overflowLogged) {
                overflowLogged = true;
                SqueakLogger.log_W("Mouse event queue overflow; dropping oldest transition");
            }
        }
        events.addLast(new MouseEventRecord(type, x, y, buttons, System.nanoTime()));
    }

    private void signalInput() {
        synchronized (lock) {
            inputSequence++;
            lock.notifyAll();
        }
        inputSignal.run();
    }

    /**
     * Per-primitive poll rate limiter. Fields are guarded by {@link #lock}.
     */
    private static final class PollGate {
        private boolean initialized;
        private long lastPollNanos;
        private long observedSequence;
    }

    public enum Type {
        PRESS,
        RELEASE
    }

    public static final class MouseEventRecord {
        public final Type type;
        public final int x;
        public final int y;
        public final int buttons;
        public final long timestampNanos;

        MouseEventRecord(Type type, int x, int y, int buttons, long timestampNanos) {
            this.type = type;
            this.x = x;
            this.y = y;
            this.buttons = buttons;
            this.timestampNanos = timestampNanos;
        }
    }
}
