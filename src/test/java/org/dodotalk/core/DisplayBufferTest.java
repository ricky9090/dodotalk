package org.dodotalk.core;

import org.dodotalk.TestImages;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Regression tests for the display copy and BitBlt bounds fixes
 * (rendering review items 3.1 and 3.3).
 */
class DisplayBufferTest {

    private static SqueakVM vm;

    @BeforeAll
    static void loadVM() throws Exception {
        vm = new SqueakVM(new SqueakImage(TestImages.miniImage()));
    }

    @Test
    @DisplayName("only the affected display words are copied")
    void copiesAffectedRegionOnly() {
        int[] source = new int[12];
        int[] destination = new int[12];
        for (int i = 0; i < source.length; i++) {
            source[i] = i + 1;
        }

        // 4 words per row, 8 bpp -> 16 pixels per row, 3 rows.
        SqueakPrimitiveHandler.copyBitmapIntToInt(source, destination,
                new Rectangle(2, 1, 5, 1), 4, 8);

        assertEquals(0, destination[0]);
        assertEquals(0, destination[3]);
        assertEquals(5, destination[4]);
        assertEquals(6, destination[5]);
        assertEquals(0, destination[6]);
        assertEquals(0, destination[8]);
    }

    @Test
    @DisplayName("the copied region is clipped to the bitmap")
    void clipsRegion() {
        int[] source = new int[8];
        int[] destination = new int[8];
        for (int i = 0; i < source.length; i++) {
            source[i] = i + 1;
        }

        // 16 bpp -> 2 pixels per word, 4 words per row, 2 rows.
        SqueakPrimitiveHandler.copyBitmapIntToInt(source, destination,
                new Rectangle(6, 0, 100, 100), 4, 16);
        // x=6 -> word 3, x=8 (pixel width) is clipped: only word 3 is copied.
        assertEquals(0, destination[2]);
        assertEquals(4, destination[3]);
        assertEquals(0, destination[0]);

        SqueakPrimitiveHandler.copyBitmapIntToInt(source, destination,
                new Rectangle(-10, -10, 2, 2), 4, 16);
        assertEquals(0, destination[0]);
    }

    @Test
    @DisplayName("mismatched display buffers are rejected")
    void rejectsMismatchedBuffers() {
        assertThrows(IllegalArgumentException.class,
                () -> SqueakPrimitiveHandler.copyBitmapIntToInt(new int[4], new int[5],
                        new Rectangle(0, 0, 4, 1), 4, 8));
        assertThrows(IllegalArgumentException.class,
                () -> SqueakPrimitiveHandler.copyBitmapIntToInt(new int[4], new int[4],
                        new Rectangle(0, 0, 4, 1), 0, 8));
        assertThrows(IllegalArgumentException.class,
                () -> SqueakPrimitiveHandler.copyBitmapIntToInt(new int[4], new int[4],
                        new Rectangle(0, 0, 4, 1), 4, 3));
    }

    @Test
    @DisplayName("BitBlt source reads outside the bitmap return zero without an exception")
    void bitBltSourceBounds() {
        BitBlt bitBlt = new BitBlt(vm);
        bitBlt.source = vm.newFormCache();
        bitBlt.source.bits = new int[]{11, 22, 33};

        assertEquals(22, bitBlt.srcLongAt(1));
        assertEquals(0, bitBlt.srcLongAt(-1));
        assertEquals(0, bitBlt.srcLongAt(3));
    }

    @Test
    @DisplayName("BitBlt destination accesses are range checked")
    void bitBltDestinationBounds() {
        BitBlt bitBlt = new BitBlt(vm);
        bitBlt.dest = vm.newFormCache();
        bitBlt.dest.bits = new int[]{5};

        assertEquals(5, bitBlt.dstLongAt(0));
        bitBlt.dstLongAtput(0, 7);
        assertEquals(7, bitBlt.dest.bits[0]);
        assertThrows(IllegalStateException.class, () -> bitBlt.dstLongAt(1));
        assertThrows(IllegalStateException.class, () -> bitBlt.dstLongAtput(-1, 0));
    }
}
