package org.dodotalk.core;

import org.dodotalk.TestImages;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the numeric and string primitives fixed in Phase 1.6.
 * <p>
 * The primitives are exercised through {@link SqueakPrimitiveHandler#doPrimitive}
 * so that stack handling and the primitive failure path are covered as well.
 */
class NumericPrimitiveTest {

    private static SqueakVM vm;

    @BeforeAll
    static void loadVM() throws IOException {
        SqueakImage image = new SqueakImage(TestImages.miniImage());
        vm = new SqueakVM(image);
    }

    /**
     * Pushes the operands, invokes the primitive and restores the stack.
     *
     * @return the primitive result, or {@code null} when the primitive failed
     */
    private static Object call(int primitive, Object... operands) {
        int savedSp = vm.sp;
        try {
            for (Object operand : operands) {
                vm.push(operand);
            }
            boolean ok = SqueakVM.primHandler.doPrimitive(primitive, operands.length - 1);
            return ok ? vm.top() : null;
        } finally {
            vm.sp = savedSp;
        }
    }

    private static SqueakObject floatObject(double value) {
        SqueakObject floatObject = vm.instantiateClass(Squeak.splOb_ClassFloat, -1);
        floatObject.setFloatBits(value);
        return floatObject;
    }

    private static double floatValue(Object object) {
        assertTrue(object instanceof SqueakObject, "expected a Float object");
        return ((SqueakObject) object).getFloatBits();
    }

    @Test
    @DisplayName("timesTwoPower handles positive, zero and negative exponents")
    void timesTwoPower() {
        assertEquals(8.0, floatValue(call(54, floatObject(1.0), Integer.valueOf(3))), 0.0);
        assertEquals(1.0, floatValue(call(54, floatObject(1.0), Integer.valueOf(0))), 0.0);
        assertEquals(0.25, floatValue(call(54, floatObject(2.0), Integer.valueOf(-3))), 0.0);
        assertEquals(2.0, floatValue(call(54, floatObject(0.5), Integer.valueOf(2))), 0.0);
    }

    @Test
    @DisplayName("timesTwoPower rejects a non-integer exponent")
    void timesTwoPowerRejectsFractionalExponent() {
        assertNull(call(54, floatObject(2.0), floatObject(1.5)));
    }

    @Test
    @DisplayName("truncated rounds towards zero")
    void truncate() {
        assertEquals(3, call(51, floatObject(3.75)));
        assertEquals(-3, call(51, floatObject(-3.75)));
        assertEquals(0, call(51, floatObject(0.99)));
        assertEquals(0, call(51, floatObject(-0.99)));
    }

    @Test
    @DisplayName("truncated accepts the SmallInt boundaries")
    void truncateBoundaries() {
        assertEquals(SqueakVM.maxSmallInt, call(51, floatObject(SqueakVM.maxSmallInt)));
        assertEquals(SqueakVM.minSmallInt, call(51, floatObject(SqueakVM.minSmallInt)));
    }

    @Test
    @DisplayName("truncated fails outside the SmallInt range instead of saturating")
    void truncateOverflow() {
        assertNull(call(51, floatObject(SqueakVM.maxSmallInt + 1.0)));
        assertNull(call(51, floatObject(SqueakVM.minSmallInt - 1.0)));
        assertNull(call(51, floatObject(Double.NaN)));
        assertNull(call(51, floatObject(Double.POSITIVE_INFINITY)));
        assertNull(call(51, floatObject(Double.NEGATIVE_INFINITY)));
    }

    @Test
    @DisplayName("makeStString allocates the UTF-8 byte length, not the Java char count")
    void makeStStringUsesUtf8ByteLength() {
        SqueakObject string = SqueakVM.primHandler.makeStString("a中");

        byte[] expected = "a中".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(expected, (byte[]) string.getBits());
        assertEquals(4, ((byte[]) string.getBits()).length);
    }
}
