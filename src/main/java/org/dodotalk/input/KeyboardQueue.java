package org.dodotalk.input;

import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.IntPredicate;

/**
 * I'm DodoTalk's keyboard driver.  I convert Java KeyEvents into Squeak
 * key and modifier key press events.
 * <p>
 * The optional {@code interruptKey} callback receives the Squeak keycode
 * (low byte character, high nibble modifiers: command=8, option=4, control=2,
 * shift=1). Returning true consumes the key: it is not added to the
 * type-ahead queue. This is how {@code cmd-.} becomes an interrupt without
 * appearing as typed input.
 */
public class KeyboardQueue implements KeyListener {
    /**
     * The size of the character queue. The original limit of 8 dropped
     * keystrokes during fast typing; the queue is cheap, so keep more.
     */
    private static final int TYPEAHEAD_LIMIT = 256;

    /**
     * Maximum idle polling rate for the Squeak keyboard primitives.
     * <p>
     * Squeak 2.2 contains unbounded keyboard waits such as
     * {@code [Sensor keyboardPressed] whileFalse: []} and the
     * {@code Object>>primitiveError:} fallback. Primitive 109 used to return
     * immediately with an empty queue, so those loops could execute millions
     * of times per second. This is the same 250Hz policy as the mouse
     * primitives; a real key event wakes the wait immediately.
     */
    public static final int POLL_RATE_HZ = MouseStatus.POLL_RATE_HZ;

    private static final long POLL_INTERVAL_NANOS = 1_000_000_000L / POLL_RATE_HZ;

    /**
     * See instance methods of InputSensor.
     */
    private static final int
            SHIFT_KEY = 8,
            CONTROL_KEY = 16,
            COMMAND_KEY = 64;

    /**
     * Modifier bits of a Squeak keycode: the high nibble of a 12-bit keycode
     * is {@code <command><option><control><shift>}.
     */
    private static final int
            KEYCODE_SHIFT = 1,
            KEYCODE_CONTROL = 2,
            KEYCODE_COMMAND = 8;

    /**
     * See ParagraphEditor class>>initializeCmdKeyShortcuts.
     */
    private static final char
            CURSOR_HOME = 1,
            CURSOR_END = 4,
            CR_WITH_IDENT = 13,
            SELECT_CURRENT_TYPE_IN = 27,
            CURSOR_LEFT = 28,
            CURSOR_RIGHT = 29,
            CURSOR_UP = 30,
            CURSOR_DOWN = 31;

    /**
     * Squeak keys that do not map to Java key events. Order *MUST* match that of JAVA_KEYS.
     */
    private static final char[] SQUEAK_KEYS =
            {
                    CURSOR_HOME,
                    CURSOR_END,
                    CR_WITH_IDENT,
                    CURSOR_LEFT,
                    CURSOR_RIGHT,
                    CURSOR_UP,
                    CURSOR_DOWN,
                    SELECT_CURRENT_TYPE_IN,
            };

    /**
     * Counterpart of squeak keys.  Order *MUST* match that of SQUEAK_KEYS.
     */
    private static final int[] JAVA_KEYS =
            {
                    KeyEvent.VK_HOME,
                    KeyEvent.VK_END,
                    KeyEvent.VK_ENTER,
                    KeyEvent.VK_LEFT,
                    KeyEvent.VK_RIGHT,
                    KeyEvent.VK_UP,
                    KeyEvent.VK_DOWN,
                    KeyEvent.VK_BACK_QUOTE,
            };

    private final Runnable inputSignal;

    private final IntPredicate interruptKey;

    private final Deque<Character> fCharQueue = new ArrayDeque<>();

    private volatile int fModifierKeys = 0;

    /**
     * A keyPressed mapping (special key or command shortcut) already queued
     * this character. Suppress the immediately following keyTyped duplicate;
     * this also keeps a consumed interrupt key from leaking as typed input on
     * platforms that deliver keyTyped without modifier state.
     */
    private char pendingTypedChar = KeyEvent.CHAR_UNDEFINED;

    private final Object lockObj = new Object();

    private final PollGate peekPollGate = new PollGate();
    private final PollGate nextPollGate = new PollGate();
    private long inputSequence;

    public KeyboardQueue() {
        this(() -> {
        });
    }

    public KeyboardQueue(Runnable inputSignal) {
        this(inputSignal, keycode -> false);
    }

    public KeyboardQueue(Runnable inputSignal, IntPredicate interruptKey) {
        this.inputSignal = inputSignal == null ? () -> {
        } : inputSignal;
        this.interruptKey = interruptKey == null ? keycode -> false : interruptKey;
    }

    // region DodoTalk interface

    public int peek() {
        synchronized (lockObj) {
            Character next = fCharQueue.peekFirst();
            return next == null ? 0 : keycode(next);
        }
    }

    public int next() {
        synchronized (lockObj) {
            Character next = fCharQueue.pollFirst();
            return next == null ? Integer.MIN_VALUE : keycode(next);
        }
    }

    /**
     * Primitive 109 entry point. Like {@link #peek()}, but limits empty-queue
     * polls to {@link #POLL_RATE_HZ}. A key event wakes the wait immediately,
     * and a non-empty queue never waits.
     */
    public int pollPeek() {
        awaitPollSlot(peekPollGate);
        return peek();
    }

    /**
     * Primitive 108 entry point. Like {@link #next()}, but limits empty-queue
     * polls to {@link #POLL_RATE_HZ}. A key event wakes the wait immediately,
     * and a non-empty queue never waits.
     */
    public int pollNext() {
        awaitPollSlot(nextPollGate);
        return next();
    }

    public boolean hasNext() {
        synchronized (lockObj) {
            return !fCharQueue.isEmpty();
        }
    }

    public int size() {
        synchronized (lockObj) {
            return fCharQueue.size();
        }
    }

    public int modifierKeys() {
        return fModifierKeys;
    }

    public void clearModifiers() {
        boolean changed;
        synchronized (lockObj) {
            changed = fModifierKeys != 0;
            fModifierKeys = 0;
        }
        if (changed) {
            signalInput();
        }
    }

    // endregion

    // region KeyListener methods

    @Override
    public void keyPressed(KeyEvent event) {
        boolean changed = false;
        int modifiers = mapModifierKey(event);
        if (modifiers != fModifierKeys) {
            fModifierKeys = modifiers;
            changed = true;
        }

        char keyChar = mapSpecialKey(event);
        // Normal characters are queued from keyTyped; use keyPressed only for
        // the interrupt check so a plain character is not queued twice.
        char candidate = keyChar != KeyEvent.CHAR_UNDEFINED ? keyChar : event.getKeyChar();
        boolean consumed = isInterruptKey(event, candidate);
        if (consumed) {
            pendingTypedChar = candidate;
        } else if (keyChar != KeyEvent.CHAR_UNDEFINED) {
            if (addToQueue(keyChar)) {
                changed = true;
                pendingTypedChar = keyChar;
            }
        }
        if (changed || consumed) {
            signalInput();
        }
    }

    @Override
    public void keyReleased(KeyEvent event) {
        pendingTypedChar = KeyEvent.CHAR_UNDEFINED;
        int modifiers = mapModifierKey(event);
        if (modifiers != fModifierKeys) {
            fModifierKeys = modifiers;
            signalInput();
        }
    }

    @Override
    public void keyTyped(KeyEvent event) {
        char keyChar = event.getKeyChar();
        char expected = pendingTypedChar;
        pendingTypedChar = KeyEvent.CHAR_UNDEFINED;

        // Ignore the return key, mapSpecialKey() took care of it
        if (keyChar == '\n') {
            return;
        }

        if (keyChar == expected) {
            // keyPressed already handled this character (or consumed it as
            // the interrupt key); keyTyped is a duplicate.
            signalInput();
            return;
        }

        if (isInterruptKey(event, keyChar)) {
            signalInput();
            return;
        }

        if (addToQueue(keyChar)) {
            signalInput();
        }
    }

    // endregion

    // region Private methods

    /**
     * @return true when the key is the configured interrupt key and must not
     * be queued as typed input
     */
    private boolean isInterruptKey(KeyEvent event, char keyChar) {
        return keyChar != KeyEvent.CHAR_UNDEFINED
                && interruptKey.test(keycode(keyChar, event));
    }

    /**
     * Squeak keycode: low byte is the character, high nibble is
     * {@code <command><option><control><shift>}. The image compares this value
     * with Primitive 133's {@code setInterruptKey:} argument.
     */
    private static int keycode(char keyChar, KeyEvent event) {
        int modifiers = 0;
        if (event.isShiftDown()) {
            modifiers |= KEYCODE_SHIFT;
        }
        if (event.isControlDown()) {
            modifiers |= KEYCODE_CONTROL;
        }
        if (event.isAltDown() || event.isMetaDown()) {
            // DodoTalk maps Alt/Meta to the Squeak command key (and exposes it
            // as COMMAND_KEY through primMouseButtons for image compatibility).
            modifiers |= KEYCODE_COMMAND;
        }
        return (modifiers << 8) | (keyChar & 0xFF);
    }

    private boolean addToQueue(char keyChar) {
        synchronized (lockObj) {
            if (fCharQueue.size() < TYPEAHEAD_LIMIT) {
                fCharQueue.addLast(keyChar);
                return true;
            }
        }
        return false;
    }

    /**
     * Adds a bounded wait before an empty-queue keyboard poll when the image
     * is spinning. A key event increments {@link #inputSequence} and wakes the
     * wait, so latency is not tied to the 250Hz idle rate. Non-empty queues
     * always bypass the wait.
     */
    private void awaitPollSlot(PollGate gate) {
        synchronized (lockObj) {
            long now = System.nanoTime();
            long earliest = gate.lastPollNanos + POLL_INTERVAL_NANOS;
            long observed = gate.observedSequence;
            if (gate.initialized && now < earliest
                    && inputSequence == observed && fCharQueue.isEmpty()) {
                while (now < earliest && inputSequence == observed
                        && fCharQueue.isEmpty()) {
                    long remaining = earliest - now;
                    try {
                        lockObj.wait(remaining / 1_000_000L,
                                (int) (remaining % 1_000_000L));
                    } catch (InterruptedException e) {
                        // The JVM already cleared the interrupt status. Do not
                        // re-arm it: a persistent flag would make every later
                        // wait throw immediately and disable the throttle.
                        Thread.interrupted(); // defensive no-op
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

    /**
     * Records a keyboard event, wakes an idle keyboard poll, and notifies the
     * VM's input latch.
     */
    private void signalInput() {
        synchronized (lockObj) {
            inputSequence++;
            lockObj.notifyAll();
        }
        inputSignal.run();
    }

    /**
     * Per-primitive keyboard poll rate limiter. Fields are guarded by
     * {@link #lockObj}.
     */
    private static final class PollGate {
        private boolean initialized;
        private long lastPollNanos;
        private long observedSequence;
    }

    private static int mapModifierKey(KeyEvent event) {
        int modifiers = 0;
        if (event.isShiftDown()) {
            modifiers |= SHIFT_KEY;
        }
        if (event.isControlDown()) {
            modifiers |= CONTROL_KEY;
        }
        if (event.isAltDown() || event.isMetaDown()) {
            modifiers |= COMMAND_KEY;
        }

        return modifiers;
    }

    private static char mapSpecialKey(KeyEvent evt) {
        // Modifier keys do not produce typed characters. Without this guard,
        // pressing Alt with a Meta/Alt modifier active mapped the modifier's
        // virtual key code to a control character and queued it as type-ahead.
        switch (evt.getKeyCode()) {
            case KeyEvent.VK_SHIFT:
            case KeyEvent.VK_CONTROL:
            case KeyEvent.VK_ALT:
            case KeyEvent.VK_META:
            case KeyEvent.VK_ALT_GRAPH:
                return KeyEvent.CHAR_UNDEFINED;
            default:
                break;
        }

        int specialKeyIndex = 0;
        while (specialKeyIndex < JAVA_KEYS.length && JAVA_KEYS[specialKeyIndex] != evt.getKeyCode())
            specialKeyIndex++;
        if (specialKeyIndex < JAVA_KEYS.length) {
            return SQUEAK_KEYS[specialKeyIndex];
        }

        if (evt.isAltDown() || evt.isMetaDown()) {
            return Character.toLowerCase((char) evt.getKeyCode());
        }

        return KeyEvent.CHAR_UNDEFINED;
    }

    private static int keycode(Character c) {
        return c & 255;
    }

    // endregion
}
