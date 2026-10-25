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
        fModifierKeys = 0;
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
            inputSignal.run();
        }
    }

    @Override
    public void keyReleased(KeyEvent event) {
        pendingTypedChar = KeyEvent.CHAR_UNDEFINED;
        int modifiers = mapModifierKey(event);
        if (modifiers != fModifierKeys) {
            fModifierKeys = modifiers;
            inputSignal.run();
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
            inputSignal.run();
            return;
        }

        if (isInterruptKey(event, keyChar)) {
            inputSignal.run();
            return;
        }

        if (addToQueue(keyChar)) {
            inputSignal.run();
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
