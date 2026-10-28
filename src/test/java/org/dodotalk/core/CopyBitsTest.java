package org.dodotalk.core;

import org.dodotalk.TestImages;
import org.dodotalk.uilts.RuntimeStats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for Primitive 96 {@code BitBlt>>copyBits}.
 * <p>
 * Squeak 2.2's Smalltalk fallback explicitly unhibernates compressed source,
 * destination and halftone Forms and retries the primitive. These tests pin
 * down that the Java side fails those cases cleanly, with a readable reason and
 * without disturbing the stack, while an ordinary uncompressed copy succeeds.
 */
class CopyBitsTest {

    private SqueakVM vm;
    private SqueakPrimitiveHandler handler;

    @BeforeEach
    void loadVM() throws Exception {
        vm = new SqueakVM(new SqueakImage(TestImages.fullImage()));
        handler = SqueakVM.primHandler;
    }

    @Test
    @DisplayName("an uncompressed source form is copied into the destination form")
    void copiesWordsIntoDestination() {
        SqueakObject destination = form(words(0, 0), 2, 1, 32);
        SqueakObject source = form(words(0x11223344, 0x55667788), 2, 1, 32);
        SqueakObject bitBlt = bitBlt(destination, source, null, 3, 2, 1);

        assertTrue(runCopyBits(bitBlt), "ordinary copy should succeed");

        int[] destinationBits = (int[]) ((SqueakObject) destination.getPointer(0)).bits;
        assertEquals(0x11223344, destinationBits[0]);
        assertEquals(0x55667788, destinationBits[1]);
    }

    @Test
    @DisplayName("a compressed source form fails cleanly for the image to unhibernate")
    void compressedSourceFailsCleanly() {
        SqueakObject destination = form(words(0, 0), 2, 1, 32);
        SqueakObject source = form(bytes(1, 2, 3), 2, 1, 32);
        SqueakObject bitBlt = bitBlt(destination, source, null, 3, 2, 1);

        assertCopyBitsFails(bitBlt, "compressed bits");
    }

    @Test
    @DisplayName("a compressed destination form fails cleanly")
    void compressedDestinationFailsCleanly() {
        SqueakObject destination = form(bytes(1, 2, 3), 2, 1, 32);
        SqueakObject source = form(words(0x11223344), 1, 1, 32);
        SqueakObject bitBlt = bitBlt(destination, source, null, 3, 1, 1);

        assertCopyBitsFails(bitBlt, "destination form cannot be loaded (possibly compressed bits)");
    }

    @Test
    @DisplayName("a compressed halftone form fails instead of throwing ClassCastException")
    void compressedHalftoneFailsCleanly() {
        SqueakObject destination = form(words(0), 1, 1, 32);
        SqueakObject source = form(words(0x11223344), 1, 1, 32);

        // Old-style 32xN monochrome halftone layout: pointer 0 is the bits
        // object and pointer 2 is the halftone height.
        SqueakObject halftone = pointers(4);
        halftone.setPointer(0, bytes(1, 2, 3, 4));
        halftone.setPointer(2, Integer.valueOf(1));

        SqueakObject bitBlt = bitBlt(destination, source, halftone, 3, 1, 1);

        assertCopyBitsFails(bitBlt, "halftone form cannot be loaded");
    }

    private void assertCopyBitsFails(SqueakObject bitBlt, String reasonFragment) {
        int savedSp = vm.sp;
        vm.sp = 0;
        vm.push(bitBlt);
        try {
            assertFalse(handler.doPrimitive(96, 0),
                    "the primitive must fail so the image can run its fallback");
            assertSame(bitBlt, vm.top(), "a failed primitive must leave the receiver on the stack");
            assertEquals(RuntimeStats.PrimitiveFailure.FAILED,
                    handler.getLastFailureKind(), "compressed forms are not intentional primitiveFail");
            assertTrue(handler.lastBitBltFailure().contains(reasonFragment),
                    "unexpected failure reason: " + handler.lastBitBltFailure());
        } finally {
            vm.sp = savedSp;
        }
    }

    private boolean runCopyBits(SqueakObject bitBlt) {
        int savedSp = vm.sp;
        vm.sp = 0;
        vm.push(bitBlt);
        try {
            return handler.doPrimitive(96, 0);
        } finally {
            vm.sp = savedSp;
        }
    }

    private static SqueakObject bitBlt(SqueakObject dest, SqueakObject source,
                                       SqueakObject halftone, int rule,
                                       int width, int height) {
        SqueakObject bb = pointers(15);
        bb.setPointer(0, dest);
        bb.setPointer(1, source);
        bb.setPointer(2, halftone == null ? SqueakVM.nilObj : halftone);
        bb.setPointer(3, Integer.valueOf(rule));
        bb.setPointer(4, Integer.valueOf(0));   // destX
        bb.setPointer(5, Integer.valueOf(0));   // destY
        bb.setPointer(6, Integer.valueOf(width));
        bb.setPointer(7, Integer.valueOf(height));
        bb.setPointer(8, Integer.valueOf(0));   // sourceX
        bb.setPointer(9, Integer.valueOf(0));   // sourceY
        bb.setPointer(10, Integer.valueOf(0));  // clipX
        bb.setPointer(11, Integer.valueOf(0));  // clipY
        bb.setPointer(12, Integer.valueOf(width));
        bb.setPointer(13, Integer.valueOf(height));
        bb.setPointer(14, SqueakVM.nilObj);     // colorMap
        return bb;
    }

    private static SqueakObject form(SqueakObject bits, int width, int height, int depth) {
        SqueakObject form = pointers(4);
        form.setPointer(0, bits);
        form.setPointer(1, Integer.valueOf(width));
        form.setPointer(2, Integer.valueOf(height));
        form.setPointer(3, Integer.valueOf(depth));
        return form;
    }

    private static SqueakObject pointers(int count) {
        SqueakObject object = new SqueakObject(null, 0, 0, null);
        object.pointers = new Object[count];
        Arrays.fill(object.pointers, SqueakVM.nilObj);
        return object;
    }

    private static SqueakObject words(int... values) {
        SqueakObject object = new SqueakObject(null, 6, 0, null);
        object.bits = values;
        return object;
    }

    private static SqueakObject bytes(int... values) {
        byte[] data = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            data[i] = (byte) values[i];
        }
        SqueakObject object = new SqueakObject(null, 8, 0, null);
        object.bits = data;
        return object;
    }
}
