package org.dodotalk.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Regression tests for the object-table enumeration used by primitives 77/78
 * and by image-save code. GC may compact the table and invalidate the cached
 * lastObjectIndex hint.
 */
class ObjectMemoryEnumerationTest {

    @Test
    @DisplayName("a stale enumeration hint past the object table does not throw")
    void staleHintOutsideTableFallsBackSafely() throws Exception {
        ObjectMemory memory = new ObjectMemory();
        SqueakObject first = object();
        SqueakObject second = object();
        memory.registerObject(first);
        memory.registerObject(second);

        assertSame(first, memory.nextInstance(0, null));
        setLastObjectIndex(memory, memory.getObjectTableLength() + 10);

        assertEquals(0, memory.otIndexOfObject(first));
    }

    @Test
    @DisplayName("a missing object returns -1 instead of running past the table")
    void missingObjectReturnsMinusOne() {
        ObjectMemory memory = new ObjectMemory();
        SqueakObject present = object();
        SqueakObject missing = object();
        memory.registerObject(present);

        assertEquals(-1, memory.otIndexOfObject(missing));
    }

    @Test
    @DisplayName("gc compaction invalidates the cached hint")
    void compactionResetsHint() throws Exception {
        ObjectMemory memory = new ObjectMemory();
        memory.registerObject(object());
        memory.nextInstance(0, null);
        setLastObjectIndex(memory, 17);

        invokeReclaim(memory);

        assertEquals(-1, getLastObjectIndex(memory));
    }

    @Test
    @DisplayName("negative enumeration starts are clamped to the beginning")
    void negativeStartIsClamped() {
        ObjectMemory memory = new ObjectMemory();
        SqueakObject first = object();
        memory.registerObject(first);

        assertSame(first, memory.nextInstance(-5, null));
    }

    private static SqueakObject object() {
        return new SqueakObject((Integer) 0, 0, 0, new int[0]);
    }

    private static void setLastObjectIndex(ObjectMemory memory, int value) throws Exception {
        Field field = ObjectMemory.class.getDeclaredField("lastObjectIndex");
        field.setAccessible(true);
        field.setInt(memory, value);
    }

    private static int getLastObjectIndex(ObjectMemory memory) throws Exception {
        Field field = ObjectMemory.class.getDeclaredField("lastObjectIndex");
        field.setAccessible(true);
        return field.getInt(memory);
    }

    private static void invokeReclaim(ObjectMemory memory) throws Exception {
        Method method = ObjectMemory.class.getDeclaredMethod("reclaimNullOTSlots");
        method.setAccessible(true);
        method.invoke(memory);
    }
}
