package org.dodotalk.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageHeaderPlannerTest {

    @Test
    @DisplayName("compact small objects use the one-word short header")
    void compactSmallObjectUsesShortHeader() {
        ImageHeaderPlanner.HeaderPlan plan = ImageHeaderPlanner.plan(10, 1);
        assertEquals(ImageHeaderPlanner.HeaderKind.SHORT, plan.kind());
        assertEquals(11, plan.totalWords());
    }

    @Test
    @DisplayName("non-compact small objects use the two-word class header")
    void nonCompactSmallObjectUsesClassHeader() {
        ImageHeaderPlanner.HeaderPlan plan = ImageHeaderPlanner.plan(10, 0);
        assertEquals(ImageHeaderPlanner.HeaderKind.CLASS, plan.kind());
    }

    @Test
    @DisplayName("large objects use the three-word size-and-class header")
    void largeObjectUsesSizeAndClassHeader() {
        ImageHeaderPlanner.HeaderPlan plan = ImageHeaderPlanner.plan(63, 1);
        assertEquals(ImageHeaderPlanner.HeaderKind.SIZE_AND_CLASS, plan.kind());
    }

    @Test
    @DisplayName("the 63-word size boundary is inclusive for short and class headers")
    void sizeBoundaryIsInclusive() {
        assertEquals(ImageHeaderPlanner.HeaderKind.SHORT,
                ImageHeaderPlanner.plan(62, 1).kind());
        assertEquals(ImageHeaderPlanner.HeaderKind.CLASS,
                ImageHeaderPlanner.plan(62, 0).kind());
        assertEquals(ImageHeaderPlanner.HeaderKind.SIZE_AND_CLASS,
                ImageHeaderPlanner.plan(63, 0).kind());
    }

    @Test
    @DisplayName("invalid compact indices fall back to the class header")
    void invalidCompactIndicesFallBackToClassHeader() {
        assertEquals(ImageHeaderPlanner.HeaderKind.CLASS,
                ImageHeaderPlanner.plan(5, 0).kind());
        assertEquals(ImageHeaderPlanner.HeaderKind.CLASS,
                ImageHeaderPlanner.plan(5, 32).kind());
        assertEquals(ImageHeaderPlanner.HeaderKind.CLASS,
                ImageHeaderPlanner.plan(5, -1).kind());
    }

    @Test
    @DisplayName("class headers can be forced for the leading special objects")
    void classHeaderCanBeForced() {
        ImageHeaderPlanner.HeaderPlan plan = ImageHeaderPlanner.plan(0, 1);
        assertEquals(ImageHeaderPlanner.HeaderKind.SHORT, plan.kind());
        ImageHeaderPlanner.HeaderPlan forced = plan.asClassHeader();
        assertEquals(ImageHeaderPlanner.HeaderKind.CLASS, forced.kind());
        assertEquals(0, forced.dataWords());
        assertEquals(1, forced.compactClassIndex());
        assertSame(forced, forced.asClassHeader());
    }

    @Test
    @DisplayName("compact table skips nil, ignores non-objects, and keeps the smallest index")
    void compactTableMapping() {
        SqueakObject nil = objectWithPointers();
        SqueakObject classA = objectWithPointers();
        SqueakObject classB = objectWithPointers();

        SqueakObject compactClasses = objectWithPointers(
                classA, nil, classB, classA, Integer.valueOf(7), null);
        Object[] specialPointers = new Object[Squeak.splOb_CompactClasses + 1];
        specialPointers[Squeak.splOb_CompactClasses] = compactClasses;
        SqueakObject specialObjectsArray = objectWithPointers(specialPointers);

        Map<SqueakObject, Integer> indexes =
                ImageHeaderPlanner.compactClassIndexes(specialObjectsArray, nil);

        assertEquals(2, indexes.size());
        assertEquals(Integer.valueOf(1), indexes.get(classA));
        assertEquals(Integer.valueOf(3), indexes.get(classB));
        assertFalse(indexes.containsKey(nil));
        assertTrue(indexes.values().stream().allMatch(index -> index > 0));
    }

    private static SqueakObject objectWithPointers(Object... pointers) {
        SqueakObject object = new SqueakObject(Integer.valueOf(1), 0, 0, new int[0]);
        object.pointers = pointers;
        return object;
    }
}
