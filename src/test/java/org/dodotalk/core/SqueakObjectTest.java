package org.dodotalk.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Hashtable;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the image decoding part of {@link SqueakObject} (task 1.3 of
 * the maintenance plan).
 * <p>
 * The tests exercise the Squeak object formats 0..15:
 * <ul>
 *     <li>0-4: pointer objects (fixed and/or indexable fields)</li>
 *     <li>5: unused</li>
 *     <li>6: word objects (Floats are converted to a Java double)</li>
 *     <li>7: unused</li>
 *     <li>8-11: byte objects (ByteArrays, Strings)</li>
 *     <li>12-15: compiled methods (literals plus bytecode)</li>
 * </ul>
 */
class SqueakObjectTest {

    /** oop of the class of the object under test */
    private static final int CLASS_OOP = 1000;
    /** oop of a "pointed to" object */
    private static final int TARGET_OOP = 3000;

    private Hashtable<Object, Object> oopMap;
    private Integer[] ccArray;
    private SqueakObject floatClass;
    private SqueakObject target;

    @BeforeEach
    void setUp() {
        oopMap = new Hashtable<>();
        ccArray = new Integer[31];
        for (int i = 0; i < ccArray.length; i++) {
            ccArray[i] = 2000 + i;
        }
        floatClass = new SqueakObject(0, 0, 0, new int[0]);
        target = new SqueakObject(0, 0, 0, new int[0]);
        oopMap.put(CLASS_OOP, target);
        oopMap.put(TARGET_OOP, target);
    }

    /** an oop that is decoded as a small integer with the value {@code value} */
    private static int smallIntOop(int value) {
        return (value << 1) | 1;
    }

    @Nested
    @DisplayName("formats 0..4: pointer objects")
    class PointerFormats {

        @Test
        @DisplayName("format 0: no fields at all")
        void format0() throws Exception {
            SqueakObject obj = new SqueakObject(CLASS_OOP, 0, 0, new int[0]);

            obj.install(oopMap, ccArray, floatClass);

            assertEquals(0, obj.getPointers().length);
            assertNull(obj.getBits(), "a pointer object must not keep any raw data");
            assertSame(target, obj.getSqClass(), "the class oop must be resolved");
        }

        @Test
        @DisplayName("format 1: fixed fields only")
        void format1() throws Exception {
            SqueakObject obj = new SqueakObject(CLASS_OOP, 1, 0, new int[]{TARGET_OOP});

            obj.install(oopMap, ccArray, floatClass);

            assertEquals(1, obj.getPointers().length);
            assertSame(target, obj.getPointers()[0]);
            assertNull(obj.getBits());
        }

        @Test
        @DisplayName("format 2: indexable fields only")
        void format2() throws Exception {
            SqueakObject obj = new SqueakObject(CLASS_OOP, 2, 0, new int[]{TARGET_OOP, TARGET_OOP});

            obj.install(oopMap, ccArray, floatClass);

            assertEquals(2, obj.getPointers().length);
            assertSame(target, obj.getPointers()[0]);
            assertSame(target, obj.getPointers()[1]);
        }

        @Test
        @DisplayName("format 3: fixed and indexable fields")
        void format3() throws Exception {
            SqueakObject obj = new SqueakObject(CLASS_OOP, 3, 0, new int[]{TARGET_OOP, TARGET_OOP});

            obj.install(oopMap, ccArray, floatClass);

            assertEquals(2, obj.getPointers().length);
            assertSame(target, obj.getPointers()[0]);
        }

        @Test
        @DisplayName("format 4: weak fields are decoded like normal pointers")
        void format4() throws Exception {
            SqueakObject obj = new SqueakObject(CLASS_OOP, 4, 0, new int[]{TARGET_OOP});

            obj.install(oopMap, ccArray, floatClass);

            assertEquals(1, obj.getPointers().length);
            assertSame(target, obj.getPointers()[0]);
        }

        @Test
        @DisplayName("odd oops are decoded as small integers")
        void smallIntegers() throws Exception {
            SqueakObject obj = new SqueakObject(CLASS_OOP, 1, 0, new int[]{smallIntOop(7), smallIntOop(-1)});

            obj.install(oopMap, ccArray, floatClass);

            assertEquals(Integer.valueOf(7), obj.getPointers()[0]);
            assertEquals(Integer.valueOf(-1), obj.getPointers()[1]);
        }

        @Test
        @DisplayName("every odd oop decodes into the SmallInt range")
        void smallIntegerRange() throws Exception {
            // the largest odd oop decodes to maxSmallInt, the smallest to minSmallInt
            SqueakObject obj = new SqueakObject(CLASS_OOP, 2, 0,
                    new int[]{smallIntOop(SqueakVM.maxSmallInt), smallIntOop(SqueakVM.minSmallInt)});

            obj.install(oopMap, ccArray, floatClass);

            assertEquals(Integer.valueOf(SqueakVM.maxSmallInt), obj.getPointers()[0]);
            assertEquals(Integer.valueOf(SqueakVM.minSmallInt), obj.getPointers()[1]);
        }
    }

    @Nested
    @DisplayName("format 5, 6 and 7: word objects")
    class WordFormats {

        @Test
        @DisplayName("format 6 with the Float class is converted to a double")
        void floatObject() throws Exception {
            oopMap.put(CLASS_OOP, floatClass);
            double value = -1234.5678;
            long rawBits = Double.doubleToLongBits(value);
            int high = (int) (rawBits >>> 32);
            int low = (int) rawBits;

            SqueakObject obj = new SqueakObject(CLASS_OOP, 6, 0, new int[]{high, low});
            obj.install(oopMap, ccArray, floatClass);

            assertInstanceOf(Double.class, obj.getBits());
            assertEquals(value, (Double) obj.getBits(), 0.0);
            assertNull(obj.getPointers());
        }

        @Test
        @DisplayName("format 6 keeps the raw words of a non Float object")
        void nonFloatWordObject() throws Exception {
            SqueakObject obj = new SqueakObject(CLASS_OOP, 6, 0, new int[]{0x1234, 0x5678});

            obj.install(oopMap, ccArray, floatClass);

            assertInstanceOf(int[].class, obj.getBits());
            assertArrayEquals(new int[]{0x1234, 0x5678}, (int[]) obj.getBits());
        }

        @Test
        @DisplayName("format 5 and 7 are unused and keep their raw words")
        void unusedFormats() throws Exception {
            SqueakObject five = new SqueakObject(CLASS_OOP, 5, 0, new int[]{0x1234});
            SqueakObject seven = new SqueakObject(CLASS_OOP, 7, 0, new int[]{0x1234});

            five.install(oopMap, ccArray, floatClass);
            seven.install(oopMap, ccArray, floatClass);

            assertInstanceOf(int[].class, five.getBits());
            assertInstanceOf(int[].class, seven.getBits());
        }
    }

    @Nested
    @DisplayName("formats 8..11: byte objects")
    class ByteFormats {

        @Test
        @DisplayName("format 8: a full word of bytes")
        void format8() throws Exception {
            SqueakObject obj = new SqueakObject(CLASS_OOP, 8, 0, new int[]{0x41424344});

            obj.install(oopMap, ccArray, floatClass);

            assertInstanceOf(byte[].class, obj.getBits());
            assertArrayEquals(new byte[]{0x41, 0x42, 0x43, 0x44}, (byte[]) obj.getBits());
        }

        @Test
        @DisplayName("the low two bits of the format encode the padding of the last word")
        void padding() throws Exception {
            SqueakObject nine = new SqueakObject(CLASS_OOP, 9, 0, new int[]{0x41424344});
            SqueakObject ten = new SqueakObject(CLASS_OOP, 10, 0, new int[]{0x41424344});
            SqueakObject eleven = new SqueakObject(CLASS_OOP, 11, 0, new int[]{0x41424344});

            nine.install(oopMap, ccArray, floatClass);
            ten.install(oopMap, ccArray, floatClass);
            eleven.install(oopMap, ccArray, floatClass);

            assertEquals(3, ((byte[]) nine.getBits()).length);
            assertEquals(2, ((byte[]) ten.getBits()).length);
            assertEquals(1, ((byte[]) eleven.getBits()).length);
            assertArrayEquals(new byte[]{0x41, 0x42, 0x43}, (byte[]) nine.getBits());
        }

        @Test
        @DisplayName("a byte object may span several words")
        void severalWords() throws Exception {
            SqueakObject obj = new SqueakObject(CLASS_OOP, 8, 0, new int[]{0x41424344, 0x45464748});

            obj.install(oopMap, ccArray, floatClass);

            assertEquals(8, ((byte[]) obj.getBits()).length);
            assertArrayEquals("ABCDEFGH".getBytes(), (byte[]) obj.getBits());
        }
    }

    @Nested
    @DisplayName("formats 12..15: compiled methods")
    class MethodFormats {

        /** the method header is stored shifted left by one bit */
        private static int methodHeader(int numLiterals) {
            return (numLiterals << 10) | 1;
        }

        @Test
        @DisplayName("the literals and the bytecode are separated")
        void methodLayout() throws Exception {
            SqueakObject obj = new SqueakObject(CLASS_OOP, 12, 0,
                    new int[]{methodHeader(2), TARGET_OOP, TARGET_OOP, 0x41424344, 0x45464748});

            obj.install(oopMap, ccArray, floatClass);

            // header + 2 literals
            assertEquals(3, obj.getPointers().length);
            assertEquals(Integer.valueOf(methodHeader(2) >> 1), obj.getPointers()[0],
                    "the method header is decoded as a small integer");
            assertSame(target, obj.getPointers()[1]);
            assertSame(target, obj.getPointers()[2]);

            assertInstanceOf(byte[].class, obj.getBits());
            assertArrayEquals("ABCDEFGH".getBytes(), (byte[]) obj.getBits());
        }

        @Test
        @DisplayName("the number of literals is taken from the method header")
        void literalCount() throws Exception {
            SqueakObject obj = new SqueakObject(CLASS_OOP, 12, 0,
                    new int[]{methodHeader(2), TARGET_OOP, TARGET_OOP, 0x41424344});

            obj.install(oopMap, ccArray, floatClass);

            assertEquals(2, obj.methodNumLits());
            assertEquals(3, obj.getPointers().length);
        }

        @Test
        @DisplayName("the padding of the last bytecode word is removed")
        void methodPadding() throws Exception {
            SqueakObject obj = new SqueakObject(CLASS_OOP, 13, 0,
                    new int[]{methodHeader(0), 0x41424344, 0x45464748});

            obj.install(oopMap, ccArray, floatClass);

            // format 13 -> one padding byte in the last word
            assertEquals(7, ((byte[]) obj.getBits()).length);
        }
    }

    @Nested
    @DisplayName("class references")
    class ClassReferences {

        @Test
        @DisplayName("a compact class index is resolved through the compact class array")
        void compactClassIndex() throws Exception {
            SqueakObject compactTarget = new SqueakObject(0, 0, 0, new int[0]);
            oopMap.put(ccArray[4], compactTarget);

            SqueakObject obj = new SqueakObject(5, 0, 0, new int[0]);
            obj.install(oopMap, ccArray, floatClass);

            assertSame(compactTarget, obj.getSqClass());
        }

        @Test
        @DisplayName("installing an object twice does not change it")
        void installIsIdempotent() throws Exception {
            SqueakObject obj = new SqueakObject(CLASS_OOP, 1, 0, new int[]{TARGET_OOP});

            obj.install(oopMap, ccArray, floatClass);
            Object[] pointersAfterFirstInstall = obj.getPointers();
            Object bitsAfterFirstInstall = obj.getBits();

            obj.install(oopMap, ccArray, floatClass);

            assertSame(pointersAfterFirstInstall, obj.getPointers());
            assertSame(bitsAfterFirstInstall, obj.getBits());
            assertSame(target, obj.getPointers()[0]);
        }
    }

    @Nested
    @DisplayName("malformed data is reported (task 1.6)")
    class MalformedData {

        @Test
        @DisplayName("a class reference that is not an oop is rejected")
        void invalidClassReference() {
            SqueakObject obj = new SqueakObject(CLASS_OOP, 0, 0, new int[0]);
            obj.sqClass = "not an oop";

            SqueakImageFormatException e = assertThrows(SqueakImageFormatException.class,
                    () -> obj.install(oopMap, ccArray, floatClass));
            assertTrue(e.getMessage().contains("not an image oop"), e.getMessage());
        }

        @Test
        @DisplayName("a class oop that points nowhere is rejected")
        void danglingClassOop() {
            SqueakObject obj = new SqueakObject(999999, 0, 0, new int[0]);

            SqueakImageFormatException e = assertThrows(SqueakImageFormatException.class,
                    () -> obj.install(oopMap, ccArray, floatClass));
            assertTrue(e.getMessage().contains("does not refer to any object"), e.getMessage());
        }

        @Test
        @DisplayName("a compact class index without an oop is rejected")
        void danglingCompactClass() {
            ccArray[0] = null;
            SqueakObject obj = new SqueakObject(1, 0, 0, new int[0]);

            SqueakImageFormatException e = assertThrows(SqueakImageFormatException.class,
                    () -> obj.install(oopMap, ccArray, floatClass));
            assertTrue(e.getMessage().contains("compact class index"), e.getMessage());
        }

        @Test
        @DisplayName("a float with less than two words is rejected")
        void truncatedFloat() {
            oopMap.put(CLASS_OOP, floatClass);
            SqueakObject obj = new SqueakObject(CLASS_OOP, 6, 0, new int[]{0x3FF00000});

            SqueakImageFormatException e = assertThrows(SqueakImageFormatException.class,
                    () -> obj.install(oopMap, ccArray, floatClass));
            assertTrue(e.getMessage().contains("float object needs 2 words"), e.getMessage());
        }

        @Test
        @DisplayName("a method that declares more literals than it has words is rejected")
        void methodWithTooManyLiterals() {
            SqueakObject obj = new SqueakObject(CLASS_OOP, 12, 0, new int[]{(10 << 10) | 1, 0});

            SqueakImageFormatException e = assertThrows(SqueakImageFormatException.class,
                    () -> obj.install(oopMap, ccArray, floatClass));
            assertTrue(e.getMessage().contains("literals"), e.getMessage());
        }

        @Test
        @DisplayName("a method without any word at all is rejected")
        void methodWithoutHeader() {
            SqueakObject obj = new SqueakObject(CLASS_OOP, 12, 0, new int[0]);

            SqueakImageFormatException e = assertThrows(SqueakImageFormatException.class,
                    () -> obj.install(oopMap, ccArray, floatClass));
            assertTrue(e.getMessage().contains("header word"), e.getMessage());
        }
    }
}
