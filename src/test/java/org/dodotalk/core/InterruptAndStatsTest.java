package org.dodotalk.core;

import org.dodotalk.TestImages;
import org.dodotalk.input.KeyboardQueue;
import org.dodotalk.uilts.RuntimeStats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the cmd-. interrupt path and primitive diagnostics
 * (plan tasks 1.17 and 1.18).
 */
class InterruptAndStatsTest {

    private static final JPanel SOURCE = new JPanel();

    private static SqueakVM newVM(RuntimeStats stats) throws Exception {
        return new SqueakVM(new SqueakImage(TestImages.miniImage()), stats);
    }

    private static KeyEvent keyEvent(int id, int modifiers, int keyCode, char keyChar) {
        return new KeyEvent(SOURCE, id, System.currentTimeMillis(), modifiers, keyCode, keyChar);
    }

    @Test
    @DisplayName("the configured interrupt key raises the pending flag")
    void interruptKeyRaisesPendingFlag() throws Exception {
        SqueakVM vm = newVM(new RuntimeStats(false));

        assertFalse(vm.handleKeycode(110), "a normal key must not become an interrupt");
        assertFalse(vm.hasPendingInterrupt());

        assertTrue(vm.handleKeycode(SqueakVM.DEFAULT_INTERRUPT_KEYCODE),
                "cmd-. must be consumed by the interrupt handler");
        assertTrue(vm.hasPendingInterrupt());
    }

    @Test
    @DisplayName("the keyboard driver raises the interrupt flag without queueing the key")
    void keyboardDriverWiresInterruptToVM() throws Exception {
        SqueakVM vm = newVM(new RuntimeStats(false));
        KeyboardQueue queue = new KeyboardQueue(vm::signalInputEvent, vm::handleKeycode);

        queue.keyPressed(keyEvent(KeyEvent.KEY_PRESSED, InputEvent.ALT_DOWN_MASK,
                KeyEvent.VK_PERIOD, '.'));

        assertTrue(vm.hasPendingInterrupt(), "Alt-. must reach the VM interrupt flag");
        assertFalse(queue.hasNext(), "the interrupt key must not become typed input");
    }

    @Test
    @DisplayName("the interpreter consumes the pending interrupt at its next check")
    void checkForInterruptsConsumesPendingFlag() throws Exception {
        SqueakVM vm = newVM(new RuntimeStats(false));

        assertTrue(vm.handleKeycode(SqueakVM.DEFAULT_INTERRUPT_KEYCODE));
        assertTrue(vm.hasPendingInterrupt());

        vm.checkForInterrupts();
        assertFalse(vm.hasPendingInterrupt(), "the interrupt flag must be cleared after handling");
    }

    @Test
    @DisplayName("primitive 133 changes the interrupt key and leaves the receiver")
    void primitive133SetsInterruptKey() throws Exception {
        SqueakVM vm = newVM(new RuntimeStats(false));

        vm.push(SqueakVM.nilObj);
        vm.push(InterpreterHelper.smallFromInt(110));
        assertTrue(SqueakVM.primHandler.doPrimitive(133, 1));

        assertEquals(110, vm.getInterruptKeycode());
        assertSame(SqueakVM.nilObj, vm.top(), "the receiver must remain on the stack");
        assertFalse(vm.handleKeycode(SqueakVM.DEFAULT_INTERRUPT_KEYCODE));
        assertTrue(vm.handleKeycode(110));
    }

    @Test
    @DisplayName("primitive 133 fails without changing the key when the argument is not an integer")
    void primitive133RejectsNonInteger() throws Exception {
        SqueakVM vm = newVM(new RuntimeStats(false));

        vm.push(SqueakVM.nilObj);
        vm.push(SqueakVM.nilObj);
        assertFalse(SqueakVM.primHandler.doPrimitive(133, 1));

        assertEquals(SqueakVM.DEFAULT_INTERRUPT_KEYCODE, vm.getInterruptKeycode());
    }

    @Test
    @DisplayName("primitive diagnostics distinguish missing, failed and intentional primitives")
    void primitiveDiagnosticsAreClassified() throws Exception {
        RuntimeStats stats = new RuntimeStats(true);
        SqueakVM vm = newVM(stats);
        SqueakPrimitiveHandler handler = SqueakVM.primHandler;

        assertFalse(handler.doPrimitive(82, 0));   // valueWithArgs: no branch
        assertFalse(handler.doPrimitive(104, 0));  // drawLoop: no branch
        assertFalse(handler.doPrimitive(120, 0));  // documented primitiveFail
        assertFalse(handler.doPrimitive(19, 0));   // guard primitive

        // Primitive 133 with a non-integer argument is an implementation
        // failure, not a missing primitive.
        vm.push(SqueakVM.nilObj);
        vm.push(SqueakVM.nilObj);
        assertFalse(handler.doPrimitive(133, 1));

        assertEquals(5, stats.getPrimitiveCallsTotal());
        assertEquals(5, stats.getPrimitiveFailuresTotal());
        assertEquals(2, stats.getUnimplementedTotal());
        assertEquals(2, stats.getIntentionalTotal());
        assertEquals(1, stats.getFailedTotal());
        assertEquals(0, stats.getExceptionTotal());
        assertEquals(1, stats.getPrimitiveFailureCount(82));
        assertEquals(1, stats.getPrimitiveFailureCount(104));
        assertEquals(1, stats.getPrimitiveFailureCount(133));

        String summary = stats.summary();
        assertTrue(summary.contains("#82"), summary);
        assertTrue(summary.contains("unimplemented=2"), summary);
        assertTrue(summary.contains("failed=1"), summary);
    }
}
