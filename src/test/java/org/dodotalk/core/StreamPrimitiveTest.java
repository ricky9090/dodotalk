package org.dodotalk.core;

import org.dodotalk.TestImages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the stream primitives (65-67) and collated string
 * compare (235). These were previously TODO/unimplemented and showed up in
 * the runtime failure histogram.
 */
class StreamPrimitiveTest {

    private SqueakVM vm;
    private SqueakPrimitiveHandler handler;

    @BeforeEach
    void loadVM() throws Exception {
        vm = new SqueakVM(new SqueakImage(TestImages.miniImage()));
        handler = SqueakVM.primHandler;
    }

    private SqueakObject newStream(SqueakObject array, int position, int limit) {
        SqueakObject stream = vm.instantiateClass(Squeak.splOb_ClassArray, 3);
        stream.setPointer(Squeak.Stream_array, array);
        stream.setPointer(Squeak.Stream_position, InterpreterHelper.smallFromInt(position));
        stream.setPointer(Squeak.Stream_limit, InterpreterHelper.smallFromInt(limit));
        return stream;
    }

    private static int position(SqueakObject stream) {
        return stream.getPointerI(Squeak.Stream_position);
    }

    @Test
    @DisplayName("String streams read characters and advance their position")
    void stringStreamNext() {
        SqueakObject string = handler.makeStString("abc");
        SqueakObject stream = newStream(string, 0, 3);

        vm.push(stream);
        assertTrue(handler.doPrimitive(65, 0));
        assertEquals(Integer.valueOf('a'), ((SqueakObject) vm.top()).getPointerI(0));
        assertEquals(1, position(stream));

        vm.push(stream);
        assertTrue(handler.doPrimitive(65, 0));
        assertEquals(Integer.valueOf('b'), ((SqueakObject) vm.top()).getPointerI(0));

        vm.push(stream);
        assertTrue(handler.doPrimitive(65, 0));
        assertEquals(Integer.valueOf('c'), ((SqueakObject) vm.top()).getPointerI(0));

        vm.push(stream);
        assertFalse(handler.doPrimitive(65, 0), "next past the limit must fail");
    }

    @Test
    @DisplayName("String streams write characters and answer the argument")
    void stringStreamNextPut() {
        SqueakObject string = handler.makeStString("abc");
        SqueakObject stream = newStream(string, 0, 3);
        SqueakObject character = handler.charFromInt('x');

        vm.push(stream);
        vm.push(character);
        assertTrue(handler.doPrimitive(66, 1));
        assertSame(character, vm.top());
        assertEquals((byte) 'x', ((byte[]) string.bits)[0]);
        assertEquals(1, position(stream));
    }

    @Test
    @DisplayName("Array streams preserve object identity")
    void arrayStreamNext() {
        SqueakObject backing = vm.instantiateClass(Squeak.splOb_ClassArray, 2);
        backing.setPointer(0, SqueakVM.trueObj);
        backing.setPointer(1, SqueakVM.falseObj);
        SqueakObject stream = newStream(backing, 0, 2);

        vm.push(stream);
        assertTrue(handler.doPrimitive(65, 0));
        assertSame(SqueakVM.trueObj, vm.top());

        vm.push(stream);
        assertTrue(handler.doPrimitive(65, 0));
        assertSame(SqueakVM.falseObj, vm.top());
    }

    @Test
    @DisplayName("atEnd reflects the position, limit and collection size")
    void streamAtEnd() {
        SqueakObject string = handler.makeStString("ab");
        SqueakObject stream = newStream(string, 0, 2);

        vm.push(stream);
        assertTrue(handler.doPrimitive(67, 0));
        assertSame(SqueakVM.falseObj, vm.top());

        stream.setPointer(Squeak.Stream_position, InterpreterHelper.smallFromInt(2));
        vm.push(stream);
        assertTrue(handler.doPrimitive(67, 0));
        assertSame(SqueakVM.trueObj, vm.top());
    }

    @Test
    @DisplayName("collated string compare returns 1/2/3 for less/equal/greater")
    void collatedStringCompare() {
        SqueakObject order = vm.instantiateClass(Squeak.splOb_ClassByteArray, 256);
        byte[] table = (byte[]) order.bits;
        for (int i = 0; i < table.length; i++) {
            table[i] = (byte) i;
        }

        assertEquals(1, compare("abc", "abd", order));
        assertEquals(2, compare("same", "same", order));
        assertEquals(3, compare("abd", "abc", order));
        assertEquals(1, compare("ab", "abc", order));
        assertEquals(3, compare("abc", "ab", order));
    }

    private int compare(String first, String second, SqueakObject order) {
        SqueakObject receiver = (SqueakObject) SqueakVM.specialObjects[Squeak.splOb_ClassString];
        vm.push(receiver);
        vm.push(handler.makeStString(first));
        vm.push(handler.makeStString(second));
        vm.push(order);
        assertTrue(handler.doPrimitive(235, 3));
        return (Integer) vm.top();
    }
}
