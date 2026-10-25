package org.dodotalk.core;

import org.dodotalk.TestImages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the display-control primitives and VM/object-memory
 * binding added in Sprint 2. These tests run headlessly; the actual Swing
 * dirty-rectangle behavior is covered separately by {@code DirtyRegionTest}.
 */
class DisplayPrimitiveTest {

    private SqueakVM vm;
    private SqueakPrimitiveHandler handler;

    @BeforeEach
    void loadVM() throws Exception {
        vm = new SqueakVM(new SqueakImage(TestImages.miniImage()));
        handler = SqueakVM.primHandler;
    }

    @Test
    @DisplayName("primitive 126 stores the defer flag and leaves the receiver")
    void deferDisplayUpdates() {
        vm.push(SqueakVM.nilObj);
        vm.push(SqueakVM.trueObj);
        assertTrue(handler.doPrimitive(126, 1));
        assertTrue(vm.isDeferDisplayUpdates());
        assertSame(SqueakVM.nilObj, vm.top());

        vm.push(SqueakVM.nilObj);
        vm.push(SqueakVM.falseObj);
        assertTrue(handler.doPrimitive(126, 1));
        assertFalse(vm.isDeferDisplayUpdates());
    }

    @Test
    @DisplayName("primitive 231 succeeds without a bound display")
    void forceDisplayUpdate() {
        vm.push(SqueakVM.nilObj);
        assertTrue(handler.doPrimitive(231, 0));
    }

    @Test
    @DisplayName("primitive 127 fails cleanly when no display is bound")
    void showDisplayRectWithoutDisplay() {
        vm.push(SqueakVM.nilObj);
        vm.push(InterpreterHelper.smallFromInt(0));   // left
        vm.push(InterpreterHelper.smallFromInt(0));   // top
        vm.push(InterpreterHelper.smallFromInt(10));  // right
        vm.push(InterpreterHelper.smallFromInt(10));  // bottom
        assertFalse(handler.doPrimitive(127, 4));
    }

    @Test
    @DisplayName("primitive 233 no longer reports fake success")
    void fullScreenFailsExplicitly() {
        vm.push(SqueakVM.nilObj);
        vm.push(SqueakVM.trueObj);
        assertFalse(handler.doPrimitive(233, 1));
        assertSame(SqueakVM.trueObj, vm.top(), "the argument must remain on failure");
    }

    @Test
    @DisplayName("primitive 140 beep succeeds even without an audio device")
    void beep() {
        vm.push(SqueakVM.nilObj);
        assertTrue(handler.doPrimitive(140, 0));
    }

    @Test
    @DisplayName("ObjectMemory is bound to the constructed VM")
    void objectMemoryIsBoundToVM() {
        assertSame(vm, SqueakVM.objectMemory.getVM());
    }
}
