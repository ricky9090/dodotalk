package org.dodotalk.core;

import org.dodotalk.TestImages;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Hashtable;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the context handling of {@link SqueakVM}
 * (task 1.7 of the maintenance plan).
 * <p>
 * A context that cannot be interpreted has to be reported instead of being
 * ignored with a bare "error" message.
 */
class SqueakVMContextTest {

    private static final int CLASS_OOP = 1000;

    private static SqueakVM vm;

    private final Hashtable<Object, Object> oopMap = new Hashtable<>();
    private final Integer[] ccArray = new Integer[31];
    private SqueakObject floatClass;

    @BeforeAll
    static void loadVM() throws IOException {
        SqueakImage image = new SqueakImage(TestImages.miniImage());
        vm = new SqueakVM(image);
    }

    /** an oop that is decoded as a small integer with the value {@code value} */
    private static int smallIntOop(int value) {
        return (value << 1) | 1;
    }

    /** prepares the object memory used by the tests */
    private void prepareOopMap() {
        floatClass = new SqueakObject(0, 0, 0, new int[0]);
        oopMap.put(CLASS_OOP, new SqueakObject(0, 0, 0, new int[0]));
        for (int i = 0; i < ccArray.length; i++) {
            ccArray[i] = 2000 + i;
        }
    }

    /** builds an object with the given format and installs it */
    private SqueakObject install(int format, int[] data) throws SqueakImageFormatException {
        SqueakObject obj = new SqueakObject(CLASS_OOP, format, 0, data);
        obj.install(oopMap, ccArray, floatClass);
        return obj;
    }

    @Test
    @DisplayName("a valid context is accepted")
    void validContext() throws Exception {
        prepareOopMap();

        // a method with an empty literal frame and one word of bytecode
        SqueakObject method = install(12, new int[]{1, 0x41424344});
        oopMap.put(100, method);

        // sender, ip, sp, method, (unused), receiver
        SqueakObject context = install(1, new int[]{
                smallIntOop(0), // sender
                smallIntOop(6), // instruction pointer -> pc 0
                smallIntOop(-5), // stack pointer -> sp 0
                100, // method
                smallIntOop(0), // unused slot
                smallIntOop(0) // receiver
        });

        assertDoesNotThrow(() -> vm.fetchContextRegisters(context));
        assertEquals(0, vm.pc);
        assertEquals(0, vm.sp);
    }

    @Test
    @DisplayName("a context without a method is rejected")
    void contextWithoutMethod() throws Exception {
        prepareOopMap();

        // the method field points to an oop that is not in the image
        SqueakObject context = install(1, new int[]{
                smallIntOop(0), smallIntOop(6), smallIntOop(-5),
                424242, // dangling oop
                smallIntOop(0), smallIntOop(0)
        });

        SqueakContextException e = assertThrows(SqueakContextException.class,
                () -> vm.fetchContextRegisters(context));
        assertTrue(e.getMessage().contains("valid method"), e.getMessage());
    }

    @Test
    @DisplayName("a block context without a home context is rejected")
    void blockContextWithoutHome() throws Exception {
        prepareOopMap();

        // an integer in the method field marks a block context, the home context
        // is expected in slot 5 but is empty here
        SqueakObject context = install(1, new int[]{
                smallIntOop(0), smallIntOop(6), smallIntOop(-5),
                smallIntOop(7), // method index -> block context
                smallIntOop(0),
                smallIntOop(0) // no home context
        });

        SqueakContextException e = assertThrows(SqueakContextException.class,
                () -> vm.fetchContextRegisters(context));
        assertTrue(e.getMessage().contains("home context"), e.getMessage());
    }

    @Test
    @DisplayName("a null context is rejected")
    void nullContext() {
        SqueakContextException e = assertThrows(SqueakContextException.class,
                () -> vm.fetchContextRegisters(null));
        assertTrue(e.getMessage().contains("null context"), e.getMessage());
    }
}
