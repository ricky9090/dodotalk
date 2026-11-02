package org.dodotalk.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the synthetic Squeak 2.2 fixture.
 * <p>
 * The fixture is generated at test time; no image is stored in the repository.
 * It exercises the production image reader, object installation, initial
 * context setup, bytecode dispatch, primitive arithmetic, a message send, and
 * the writer round-trip.
 */
class SyntheticImageFixtureTest {

    @TempDir
    Path tempDir;

    private SqueakImage load(byte[] bytes) throws IOException {
        return new SqueakImage(new ByteArrayInputStream(bytes),
                tempDir.resolve("synthetic.image").toFile());
    }

    private SqueakVM newVM(byte[] bytes) throws IOException {
        return new SqueakVM(load(bytes));
    }

    private static RuntimeException runToTrap(SqueakVM vm) {
        RuntimeException failure = assertThrows(RuntimeException.class, vm::run);
        assertTrue(failure.getMessage().contains("Bad bytecode"), failure.getMessage());
        return failure;
    }

    private static void assertAtHaltTrap(SqueakVM vm) {
        assertEquals(0, vm.pc, "the trap must be the first bytecode of methodHalt");
        assertEquals(126, vm.methodBytes[0] & 0xFF, "methodHalt must contain bytecode 126");
    }

    @Test
    @DisplayName("a synthetic image loads and exposes distinct special objects")
    void syntheticImageLoads() throws Exception {
        SqueakImage image = load(SyntheticImageBuilder.build(
                SyntheticImageBuilder.Variant.ARITH));
        SqueakVM vm = new SqueakVM(image);

        assertNotNull(vm);
        assertNotNull(SqueakVM.nilObj);
        assertNotNull(SqueakVM.falseObj);
        assertNotNull(SqueakVM.trueObj);
        assertNotSame(SqueakVM.nilObj, SqueakVM.falseObj);
        assertNotSame(SqueakVM.nilObj, SqueakVM.trueObj);
        assertNotSame(SqueakVM.falseObj, SqueakVM.trueObj);
        assertSame(SqueakVM.nilObj, image.specialObjectsArray.getPointer(Squeak.splOb_NilObject));
        assertSame(SqueakVM.falseObj, image.specialObjectsArray.getPointer(Squeak.splOb_FalseObject));
        assertSame(SqueakVM.trueObj, image.specialObjectsArray.getPointer(Squeak.splOb_TrueObject));
    }

    @Test
    @DisplayName("the initial context has pc -1 and sp 5")
    void initialContextIsSetUp() throws Exception {
        SqueakVM vm = newVM(SyntheticImageBuilder.build(SyntheticImageBuilder.Variant.ARITH));

        assertEquals(-1, vm.pc);
        assertEquals(5, vm.sp);
        assertNotNull(vm.method);
        assertEquals(0, vm.method.methodNumLits());
    }

    @Test
    @DisplayName("arithmetic fixture computes 2 through bytecode dispatch and primAdd")
    void arithmeticFixtureComputes() throws Exception {
        SqueakVM vm = newVM(SyntheticImageBuilder.build(SyntheticImageBuilder.Variant.ARITH));

        runToTrap(vm);

        assertAtHaltTrap(vm);
        assertEquals(2, ((Integer) vm.top()).intValue());
        assertEquals(6, vm.sp);
    }

    @Test
    @DisplayName("send fixture resolves a method through the dictionary and returns 2")
    void sendFixtureResolvesMethod() throws Exception {
        SqueakVM vm = newVM(SyntheticImageBuilder.build(SyntheticImageBuilder.Variant.SEND));

        runToTrap(vm);

        assertAtHaltTrap(vm);
        assertEquals(2, ((Integer) vm.top()).intValue());
        assertEquals(6, vm.sp);
    }

    @Test
    @DisplayName("send fixture populates the method cache")
    void sendFixturePopulatesMethodCache() throws Exception {
        SqueakVM vm = newVM(SyntheticImageBuilder.build(SyntheticImageBuilder.Variant.SEND));
        SqueakObject methodTop = vm.method;
        SqueakObject selector = methodTop.methodGetSelector(0);
        SqueakObject receiver = (SqueakObject) methodTop.methodGetLiteral(1);
        SqueakObject lookupClass = vm.getClass(receiver);

        assertNull(vm.findMethodCacheEntry(selector, lookupClass).method);
        runToTrap(vm);
        assertNotNull(vm.findMethodCacheEntry(selector, lookupClass).method);
    }

    @Test
    @DisplayName("the halt trap is the expected Bad bytecode at methodHalt[0]")
    void haltTrapThrows() throws Exception {
        SqueakVM vm = newVM(SyntheticImageBuilder.build(SyntheticImageBuilder.Variant.ARITH));

        RuntimeException failure = runToTrap(vm);

        assertTrue(failure.getMessage().contains("Bad bytecode"));
        assertAtHaltTrap(vm);
        assertEquals(2, ((Integer) vm.top()).intValue());
        assertEquals(6, vm.sp);
    }

    @Test
    @DisplayName("the writer round-trip preserves the fixture result")
    void writerRoundTripKeepsResult() throws Exception {
        for (SyntheticImageBuilder.Variant variant :
                new SyntheticImageBuilder.Variant[]{
                        SyntheticImageBuilder.Variant.ARITH,
                        SyntheticImageBuilder.Variant.SEND}) {
            SqueakImage image = load(SyntheticImageBuilder.build(variant));
            SqueakVM vm = new SqueakVM(image);
            assertEquals(-1, vm.pc);
            assertEquals(5, vm.sp);

            // Save before running: the active context is still contextTop.
            File target = tempDir.resolve(variant.name().toLowerCase() + ".image").toFile();
            image.save(target);

            SqueakImage reloaded = new SqueakImage(target);
            SqueakVM reloadedVM = new SqueakVM(reloaded);
            assertEquals(-1, reloadedVM.pc);
            assertEquals(5, reloadedVM.sp);
            runToTrap(reloadedVM);
            assertEquals(2, ((Integer) reloadedVM.top()).intValue());
            assertEquals(6, reloadedVM.sp);
            assertAtHaltTrap(reloadedVM);
        }
    }

    @Test
    @DisplayName("the generator is deterministic")
    void syntheticImageIsDeterministic() {
        assertArrayEquals(SyntheticImageBuilder.build(SyntheticImageBuilder.Variant.ARITH),
                SyntheticImageBuilder.build(SyntheticImageBuilder.Variant.ARITH));
        assertArrayEquals(SyntheticImageBuilder.build(SyntheticImageBuilder.Variant.SEND),
                SyntheticImageBuilder.build(SyntheticImageBuilder.Variant.SEND));
        assertArrayEquals(SyntheticImageBuilder.buildBench(1_000),
                SyntheticImageBuilder.buildBench(1_000));
    }

    @Test
    @DisplayName("bench fixture is deterministic and keeps the stack balanced")
    void benchFixtureKeepsStackBalanced() throws Exception {
        int repetitions = 1_000;
        assertArrayEquals(SyntheticImageBuilder.buildBench(repetitions),
                SyntheticImageBuilder.buildBench(repetitions));

        SqueakVM vm = newVM(SyntheticImageBuilder.buildBench(repetitions));
        int steps = runCountingBytecodes(vm);

        // 124 returns from methodTop and 126 is the halt trap. The straight-line
        // sequence itself executes 4 * repetitions bytecodes, then 124 + 126.
        assertEquals((repetitions * 4) + 2, steps);
        assertEquals(6, vm.sp);
        assertSame(SqueakVM.nilObj, vm.top());
        assertAtHaltTrap(vm);
    }

    @Test
    @DisplayName("bench repetitions must be positive and bounded")
    void benchRejectsInvalidRepetitions() {
        assertThrows(IllegalArgumentException.class, () -> SyntheticImageBuilder.buildBench(0));
        assertThrows(IllegalArgumentException.class, () -> SyntheticImageBuilder.buildBench(-1));
        assertThrows(IllegalArgumentException.class,
                () -> SyntheticImageBuilder.buildBench(
                        SyntheticImageBuilder.MAX_BENCH_REPETITIONS + 1));
    }

    @Test
    @DisplayName("a bad image version is rejected")
    void badVersionIsRejected() {
        byte[] data = SyntheticImageBuilder.build(SyntheticImageBuilder.Variant.ARITH);
        putInt(data, 0, 6503);
        assertFormatError(data, "bad image version");
    }

    @Test
    @DisplayName("a bad image header size is rejected")
    void badHeaderSizeIsRejected() {
        byte[] data = SyntheticImageBuilder.build(SyntheticImageBuilder.Variant.ARITH);
        putInt(data, 4, 35);
        assertFormatError(data, "bad image header size");
    }

    @Test
    @DisplayName("a non-positive endOfMemory is rejected")
    void badEndOfMemoryIsRejected() {
        byte[] data = SyntheticImageBuilder.build(SyntheticImageBuilder.Variant.ARITH);
        putInt(data, 8, 0);
        assertFormatError(data, "bad end of memory");
    }

    @Test
    @DisplayName("a truncated image is rejected")
    void truncatedImageIsRejected() {
        byte[] data = SyntheticImageBuilder.build(SyntheticImageBuilder.Variant.ARITH);
        assertFormatError(Arrays.copyOf(data, data.length - 4), "image file is truncated");
    }

    @Test
    @DisplayName("a dangling compact classes slot is rejected")
    void missingCompactClassesIsRejected() {
        byte[] data = SyntheticImageBuilder.build(SyntheticImageBuilder.Variant.ARITH);
        putInt(data, specialSlotOffset(data, Squeak.splOb_CompactClasses), 0);
        assertFormatError(data, "compact classes array not found");
    }

    @Test
    @DisplayName("a compact classes slot pointing at nil is malformed")
    void malformedCompactClassesIsRejected() {
        byte[] data = SyntheticImageBuilder.build(SyntheticImageBuilder.Variant.ARITH);
        int nilOop = intAt(data, specialSlotOffset(data, Squeak.splOb_NilObject));
        putInt(data, specialSlotOffset(data, Squeak.splOb_CompactClasses), nilOop);
        assertFormatError(data, "compact classes array is malformed");
    }

    @Test
    @DisplayName("a dangling Float class slot is rejected")
    void missingFloatClassIsRejected() {
        byte[] data = SyntheticImageBuilder.build(SyntheticImageBuilder.Variant.ARITH);
        putInt(data, specialSlotOffset(data, Squeak.splOb_ClassFloat), 0);
        assertFormatError(data, "Float class not found");
    }

    private void assertFormatError(byte[] data, String messagePart) {
        SqueakImageFormatException failure =
                assertThrows(SqueakImageFormatException.class, () -> load(data));
        assertTrue(failure.getMessage().contains(messagePart), failure.getMessage());
    }

    private static int runCountingBytecodes(SqueakVM vm) {
        SqueakVM.BytecodeExcutor[] original = vm.bytecodeTable.clone();
        int[] count = {0};
        try {
            for (int i = 0; i < original.length; i++) {
                SqueakVM.BytecodeExcutor delegate = original[i];
                vm.bytecodeTable[i] = bytecode -> {
                    count[0]++;
                    delegate.excute(bytecode);
                };
            }
            runToTrap(vm);
            return count[0];
        } finally {
            System.arraycopy(original, 0, vm.bytecodeTable, 0, original.length);
        }
    }

    private static int intAt(byte[] data, int offset) {
        return ByteBuffer.wrap(data, offset, 4).order(ByteOrder.BIG_ENDIAN).getInt();
    }

    private static void putInt(byte[] data, int offset, int value) {
        ByteBuffer.wrap(data, offset, 4).order(ByteOrder.BIG_ENDIAN).putInt(value);
    }

    /**
     * The special objects array is the last object; its data begins after the
     * three-word object header. The oop points at the second header word, so
     * the object's heap offset is {@code oop - oldBaseAddr - 8}.
     */
    private static int specialSlotOffset(byte[] data, int slot) {
        int headerSize = intAt(data, 4);
        int oldBaseAddr = intAt(data, 12);
        int specialObjectsOop = intAt(data, 16);
        int objectOffset = specialObjectsOop - oldBaseAddr - 8;
        int dataOffset = headerSize + objectOffset + 12;
        if (slot < 0 || slot >= 64) {
            throw new IllegalArgumentException("slot out of range: " + slot);
        }
        return dataOffset + (slot * 4);
    }
}
