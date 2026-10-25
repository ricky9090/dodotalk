package org.dodotalk.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the arithmetic helpers of {@link InterpreterHelper}
 * (task 1.4 of the maintenance plan).
 * <p>
 * The expected values follow the Squeak 2.2 semantics, which are documented in
 * the Squeak sources shipped with this project:
 * <ul>
 *     <li>{@code //} answers an Integer rounded towards negative infinity</li>
 *     <li>{@code \\} is the modulo rounded towards negative infinity</li>
 *     <li>{@code quo:} is the truncated division rounded towards zero</li>
 * </ul>
 */
class InterpreterHelperTest {

    private static final int MIN = SqueakVM.minSmallInt;
    private static final int MAX = SqueakVM.maxSmallInt;
    private static final int NON_SMALL = SqueakVM.nonSmallInt;

    @Nested
    @DisplayName("SmallInt boundaries")
    class SmallIntBoundaries {

        @Test
        @DisplayName("canBeSTInteger accepts exactly the SmallInt range")
        void canBeSTInteger() {
            assertTrue(InterpreterHelper.canBeSTInteger(MIN));
            assertTrue(InterpreterHelper.canBeSTInteger(0));
            assertTrue(InterpreterHelper.canBeSTInteger(MAX));

            assertFalse(InterpreterHelper.canBeSTInteger(MIN - 1));
            assertFalse(InterpreterHelper.canBeSTInteger(MAX + 1));
            assertFalse(InterpreterHelper.canBeSTInteger(NON_SMALL));
        }

        @Test
        @DisplayName("smallFromInt converts the SmallInt range and rejects everything else")
        void smallFromInt() {
            assertEquals(Integer.valueOf(MIN), InterpreterHelper.smallFromInt(MIN));
            assertEquals(Integer.valueOf(0), InterpreterHelper.smallFromInt(0));
            assertEquals(Integer.valueOf(MAX), InterpreterHelper.smallFromInt(MAX));

            assertNull(InterpreterHelper.smallFromInt(MIN - 1));
            assertNull(InterpreterHelper.smallFromInt(MAX + 1));
            assertNull(InterpreterHelper.smallFromInt(NON_SMALL));
        }

        @Test
        @DisplayName("isSTInteger only accepts boxed Integers")
        void isSTInteger() {
            assertTrue(InterpreterHelper.isSTInteger(42));
            assertTrue(InterpreterHelper.isSTInteger(Integer.valueOf(MIN)));

            assertFalse(InterpreterHelper.isSTInteger(null));
            assertFalse(InterpreterHelper.isSTInteger("42"));
            assertFalse(InterpreterHelper.isSTInteger(new SqueakObject(0, 0, 0, new int[0])));
        }
    }

    @Nested
    @DisplayName("div: floor division (Squeak //)")
    class Div {

        @Test
        @DisplayName("rounds towards negative infinity")
        void roundsDown() {
            assertEquals(3, InterpreterHelper.div(7, 2));
            assertEquals(-4, InterpreterHelper.div(-7, 2));
            assertEquals(-4, InterpreterHelper.div(7, -2));
            assertEquals(3, InterpreterHelper.div(-7, -2));
        }

        @Test
        @DisplayName("exact divisions")
        void exact() {
            assertEquals(0, InterpreterHelper.div(0, 5));
            assertEquals(-2, InterpreterHelper.div(-6, 3));
            assertEquals(2, InterpreterHelper.div(6, 3));
            assertEquals(3, InterpreterHelper.div(6, 2));
        }

        @Test
        @DisplayName("a division by zero fails")
        void divisionByZero() {
            assertEquals(NON_SMALL, InterpreterHelper.div(5, 0));
            assertEquals(NON_SMALL, InterpreterHelper.div(-5, 0));
            assertEquals(NON_SMALL, InterpreterHelper.div(0, 0));
        }

        @Test
        @DisplayName("the SmallInt boundaries are handled")
        void boundaries() {
            assertEquals(MAX / 2, InterpreterHelper.div(MAX, 2));
            assertEquals(MIN / 2, InterpreterHelper.div(MIN, 2));
            // minSmallInt // -1 does not fit into a SmallInt any more, the
            // primitive layer rejects the result (see smallFromInt)
            assertNull(InterpreterHelper.smallFromInt(InterpreterHelper.div(MIN, -1)));
        }
    }

    @Nested
    @DisplayName("mod: modulo (Squeak \\\\)")
    class Mod {

        @Test
        @DisplayName("the result has the sign of the divisor")
        void signOfDivisor() {
            assertEquals(1, InterpreterHelper.mod(7, 2));
            assertEquals(1, InterpreterHelper.mod(-7, 2));
            assertEquals(-1, InterpreterHelper.mod(7, -2));
            assertEquals(-1, InterpreterHelper.mod(-7, -2));
        }

        @Test
        @DisplayName("an exact division has no remainder")
        void exact() {
            assertEquals(0, InterpreterHelper.mod(6, 3));
            assertEquals(0, InterpreterHelper.mod(-6, 3));
            assertEquals(0, InterpreterHelper.mod(0, 5));
        }

        @Test
        @DisplayName("a modulo by zero fails")
        void moduloByZero() {
            assertEquals(NON_SMALL, InterpreterHelper.mod(5, 0));
            assertEquals(NON_SMALL, InterpreterHelper.mod(-5, 0));
        }

        @Test
        @DisplayName("mod and div are consistent")
        void consistentWithDiv() {
            for (int rcvr = -20; rcvr <= 20; rcvr++) {
                for (int arg = -7; arg <= 7; arg++) {
                    if (arg == 0) {
                        continue;
                    }
                    int div = InterpreterHelper.div(rcvr, arg);
                    assertEquals(rcvr, div * arg + InterpreterHelper.mod(rcvr, arg),
                            "div/mod must add up to the receiver for " + rcvr + " // " + arg);
                }
            }
        }
    }

    @Nested
    @DisplayName("quickDivide: exact division only (Squeak /)")
    class QuickDivide {

        @Test
        @DisplayName("only exact divisions succeed")
        void exactOnly() {
            assertEquals(2, InterpreterHelper.quickDivide(6, 3));
            assertEquals(-2, InterpreterHelper.quickDivide(-6, 3));
            assertEquals(0, InterpreterHelper.quickDivide(0, 5));

            assertEquals(NON_SMALL, InterpreterHelper.quickDivide(7, 2));
            assertEquals(NON_SMALL, InterpreterHelper.quickDivide(-7, 2));
        }

        @Test
        @DisplayName("a division by zero fails")
        void divisionByZero() {
            assertEquals(NON_SMALL, InterpreterHelper.quickDivide(6, 0));
        }
    }

    @Nested
    @DisplayName("safeMultiply")
    class SafeMultiply {

        @Test
        @DisplayName("multiplies without an overflow")
        void normal() {
            assertEquals(6, InterpreterHelper.safeMultiply(2, 3));
            assertEquals(-6, InterpreterHelper.safeMultiply(-2, 3));
            assertEquals(0, InterpreterHelper.safeMultiply(0, 12345));
            assertEquals(12345, InterpreterHelper.safeMultiply(12345, 1));
        }

        @Test
        @DisplayName("an overflowing multiplication fails")
        void overflow() {
            assertEquals(NON_SMALL, InterpreterHelper.safeMultiply(100000, 100000));
            assertEquals(NON_SMALL, InterpreterHelper.safeMultiply(65536, 65536));
        }

        @Test
        @DisplayName("a result that does not fit into a SmallInt is rejected by the caller")
        void outsideSmallIntRange() {
            int product = InterpreterHelper.safeMultiply(MAX, 2);
            assertNull(InterpreterHelper.smallFromInt(product),
                    "a product outside the SmallInt range must not be pushed as a SmallInt");
        }
    }

    @Nested
    @DisplayName("safeShift")
    class SafeShift {

        @Test
        @DisplayName("shifts left and right")
        void shifts() {
            assertEquals(1, InterpreterHelper.safeShift(1, 0));
            assertEquals(2, InterpreterHelper.safeShift(1, 1));
            assertEquals(4, InterpreterHelper.safeShift(1, 2));
            assertEquals(0, InterpreterHelper.safeShift(1, -1));
            assertEquals(0, InterpreterHelper.safeShift(2, -2));
        }

        @Test
        @DisplayName("bits that do not fit into 32 bits are detected")
        void lostBits() {
            // 0x40000000 << 2 does not fit into 32 bits any more
            assertEquals(NON_SMALL, InterpreterHelper.safeShift(0x40000000, 2));
            assertEquals(NON_SMALL, InterpreterHelper.safeShift(0x80000000, 1));
        }

        @Test
        @DisplayName("the shift works on the unsigned 32 bit representation")
        void unsignedRepresentation() {
            // 0x7FFFFFFF << 1 is 0xFFFFFFFE, no bit of the 32 bit word is lost
            assertEquals(0xFFFFFFFE, InterpreterHelper.safeShift(0x7FFFFFFF, 1));
        }

        @Test
        @DisplayName("KNOWN DEVIATION: a shift count of 32 or more is not detected")
        void shiftCountTooLarge() {
            // Java shifts wrap around modulo 32, so the result is silently wrong.
            // The bit primitives work on unsigned 32 bit values and would return
            // a bogus result instead of failing. This is tracked as a known issue
            // of the bit primitives (see the FIXME in SqueakPrimitiveHandler).
            assertEquals(1, InterpreterHelper.safeShift(1, 32));
        }
    }

    @Nested
    @DisplayName("SHL and SHR")
    class Shifts {

        @Test
        @DisplayName("shift counts of more than 31 bits give zero")
        void tooLarge() {
            assertEquals(0, InterpreterHelper.SHL(1, 32));
            assertEquals(0, InterpreterHelper.SHR(1, 32));
            assertEquals(0, InterpreterHelper.SHL(1, 100));
        }

        @Test
        @DisplayName("the right shift is unsigned")
        void unsignedRightShift() {
            assertEquals(2147483647, InterpreterHelper.SHR(-1, 1));
            assertEquals(1, InterpreterHelper.SHL(1, 0));
        }
    }

    @Nested
    @DisplayName("type predicates")
    class TypePredicates {

        @Test
        @DisplayName("bytes, words, pointers and methods are told apart")
        void formats() {
            SqueakObject bytes = new SqueakObject(0, 8, 0, new int[0]);
            SqueakObject words = new SqueakObject(0, 6, 0, new int[0]);
            SqueakObject pointers = new SqueakObject(0, 3, 0, new int[0]);
            SqueakObject weak = new SqueakObject(0, 4, 0, new int[0]);
            SqueakObject method = new SqueakObject(0, 12, 0, new int[0]);

            assertTrue(InterpreterHelper.isBytes(bytes));
            assertFalse(InterpreterHelper.isBytes(words));

            assertTrue(InterpreterHelper.isWords(words));
            assertFalse(InterpreterHelper.isWords(bytes));

            assertTrue(InterpreterHelper.isPointers(pointers));
            assertTrue(InterpreterHelper.isPointers(weak));
            assertFalse(InterpreterHelper.isPointers(words));

            assertTrue(InterpreterHelper.isWeak(weak));
            assertFalse(InterpreterHelper.isWeak(pointers));

            assertTrue(InterpreterHelper.isMethod(method));
            assertFalse(InterpreterHelper.isMethod(bytes));

            assertTrue(InterpreterHelper.isWordsOrBytes(words));
            assertTrue(InterpreterHelper.isWordsOrBytes(bytes));
            assertFalse(InterpreterHelper.isWordsOrBytes(pointers));
        }

        @Test
        @DisplayName("a non object is never of any type")
        void nonObjects() {
            assertFalse(InterpreterHelper.isBytes(null));
            assertFalse(InterpreterHelper.isPointers(null));
            assertFalse(InterpreterHelper.isWords(null));
            assertFalse(InterpreterHelper.isMethod(null));
            assertFalse(InterpreterHelper.isWeak(null));
        }

        @Test
        @DisplayName("SIZEOF counts the fields of an object")
        void sizeOf() {
            SqueakObject pointers = new SqueakObject(0, 3, 0, new int[0]);
            assertEquals(0, InterpreterHelper.SIZEOF(pointers));
            assertEquals(0, InterpreterHelper.SIZEOF(null));
        }
    }

    @Test
    @DisplayName("fetchPointerOfObject reads a field of an object")
    void fetchPointerOfObject() {
        SqueakObject obj = new SqueakObject(0, 1, 0, new int[0]);
        obj.pointers = new Object[]{Integer.valueOf(7)};

        assertNotNull(InterpreterHelper.fetchPointerOfObject(0, obj));
        assertEquals(Integer.valueOf(7), InterpreterHelper.fetchPointerOfObject(0, obj));
        assertNull(InterpreterHelper.fetchPointerOfObject(0, "not an object"));
    }
}
