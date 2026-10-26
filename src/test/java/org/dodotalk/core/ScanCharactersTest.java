package org.dodotalk.core;

import org.dodotalk.TestImages;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for Primitive 103 (CharacterScanner scanCharacters).
 * <p>
 * The tests build the CharacterScanner and its indexable tables directly, so
 * they exercise the scanner algorithm without needing a real font, paragraph,
 * or display. A VM is only used for the primitive stack and success flag.
 */
class ScanCharactersTest {

    private static SqueakVM vm;

    @BeforeAll
    static void loadVM() throws IOException {
        vm = new SqueakVM(new SqueakImage(TestImages.fullImage()));
    }

    @Test
    @DisplayName("scanCharacters stops at a stopConditions entry")
    void stopsAtStopCondition() {
        Scanner scanner = new Scanner("ABC");
        scanner.xTable(0, 3, 7, 12);
        scanner.stopArray.pointers['B'] = 99;

        Object stopCode = scanner.scan(1, 3, 20);

        assertEquals(99, stopCode);
        assertEquals(2, scanner.lastIndex(), "lastIndex must point at the stop character");
        assertEquals(3, scanner.destX(), "only the first glyph should have advanced destX");
    }

    @Test
    @DisplayName("scanCharacters stops before a glyph that would pass rightX")
    void stopsAtRightX() {
        Scanner scanner = new Scanner("ABC");
        scanner.xTable(0, 3, 6, 9);
        scanner.stopArray.pointers[257] = 88; // CrossedX

        Object stopCode = scanner.scan(1, 3, 5);

        assertEquals(88, stopCode);
        assertEquals(2, scanner.lastIndex());
        assertEquals(3, scanner.destX());
    }

    @Test
    @DisplayName("scanCharacters exhausts stopIndex and returns EndOfRun")
    void exhaustsCharacters() {
        Scanner scanner = new Scanner("ABC");
        scanner.xTable(0, 2, 4, 6);
        scanner.stopArray.pointers[256] = 77; // EndOfRun

        Object stopCode = scanner.scan(1, 3, 100);

        assertEquals(77, stopCode);
        assertEquals(3, scanner.lastIndex());
        assertEquals(6, scanner.destX());
    }

    @Test
    @DisplayName("invalid stopConditions fails the primitive instead of throwing")
    void invalidStopConditionsFail() {
        Scanner scanner = new Scanner("A");
        scanner.xTable(0, 2);
        scanner.stopArray.pointers = new Object[0];

        assertFalse(scanner.tryScan(1, 1, 10));
    }

    @Test
    @DisplayName("invalid xTable fails the primitive instead of throwing")
    void invalidXTableFails() {
        Scanner scanner = new Scanner("A");
        scanner.scanner.setPointer(16, SqueakVM.nilObj);

        assertFalse(scanner.tryScan(1, 1, 10));
    }

    @Test
    @DisplayName("non-boolean display flag fails the primitive")
    void invalidDisplayFlagFails() {
        Scanner scanner = new Scanner("A");
        scanner.xTable(0, 2);

        assertFalse(scanner.tryScanWithFlag(1, 1, 10, SqueakVM.nilObj));
    }

    @Test
    @DisplayName("byte characters above 127 are treated as unsigned")
    void highByteCharactersAreUnsigned() {
        Scanner scanner = new Scanner("A");
        scanner.string.bits = new byte[]{(byte) 200};
        scanner.xTable.setPointer(200, Integer.valueOf(0));
        scanner.xTable.setPointer(201, Integer.valueOf(4));

        Object stopCode = scanner.scan(1, 1, 10);

        assertSame(SqueakVM.nilObj, stopCode);
        assertEquals(4, scanner.destX());
    }

    @Test
    @DisplayName("inconsistent xTable fails during the scan loop")
    void scanTimeFailureFails() {
        Scanner scanner = new Scanner("A");
        scanner.xTable(5, 3); // negative glyph width

        assertFalse(scanner.tryScan(1, 1, 10));
    }

    private static final class Scanner {
        final SqueakObject scanner = object(17);
        final SqueakObject string = bytes("ABC");
        final SqueakObject xTable = array(258);
        final SqueakObject stopArray = array(258);

        Scanner(String text) {
            scanner.setPointer(4, Integer.valueOf(0));
            scanner.setPointer(15, SqueakVM.nilObj);
            scanner.setPointer(16, xTable);
            string.bits = text.getBytes(StandardCharsets.ISO_8859_1);
        }

        void xTable(int... positions) {
            // positions[0] is the start of 'A' (ASCII 65), positions[1] the
            // start of 'B', etc. xTable uses two adjacent entries per glyph.
            for (int i = 0; i < positions.length; i++) {
                xTable.setPointer(65 + i, Integer.valueOf(positions[i]));
            }
        }

        int lastIndex() {
            return (Integer) scanner.getPointer(15);
        }

        int destX() {
            return (Integer) scanner.getPointer(4);
        }

        Object scan(int start, int stop, int rightX) {
            return scanWithFlag(start, stop, rightX, SqueakVM.falseObj);
        }

        Object scanWithFlag(int start, int stop, int rightX, Object displayFlag) {
            int savedSp = vm.sp;
            vm.sp = 0;
            try {
                vm.push(scanner);
                vm.push(Integer.valueOf(start));
                vm.push(Integer.valueOf(stop));
                vm.push(string);
                vm.push(Integer.valueOf(rightX));
                vm.push(stopArray);
                vm.push(displayFlag);
                assertTrue(SqueakVM.primHandler.doPrimitive(103, 6),
                        "valid scan should succeed");
                return vm.top();
            } finally {
                vm.sp = savedSp;
            }
        }

        boolean tryScan(int start, int stop, int rightX) {
            return tryScanWithFlag(start, stop, rightX, SqueakVM.falseObj);
        }

        boolean tryScanWithFlag(int start, int stop, int rightX, Object displayFlag) {
            int savedSp = vm.sp;
            vm.sp = 0;
            try {
                vm.push(scanner);
                vm.push(Integer.valueOf(start));
                vm.push(Integer.valueOf(stop));
                vm.push(string);
                vm.push(Integer.valueOf(rightX));
                vm.push(stopArray);
                vm.push(displayFlag);
                return SqueakVM.primHandler.doPrimitive(103, 6);
            } finally {
                vm.sp = savedSp;
            }
        }
    }

    private static SqueakObject object(int pointerCount) {
        SqueakObject object = new SqueakObject(null, 0, 0, null);
        object.pointers = new Object[pointerCount];
        Arrays.fill(object.pointers, SqueakVM.nilObj);
        return object;
    }

    private static SqueakObject array(int length) {
        return object(length);
    }

    private static SqueakObject bytes(String text) {
        SqueakObject object = new SqueakObject(null, 8, 0, null);
        object.bits = text.getBytes(StandardCharsets.ISO_8859_1);
        return object;
    }
}
