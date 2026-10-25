package org.dodotalk.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Primitive 234 regression tests. The run-coded format is described in
 * Bitmap>>decompress:fromByteArray:at: (Squeak 2.2 sources).
 */
class BitmapDecompressTest {

    @Test
    @DisplayName("code 1 repeats one byte in every byte of the word")
    void repeatsOneByte() {
        int[] words = new int[2];
        // N=2 words, code=1, data=0xAA: anInt = (2 << 2) | 1
        assertTrue(SqueakPrimitiveHandler.decompressBitmap(
                new byte[]{9, (byte) 0xAA}, 1, words));
        assertArrayEquals(new int[]{0xAAAAAAAA, 0xAAAAAAAA}, words);
    }

    @Test
    @DisplayName("code 2 repeats a four-byte word")
    void repeatsWord() {
        int[] words = new int[2];
        // N=2 words, code=2, data=0x11223344: anInt = (2 << 2) | 2
        assertTrue(SqueakPrimitiveHandler.decompressBitmap(
                new byte[]{10, 0x11, 0x22, 0x33, 0x44}, 1, words));
        assertArrayEquals(new int[]{0x11223344, 0x11223344}, words);
    }

    @Test
    @DisplayName("code 3 copies literal words")
    void copiesLiteralWords() {
        int[] words = new int[2];
        // N=2 words, code=3: anInt = (2 << 2) | 3
        assertTrue(SqueakPrimitiveHandler.decompressBitmap(
                new byte[]{11, 0x12, 0x34, 0x56, 0x78, (byte) 0x9A,
                        (byte) 0xBC, (byte) 0xDE, (byte) 0xF0}, 1, words));
        assertArrayEquals(new int[]{0x12345678, 0x9ABCDEF0}, words);
    }

    @Test
    @DisplayName("code 0 is a no-op like the 2.2 C VM")
    void ignoresZeroRun() {
        int[] words = {0x11111111, 0x22222222};
        // code 0 (anInt = 4, no-op), then repeat 0xAA once (anInt = 5)
        assertTrue(SqueakPrimitiveHandler.decompressBitmap(
                new byte[]{4, 5, (byte) 0xAA}, 1, words));
        assertArrayEquals(new int[]{0xAAAAAAAA, 0x22222222}, words);
    }

    @Test
    @DisplayName("a one-based index is honoured")
    void honoursIndexOfFirstRun() {
        int[] words = new int[1];
        assertTrue(SqueakPrimitiveHandler.decompressBitmap(
                new byte[]{0x55, 5, (byte) 0xAA}, 2, words));
        assertArrayEquals(new int[]{0xAAAAAAAA}, words);
    }

    @Test
    @DisplayName("bad input fails instead of writing past the bitmap")
    void rejectsBadInput() {
        assertFalse(SqueakPrimitiveHandler.decompressBitmap(null, 1, new int[1]));
        assertFalse(SqueakPrimitiveHandler.decompressBitmap(new byte[]{5}, 0, new int[1]));

        int[] words = new int[1];
        // N=2 words but the bitmap only has one word
        assertFalse(SqueakPrimitiveHandler.decompressBitmap(
                new byte[]{9, (byte) 0xAA}, 1, words));

        // truncated literal word
        assertFalse(SqueakPrimitiveHandler.decompressBitmap(
                new byte[]{7, 0x12}, 1, new int[1]));
    }
}
