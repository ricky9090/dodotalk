package org.dodotalk.core;

import org.dodotalk.uilts.RuntimeStats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrimitiveRegressionTest {

    private PrimitiveTestSupport.Fixture fixture;

    @BeforeEach
    void setUp() {
        fixture = PrimitiveTestSupport.newFixture();
    }

    private static void assertSuccess(PrimitiveTestSupport.CallResult result,
                                     Object expected, int stackDelta) {
        assertTrue(result.success);
        assertEquals(expected, result.result);
        assertEquals(result.spBefore + stackDelta, result.spAfter);
        assertEquals(1, result.callDelta());
        assertEquals(0, result.failureDelta());
    }

    private static void assertFailure(PrimitiveTestSupport.CallResult result,
                                      RuntimeStats.PrimitiveFailure kind) {
        assertFalse(result.success);
        assertNull(result.result);
        assertEquals(result.spBefore, result.spAfter);
        assertEquals(kind, result.failureKind);
        assertEquals(1, result.callDelta());
        assertEquals(1, result.failureDelta());
    }

    @Test
    @DisplayName("specialSelectors has the 32 Squeak 2.2 selector pairs")
    void specialSelectorsShape() {
        assertEquals(64, SqueakVM.specialSelectors.length);
        assertEquals("+", ((SqueakObject) SqueakVM.specialSelectors[0]).asString());
        assertEquals(Integer.valueOf(1), SqueakVM.specialSelectors[1]);
        assertEquals("at:", ((SqueakObject) SqueakVM.specialSelectors[32]).asString());
        assertEquals(Integer.valueOf(1), SqueakVM.specialSelectors[33]);
        assertEquals("at:put:", ((SqueakObject) SqueakVM.specialSelectors[34]).asString());
        assertEquals(Integer.valueOf(2), SqueakVM.specialSelectors[35]);
        assertEquals("size", ((SqueakObject) SqueakVM.specialSelectors[36]).asString());
        assertEquals(Integer.valueOf(0), SqueakVM.specialSelectors[37]);
    }

    @Test
    @DisplayName("primitive 60 at: reads an Array element")
    void atReadsArrayElement() {
        SqueakObject array = fixture.newArray(3);
        array.setPointer(0, fixture.newSmallInt(7));
        array.setPointer(1, fixture.newSmallInt(8));
        array.setPointer(2, fixture.newSmallInt(9));

        assertSuccess(fixture.call(60, 1, array, fixture.newSmallInt(2)),
                Integer.valueOf(8), -1);
        fixture.resetStack();
        assertFailure(fixture.call(60, 1, array, fixture.newSmallInt(4)),
                RuntimeStats.PrimitiveFailure.FAILED);
    }

    @Test
    @DisplayName("primitive 61 at:put: writes an Array element")
    void atPutWritesArrayElement() {
        SqueakObject array = fixture.newArray(3);
        assertSuccess(fixture.call(61, 2, array, fixture.newSmallInt(2),
                fixture.newSmallInt(42)), Integer.valueOf(42), -2);
        assertEquals(Integer.valueOf(42), array.getPointer(1));
        fixture.resetStack();
        assertFailure(fixture.call(61, 2, array, fixture.newSmallInt(0),
                fixture.newSmallInt(42)), RuntimeStats.PrimitiveFailure.FAILED);
    }

    @Test
    @DisplayName("primitive 62 size answers the Array length")
    void sizeAnswersArrayLength() {
        SqueakObject array = fixture.newArray(3);
        assertSuccess(fixture.call(62, 0, array), Integer.valueOf(3), 0);
        fixture.resetStack();
        assertFailure(fixture.call(62, 0, fixture.newSmallInt(1)),
                RuntimeStats.PrimitiveFailure.FAILED);
    }

    @Test
    @DisplayName("primitive 10 divide handles success and division by zero")
    void integerDivide() {
        assertSuccess(fixture.call(10, 1, fixture.newSmallInt(6), fixture.newSmallInt(2)),
                Integer.valueOf(3), -1);
        fixture.resetStack();
        assertFailure(fixture.call(10, 1, fixture.newSmallInt(6), fixture.newSmallInt(0)),
                RuntimeStats.PrimitiveFailure.FAILED);
    }

    @Test
    @DisplayName("primitive 13 quo rounds toward zero")
    void integerQuo() {
        assertSuccess(fixture.call(13, 1, fixture.newSmallInt(-7), fixture.newSmallInt(2)),
                Integer.valueOf(-3), -1);
        fixture.resetStack();
        assertFailure(fixture.call(13, 1, fixture.newSmallInt(-7), fixture.newSmallInt(0)),
                RuntimeStats.PrimitiveFailure.FAILED);
    }

    @Test
    @DisplayName("primitive 17 bitShift rejects non-integer arguments")
    void integerBitShift() {
        assertSuccess(fixture.call(17, 1, fixture.newSmallInt(1), fixture.newSmallInt(3)),
                Integer.valueOf(8), -1);
        fixture.resetStack();
        assertFailure(fixture.call(17, 1, fixture.newSmallInt(1), SqueakVM.nilObj),
                RuntimeStats.PrimitiveFailure.FAILED);
    }

    @Test
    @DisplayName("primitive 65 next reads an Array stream")
    void streamNext() {
        SqueakObject backing = fixture.newArray(2);
        backing.setPointer(0, SqueakVM.trueObj);
        backing.setPointer(1, SqueakVM.falseObj);
        SqueakObject stream = fixture.newStream(backing, 0, 2);

        assertSuccess(fixture.call(65, 0, stream), SqueakVM.trueObj, 0);
        assertSuccess(fixture.call(65, 0, stream), SqueakVM.falseObj, 0);
        fixture.resetStack();
        assertFailure(fixture.call(65, 0, stream), RuntimeStats.PrimitiveFailure.FAILED);
    }

    @Test
    @DisplayName("primitive 66 nextPut: writes an Array stream")
    void streamNextPut() {
        SqueakObject backing = fixture.newArray(2);
        SqueakObject stream = fixture.newStream(backing, 0, 2);

        assertSuccess(fixture.call(66, 1, stream, SqueakVM.falseObj),
                SqueakVM.falseObj, -1);
        assertSame(SqueakVM.falseObj, backing.getPointer(0));
        fixture.resetStack();
        assertFailure(fixture.call(66, 1, fixture.newStream(backing, 2, 2),
                SqueakVM.trueObj), RuntimeStats.PrimitiveFailure.FAILED);
    }

    @Test
    @DisplayName("primitive 67 atEnd reflects the stream position")
    void streamAtEnd() {
        SqueakObject backing = fixture.newArray(2);
        SqueakObject stream = fixture.newStream(backing, 0, 2);
        assertSuccess(fixture.call(67, 0, stream), SqueakVM.falseObj, 0);
        stream.setPointer(Squeak.Stream_position, fixture.newSmallInt(2));
        assertSuccess(fixture.call(67, 0, stream), SqueakVM.trueObj, 0);
    }

    @Test
    @DisplayName("primitive 70 Class new allocates zero-sized instances")
    void primitiveNew() {
        SqueakObject classArray = (SqueakObject) SqueakVM.specialObjects[Squeak.splOb_ClassArray];
        PrimitiveTestSupport.CallResult result = fixture.call(70, 0, classArray);
        assertTrue(result.success);
        assertTrue(result.result instanceof SqueakObject);
        assertEquals(0, ((SqueakObject) result.result).pointersSize());
        assertEquals(result.spBefore, result.spAfter);
    }

    @Test
    @DisplayName("primitive 70 class mismatch is an EXCEPTION")
    void primitiveNewClassMismatchIsException() {
        assertThrows(RuntimeException.class,
                () -> fixture.call(70, 0, SqueakVM.nilObj));
        assertEquals(RuntimeStats.PrimitiveFailure.EXCEPTION,
                fixture.handler.getLastFailureKind());
        assertEquals(1, fixture.stats.getExceptionTotal());
    }

    @Test
    @DisplayName("primitive 71 new: allocates an indexable Array")
    void primitiveNewWithArg() {
        SqueakObject classArray = (SqueakObject) SqueakVM.specialObjects[Squeak.splOb_ClassArray];
        PrimitiveTestSupport.CallResult result = fixture.call(71, 1, classArray,
                fixture.newSmallInt(3));
        assertTrue(result.success);
        assertEquals(3, ((SqueakObject) result.result).pointersSize());
        assertEquals(result.spBefore - 1, result.spAfter);

        fixture.resetStack();
        assertFailure(fixture.call(71, 1, classArray, SqueakVM.nilObj),
                RuntimeStats.PrimitiveFailure.FAILED);
    }

    @Test
    @DisplayName("primitive 110 identity compares object identity")
    void identity() {
        assertSuccess(fixture.call(110, 1, SqueakVM.trueObj, SqueakVM.trueObj),
                SqueakVM.trueObj, -1);
        fixture.resetStack();
        assertSuccess(fixture.call(110, 1, SqueakVM.trueObj, SqueakVM.falseObj),
                SqueakVM.falseObj, -1);
    }

    @Test
    @DisplayName("primitive 112 bytesLeft answers without changing the stack")
    void bytesLeft() {
        PrimitiveTestSupport.CallResult result = fixture.call(112, 0, SqueakVM.nilObj);
        assertTrue(result.success);
        assertTrue(result.result instanceof Integer);
        assertEquals(result.spBefore, result.spAfter);
    }

    @Test
    @DisplayName("primitive 126 deferDisplayUpdates stores the boolean")
    void deferDisplayUpdates() {
        PrimitiveTestSupport.CallResult result = fixture.call(126, 1, SqueakVM.nilObj,
                SqueakVM.trueObj);
        assertTrue(result.success);
        assertSame(SqueakVM.nilObj, result.result);
        assertEquals(result.spBefore - 1, result.spAfter);
        assertTrue(fixture.vm.isDeferDisplayUpdates());

        fixture.resetStack();
        assertFailure(fixture.call(126, 1, SqueakVM.nilObj, fixture.newSmallInt(1)),
                RuntimeStats.PrimitiveFailure.FAILED);
    }

    @Test
    @DisplayName("primitive 127 fails cleanly without a display")
    void showDisplayRectWithoutDisplay() {
        assertFailure(fixture.call(127, 4, SqueakVM.nilObj,
                fixture.newSmallInt(0), fixture.newSmallInt(0),
                fixture.newSmallInt(10), fixture.newSmallInt(10)),
                RuntimeStats.PrimitiveFailure.FAILED);
    }

    @Test
    @DisplayName("primitive 133 setInterruptKey stores the keycode")
    void setInterruptKey() {
        PrimitiveTestSupport.CallResult result = fixture.call(133, 1, SqueakVM.nilObj,
                fixture.newSmallInt(110));
        assertTrue(result.success);
        assertSame(SqueakVM.nilObj, result.result);
        assertEquals(result.spBefore - 1, result.spAfter);
        assertEquals(110, fixture.vm.getInterruptKeycode());

        fixture.resetStack();
        assertFailure(fixture.call(133, 1, SqueakVM.nilObj, SqueakVM.nilObj),
                RuntimeStats.PrimitiveFailure.FAILED);
    }

    @Test
    @DisplayName("primitive 140 beep succeeds without an audio device")
    void beep() {
        assertSuccess(fixture.call(140, 0, SqueakVM.nilObj), SqueakVM.nilObj, 0);
    }

    @Test
    @DisplayName("primitive 172 soundStop is a successful no-op")
    void soundStopNoOp() {
        assertTrue(SqueakPrimitiveHandler.isNoOpStub(172));
        assertSuccess(fixture.call(172, 0, SqueakVM.nilObj), SqueakVM.nilObj, 0);
    }

    @Test
    @DisplayName("primitive 231 forceDisplayUpdate succeeds headlessly")
    void forceDisplayUpdate() {
        assertSuccess(fixture.call(231, 0, SqueakVM.nilObj), SqueakVM.nilObj, 0);
        fixture.resetStack();
        assertFailure(fixture.call(231, 1, SqueakVM.nilObj, SqueakVM.trueObj),
                RuntimeStats.PrimitiveFailure.FAILED);
    }

    @Test
    @DisplayName("primitive 235 compare returns 1, 2 or 3")
    void compareCollated() {
        SqueakObject order = fixture.newByteArray(256);
        byte[] table = (byte[]) order.bits;
        for (int i = 0; i < table.length; i++) {
            table[i] = (byte) i;
        }
        assertEquals(Integer.valueOf(1), compare("abc", "abd", order));
        assertEquals(Integer.valueOf(2), compare("same", "same", order));
        assertEquals(Integer.valueOf(3), compare("abd", "abc", order));
    }

    private Object compare(String first, String second, SqueakObject order) {
        PrimitiveTestSupport.CallResult result = fixture.call(235, 3,
                SqueakVM.specialObjects[Squeak.splOb_ClassString],
                fixture.newString(first), fixture.newString(second), order);
        assertTrue(result.success);
        assertEquals(result.spBefore - 3, result.spAfter);
        fixture.resetStack();
        return result.result;
    }

    @Test
    @DisplayName("primitive 235 rejects an invalid collation table")
    void compareRejectsInvalidTable() {
        assertFailure(fixture.call(235, 3,
                SqueakVM.specialObjects[Squeak.splOb_ClassString],
                fixture.newString("a"), fixture.newString("b"), SqueakVM.nilObj),
                RuntimeStats.PrimitiveFailure.FAILED);
    }

    @Test
    @DisplayName("primitive 96 copyBits fails cleanly without a BitBlt form")
    void copyBitsFailsCleanly() {
        assertFailure(fixture.call(96, 0, fixture.newArray(10)),
                RuntimeStats.PrimitiveFailure.FAILED);
    }

    @Test
    @DisplayName("primitive 103 scanCharacters rejects a wrong argument count")
    void scanCharactersRejectsWrongArgCount() {
        assertFailure(fixture.call(103, 0, SqueakVM.nilObj),
                RuntimeStats.PrimitiveFailure.FAILED);
    }

    @Test
    @DisplayName("intentional primitives report INTENTIONAL")
    void intentionalFailures() {
        assertFailure(fixture.call(0, 0, SqueakVM.nilObj),
                RuntimeStats.PrimitiveFailure.INTENTIONAL);
        fixture.resetStack();
        assertFailure(fixture.call(19, 0, SqueakVM.nilObj),
                RuntimeStats.PrimitiveFailure.INTENTIONAL);
        fixture.resetStack();
        assertFailure(fixture.call(20, 0, SqueakVM.nilObj),
                RuntimeStats.PrimitiveFailure.INTENTIONAL);
        fixture.resetStack();
        assertFailure(fixture.call(120, 0, SqueakVM.nilObj),
                RuntimeStats.PrimitiveFailure.INTENTIONAL);
        fixture.resetStack();
        assertFailure(fixture.call(123, 0, SqueakVM.nilObj),
                RuntimeStats.PrimitiveFailure.INTENTIONAL);
    }

    @Test
    @DisplayName("unimplemented primitives report UNIMPLEMENTED")
    void unimplementedPrimitives() {
        assertFailure(fixture.call(82, 0, SqueakVM.nilObj),
                RuntimeStats.PrimitiveFailure.UNIMPLEMENTED);
        fixture.resetStack();
        assertFailure(fixture.call(104, 0, SqueakVM.nilObj),
                RuntimeStats.PrimitiveFailure.UNIMPLEMENTED);
        fixture.resetStack();
        assertFailure(fixture.call(242, 0, SqueakVM.nilObj),
                RuntimeStats.PrimitiveFailure.UNIMPLEMENTED);
    }

    @Test
    @DisplayName("primitive 699 debug returns success")
    void debugPrimitiveReturnsSuccess() {
        assertSuccess(fixture.call(699, 0, SqueakVM.nilObj), SqueakVM.nilObj, 0);
    }

    @Test
    @DisplayName("a primitive header succeeds without running its fallback body")
    void primitiveHeaderSuccess() {
        PrimitiveTestSupport.CallResult result = PrimitiveTestSupport.callViaPrimitiveHeader(
                1, 1, Integer.valueOf(2), Integer.valueOf(3));
        assertTrue(result.success);
        assertEquals(Integer.valueOf(5), result.result);
        assertEquals(1, result.callDelta());
        assertEquals(0, result.failureDelta());
    }

    @Test
    @DisplayName("a failed primitive header runs the fallback body")
    void primitiveHeaderFailureRunsBody() {
        PrimitiveTestSupport.CallResult result = PrimitiveTestSupport.callViaPrimitiveHeader(
                1, 1, Integer.valueOf(2), SqueakVM.nilObj);
        assertFalse(result.success);
        assertEquals(Integer.valueOf(1), result.result);
        assertEquals(RuntimeStats.PrimitiveFailure.FAILED, result.failureKind);
        assertEquals(1, result.callDelta());
        assertEquals(1, result.failureDelta());
    }

    @Test
    @DisplayName("a special bytecode failure falls back through specialSelectors")
    void specialBytecodeFallback() {
        PrimitiveTestSupport.CallResult result =
                PrimitiveTestSupport.callSpecialBytecodeFallback();
        assertTrue(result.success);
        assertEquals(Integer.valueOf(42), result.result);
        assertNotNull(result.result);
    }
}
