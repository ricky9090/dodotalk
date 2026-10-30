package org.dodotalk.input;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import java.awt.Point;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the input wakeup latch and the mouse/keyboard event
 * queues. These tests do not open a window or depend on the real display.
 */
class InputSynchronizationTest {

    private static final JPanel SOURCE = new JPanel();

    // region InputEventLatch

    @Test
    @DisplayName("a signal before await is not lost")
    void signalBeforeAwaitIsObserved() {
        InputEventLatch latch = new InputEventLatch();
        assertFalse(latch.hasPending());

        latch.signal();
        assertTrue(latch.hasPending());
        assertTrue(latch.await(0));
        assertFalse(latch.hasPending());
    }

    @Test
    @DisplayName("await is bounded when no signal arrives")
    void awaitTimesOut() {
        InputEventLatch latch = new InputEventLatch();
        long start = System.nanoTime();

        assertFalse(latch.await(30));

        long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;
        assertTrue(elapsedMillis >= 20, "wait returned too early: " + elapsedMillis + "ms");
        assertTrue(elapsedMillis < 500, "wait was not bounded: " + elapsedMillis + "ms");
    }

    @Test
    @DisplayName("await wakes up when another thread signals")
    void awaitWakesOnSignalFromAnotherThread() throws Exception {
        InputEventLatch latch = new InputEventLatch();
        Thread notifier = new Thread(() -> {
            sleepSilently(20);
            latch.signal();
        });
        notifier.start();

        long start = System.nanoTime();
        assertTrue(latch.await(1000));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;

        notifier.join(1000);
        assertTrue(elapsedMillis < 500, "wakeup was too slow: " + elapsedMillis + "ms");
        assertFalse(latch.hasPending());
    }

    @Test
    @DisplayName("multiple signals may be coalesced into one wakeup")
    void repeatedSignalsAreCoalesced() {
        InputEventLatch latch = new InputEventLatch();
        latch.signal();
        latch.signal();

        assertTrue(latch.await(0));
        assertFalse(latch.hasPending());
    }

    @Test
    @DisplayName("an interrupted await does not disable later waits")
    void interruptedAwaitDoesNotDisableLaterWaits() throws Exception {
        InputEventLatch latch = new InputEventLatch();
        AtomicInteger firstResult = new AtomicInteger(-1);
        AtomicInteger secondResult = new AtomicInteger(-1);
        AtomicLong firstMillis = new AtomicLong(-1);
        AtomicLong secondMillis = new AtomicLong(-1);

        Thread waiter = new Thread(() -> {
            Thread.currentThread().interrupt();
            long start = System.nanoTime();
            firstResult.set(latch.await(30) ? 1 : 0);
            firstMillis.set((System.nanoTime() - start) / 1_000_000L);

            start = System.nanoTime();
            secondResult.set(latch.await(30) ? 1 : 0);
            secondMillis.set((System.nanoTime() - start) / 1_000_000L);
        });
        waiter.start();
        waiter.join(2000);

        assertFalse(waiter.isAlive(), "the interrupted waiter did not finish");
        assertEquals(0, firstResult.get(), "the first await should observe the interrupt");
        assertEquals(0, secondResult.get(), "the second await should time out");
        assertTrue(firstMillis.get() < 20,
                "the first await should return immediately: " + firstMillis.get() + "ms");
        assertTrue(secondMillis.get() >= 20,
                "the interrupt flag disabled the second wait: " + secondMillis.get() + "ms");
        assertFalse(waiter.isInterrupted(),
                "the latch must not leave the timer/VM thread interrupted");
    }

    // endregion

    // region InputNotifyThread

    @Test
    @DisplayName("the heartbeat thread signals repeatedly and stops cleanly")
    void notifyThreadSignalsAndStops() throws Exception {
        AtomicInteger signals = new AtomicInteger();
        InputNotifyThread thread = new InputNotifyThread(signals::incrementAndGet, 5);

        thread.start();
        long deadline = System.nanoTime() + 1_000_000_000L;
        while (signals.get() < 2 && System.nanoTime() < deadline) {
            sleepSilently(5);
        }
        thread.quit();
        thread.join(1000);

        assertFalse(thread.isAlive(), "input notify thread did not stop");
        assertTrue(signals.get() >= 2, "heartbeat did not signal: " + signals.get());
        assertFalse(thread.isRunning());
    }

    // endregion

    // region MouseStatus

    @Test
    @DisplayName("100 rapid clicks are queued without loss")
    void rapidClicksAreQueued() {
        MouseStatus status = new MouseStatus();

        for (int i = 0; i < 100; i++) {
            status.mousePressed(mouseEvent(MouseEvent.MOUSE_PRESSED, i, i, MouseEvent.BUTTON1, 0));
            status.mouseReleased(mouseEvent(MouseEvent.MOUSE_RELEASED, i, i, MouseEvent.BUTTON1, 0));
        }

        assertEquals(200, status.queueSize(), "press/release transitions were lost");
        assertEquals(0, status.getButtons(), "physical button state must be released");

        for (int i = 0; i < 100; i++) {
            Point pressPoint = status.peekMousePoint();
            assertEquals(i, pressPoint.x);
            assertEquals(i, pressPoint.y);
            assertEquals(MouseStatus.RED, status.consumeButtons());

            Point releasePoint = status.peekMousePoint();
            assertEquals(i, releasePoint.x);
            assertEquals(i, releasePoint.y);
            assertEquals(0, status.consumeButtons());
        }

        assertEquals(0, status.queueSize());
    }

    @Test
    @DisplayName("ctrl-click maps to yellow and does not leave a stuck button")
    void ctrlClickReleasesYellow() {
        MouseStatus status = new MouseStatus();

        status.mousePressed(mouseEvent(MouseEvent.MOUSE_PRESSED, 10, 20,
                MouseEvent.BUTTON1, InputEvent.CTRL_DOWN_MASK));
        status.mouseReleased(mouseEvent(MouseEvent.MOUSE_RELEASED, 10, 20,
                MouseEvent.BUTTON1, 0));

        assertEquals(MouseStatus.YELLOW, nextButtons(status));
        assertEquals(0, nextButtons(status));
        assertEquals(0, status.getButtons(), "ctrl-click left a button stuck");
    }

    @Test
    @DisplayName("the point and button primitives see the same transition")
    void pointAndButtonsArePaired() {
        MouseStatus status = new MouseStatus();
        status.mousePressed(mouseEvent(MouseEvent.MOUSE_PRESSED, 10, 20, MouseEvent.BUTTON1, 0));
        status.mouseReleased(mouseEvent(MouseEvent.MOUSE_RELEASED, 30, 40, MouseEvent.BUTTON1, 0));

        Point press = status.peekMousePoint();
        assertEquals(10, press.x);
        assertEquals(20, press.y);
        assertEquals(MouseStatus.RED, status.consumeButtons());

        Point release = status.peekMousePoint();
        assertEquals(30, release.x);
        assertEquals(40, release.y);
        assertEquals(0, status.consumeButtons());
    }

    @Test
    @DisplayName("mouse movement updates the point without queueing a button event")
    void movementDoesNotQueueEvents() {
        MouseStatus status = new MouseStatus();
        status.mouseMoved(mouseEvent(MouseEvent.MOUSE_MOVED, 7, 9, MouseEvent.NOBUTTON, 0));

        assertEquals(0, status.queueSize());
        assertEquals(new Point(7, 9), status.getPoint());
        assertEquals(0, status.consumeButtons());
    }

    @Test
    @DisplayName("unknown buttons are ignored instead of throwing")
    void unknownButtonIsIgnored() {
        MouseStatus status = new MouseStatus();
        status.mousePressed(mouseEvent(MouseEvent.MOUSE_PRESSED, 1, 2, MouseEvent.NOBUTTON, 0));

        assertEquals(0, status.queueSize());
        assertEquals(0, status.getButtons());
    }

    @Test
    @DisplayName("a release without a press does not create a transition")
    void releaseWithoutPressIsIgnored() {
        MouseStatus status = new MouseStatus();
        status.mouseReleased(mouseEvent(MouseEvent.MOUSE_RELEASED, 1, 2, MouseEvent.BUTTON1, 0));

        assertEquals(0, status.queueSize());
        assertEquals(0, status.getButtons());
    }

    @Test
    @DisplayName("focus loss releases held buttons and clears pending clicks")
    void focusLossReleasesButtons() {
        MouseStatus status = new MouseStatus();
        status.mousePressed(mouseEvent(MouseEvent.MOUSE_PRESSED, 4, 5, MouseEvent.BUTTON1, 0));

        status.releaseAllButtons();

        assertEquals(0, status.getButtons());
        assertEquals(0, nextButtons(status));
        assertEquals(0, status.queueSize());
    }

    @Test
    @DisplayName("mouse events signal the VM")
    void mouseEventsSignalInput() {
        AtomicInteger signals = new AtomicInteger();
        MouseStatus status = new MouseStatus(signals::incrementAndGet);

        status.mouseMoved(mouseEvent(MouseEvent.MOUSE_MOVED, 3, 4, MouseEvent.NOBUTTON, 0));
        status.mousePressed(mouseEvent(MouseEvent.MOUSE_PRESSED, 3, 4, MouseEvent.BUTTON1, 0));
        status.mouseReleased(mouseEvent(MouseEvent.MOUSE_RELEASED, 3, 4, MouseEvent.BUTTON1, 0));

        assertTrue(signals.get() >= 3, "mouse events did not wake the VM");
    }

    // endregion

    // region KeyboardQueue

    @Test
    @DisplayName("typed characters are returned in order")
    void keyboardPreservesOrder() {
        KeyboardQueue queue = new KeyboardQueue();

        for (int i = 0; i < 100; i++) {
            queue.keyTyped(typedKey((char) ('a' + (i % 26))));
        }

        assertEquals(100, queue.size());
        for (int i = 0; i < 100; i++) {
            assertEquals('a' + (i % 26), queue.next());
        }
        assertEquals(Integer.MIN_VALUE, queue.next());
        assertEquals(0, queue.peek());
    }

    @Test
    @DisplayName("special keys and modifiers are mapped")
    void keyboardMapsSpecialKeysAndModifiers() {
        KeyboardQueue queue = new KeyboardQueue();

        queue.keyPressed(keyEvent(KeyEvent.KEY_PRESSED, InputEvent.SHIFT_DOWN_MASK,
                KeyEvent.VK_SHIFT, KeyEvent.CHAR_UNDEFINED));
        assertEquals(8, queue.modifierKeys());

        queue.keyReleased(keyEvent(KeyEvent.KEY_RELEASED, 0,
                KeyEvent.VK_SHIFT, KeyEvent.CHAR_UNDEFINED));
        assertEquals(0, queue.modifierKeys());

        queue.keyPressed(keyEvent(KeyEvent.KEY_PRESSED, 0,
                KeyEvent.VK_LEFT, KeyEvent.CHAR_UNDEFINED));
        assertTrue(queue.hasNext());
        assertEquals(28, queue.next());
    }

    @Test
    @DisplayName("keyboard input signals the VM and modifiers can be cleared on focus loss")
    void keyboardSignalsAndClearsModifiers() {
        AtomicInteger signals = new AtomicInteger();
        KeyboardQueue queue = new KeyboardQueue(signals::incrementAndGet);

        queue.keyPressed(keyEvent(KeyEvent.KEY_PRESSED, InputEvent.SHIFT_DOWN_MASK,
                KeyEvent.VK_SHIFT, KeyEvent.CHAR_UNDEFINED));
        queue.keyTyped(typedKey('x'));

        assertTrue(signals.get() >= 2, "keyboard events did not wake the VM");
        assertEquals(8, queue.modifierKeys());
        queue.clearModifiers();
        assertEquals(0, queue.modifierKeys());
    }

    @Test
    @DisplayName("modifier keys do not enter the type-ahead queue")
    void modifierKeysAreNotQueued() {
        KeyboardQueue queue = new KeyboardQueue();

        queue.keyPressed(keyEvent(KeyEvent.KEY_PRESSED, InputEvent.ALT_DOWN_MASK,
                KeyEvent.VK_ALT, KeyEvent.CHAR_UNDEFINED));
        queue.keyPressed(keyEvent(KeyEvent.KEY_PRESSED, InputEvent.CTRL_DOWN_MASK,
                KeyEvent.VK_CONTROL, KeyEvent.CHAR_UNDEFINED));
        queue.keyPressed(keyEvent(KeyEvent.KEY_PRESSED, InputEvent.SHIFT_DOWN_MASK,
                KeyEvent.VK_SHIFT, KeyEvent.CHAR_UNDEFINED));
        queue.keyPressed(keyEvent(KeyEvent.KEY_PRESSED, InputEvent.META_DOWN_MASK,
                KeyEvent.VK_META, KeyEvent.CHAR_UNDEFINED));

        assertFalse(queue.hasNext(), "modifier keys were queued as typed characters");
    }

    @Test
    @DisplayName("cmd-. is consumed as an interrupt and not queued")
    void commandPeriodIsInterrupt() {
        AtomicInteger observedKeycode = new AtomicInteger(-1);
        AtomicInteger signals = new AtomicInteger();
        KeyboardQueue queue = new KeyboardQueue(signals::incrementAndGet, keycode -> {
            observedKeycode.set(keycode);
            return keycode == 2094; // cmd-.
        });

        queue.keyPressed(keyEvent(KeyEvent.KEY_PRESSED, InputEvent.ALT_DOWN_MASK,
                KeyEvent.VK_PERIOD, '.'));
        assertEquals(2094, observedKeycode.get(), "Alt-. must use the Squeak cmd-. keycode");
        assertFalse(queue.hasNext(), "the interrupt key must not enter the type-ahead queue");
        assertTrue(signals.get() >= 1, "the interrupt key must wake the VM");

        queue.keyTyped(keyEvent(KeyEvent.KEY_TYPED, InputEvent.ALT_DOWN_MASK,
                KeyEvent.VK_UNDEFINED, '.'));
        assertFalse(queue.hasNext(), "keyTyped must not reintroduce the interrupt key");
    }

    @Test
    @DisplayName("an image-selected plain interrupt key is intercepted")
    void configuredPlainKeyIsInterrupt() {
        KeyboardQueue queue = new KeyboardQueue(() -> {
        }, keycode -> keycode == 'x');

        queue.keyPressed(keyEvent(KeyEvent.KEY_PRESSED, 0, KeyEvent.VK_X, 'x'));
        queue.keyTyped(typedKey('x'));
        assertFalse(queue.hasNext(), "plain interrupt keys must be consumed too");

        queue.keyTyped(typedKey('y'));
        assertTrue(queue.hasNext(), "non-interrupt keys must still be queued");
        assertEquals('y', queue.next());
    }

    // endregion

    private static int nextButtons(MouseStatus status) {
        status.peekMousePoint();
        return status.consumeButtons();
    }

    private static MouseEvent mouseEvent(int id, int x, int y, int button, int modifiers) {
        return new MouseEvent(SOURCE, id, System.currentTimeMillis(), modifiers,
                x, y, 1, false, button);
    }

    private static KeyEvent keyEvent(int id, int modifiers, int keyCode, char keyChar) {
        return new KeyEvent(SOURCE, id, System.currentTimeMillis(), modifiers, keyCode, keyChar);
    }

    private static KeyEvent typedKey(char keyChar) {
        return keyEvent(KeyEvent.KEY_TYPED, 0, KeyEvent.VK_UNDEFINED, keyChar);
    }

    private static void sleepSilently(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
