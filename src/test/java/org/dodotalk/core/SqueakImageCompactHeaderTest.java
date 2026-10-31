package org.dodotalk.core;

import org.dodotalk.TestImages;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqueakImageCompactHeaderTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("saved images lead with real nil, false, and true objects")
    void leadingObjectsMatchOriginalSqueakLayout() throws Exception {
        Layout layout = parse(saveRaw("leading.image"));
        assertTrue(layout.objects.size() >= 3, "image must contain the leading objects");

        Obj nil = layout.objects.get(0);
        Obj falseObject = layout.objects.get(1);
        Obj trueObject = layout.objects.get(2);

        assertEquals(Squeak.HeaderTypeClass, nil.type);
        assertEquals(layout.baseAddr + 4, nil.oop);
        assertEquals(0, nil.dataWords);

        assertEquals(Squeak.HeaderTypeClass, falseObject.type);
        assertEquals(layout.baseAddr + 12, falseObject.oop);
        assertEquals(0, falseObject.dataWords);

        assertEquals(Squeak.HeaderTypeClass, trueObject.type);
        assertEquals(layout.baseAddr + 20, trueObject.oop);
        assertEquals(0, trueObject.dataWords);

        Obj specialObjects = layout.byOop.get(layout.specialObjectsOop);
        assertNotNull(specialObjects, "special objects array must be present");
        assertEquals(nil.oop, pointer(layout, specialObjects, Squeak.splOb_NilObject));
        assertEquals(falseObject.oop,
                pointer(layout, specialObjects, Squeak.splOb_FalseObject));
        assertEquals(trueObject.oop,
                pointer(layout, specialObjects, Squeak.splOb_TrueObject));
    }

    @Test
    @DisplayName("the writer emits short, class, and size-and-class headers")
    void writerUsesAllHeaderKinds() throws Exception {
        Layout layout = parse(saveRaw("mixed.image"));
        Map<Integer, Integer> compact = compactIndexes(layout);

        int shortHeaders = 0;
        int classHeaders = 0;
        int sizeAndClassHeaders = 0;

        for (int i = 0; i < layout.objects.size(); i++) {
            Obj object = layout.objects.get(i);
            switch (object.type) {
                case Squeak.HeaderTypeShort:
                    shortHeaders++;
                    assertTrue(object.classRef >= 1 && object.classRef <= 31,
                            "short header compact index out of range: " + object.classRef);
                    assertTrue(object.totalWords <= ImageHeaderPlanner.MAX_SMALL_OBJECT_WORDS);
                    assertTrue(compactClassOop(layout, compact, object.classRef) != 0,
                            "short header must resolve to a compact class");
                    break;
                case Squeak.HeaderTypeClass:
                    classHeaders++;
                    assertTrue(object.totalWords <= ImageHeaderPlanner.MAX_SMALL_OBJECT_WORDS);
                    break;
                case Squeak.HeaderTypeSizeAndClass:
                    sizeAndClassHeaders++;
                    assertTrue(object.totalWords > ImageHeaderPlanner.MAX_SMALL_OBJECT_WORDS,
                            "size-and-class header used for an object that fits 63 words");
                    break;
                default:
                    throw new AssertionError("unexpected header type " + object.type);
            }

            int classOop = object.type == Squeak.HeaderTypeShort
                    ? compactClassOop(layout, compact, object.classRef)
                    : object.classRef;
            boolean isCompactClass = compact.containsKey(classOop);
            if (i < 3) {
                // nil/false/true are forced to class headers for the SqueakJS
                // oldBaseAddr + 4 convention.
                assertEquals(Squeak.HeaderTypeClass, object.type);
            } else if (object.totalWords > ImageHeaderPlanner.MAX_SMALL_OBJECT_WORDS) {
                assertEquals(Squeak.HeaderTypeSizeAndClass, object.type);
            } else if (isCompactClass) {
                assertEquals(Squeak.HeaderTypeShort, object.type,
                        "compact small object #" + i + " must use a short header");
            } else {
                assertEquals(Squeak.HeaderTypeClass, object.type,
                        "non-compact small object #" + i + " must use a class header");
            }
        }

        assertTrue(shortHeaders > 0, "image must contain short headers");
        assertTrue(classHeaders >= 3, "image must contain the leading class headers");
        assertTrue(sizeAndClassHeaders > 0, "image must contain at least one large object");
    }

    @Test
    @DisplayName("saved size matches the planned mixed-header layout and beats the 3-word baseline")
    void savedSizeMatchesPlanAndIsSmaller() throws Exception {
        byte[] saved = saveRaw("size.image");
        Layout layout = parse(saved);

        int plannedSize = 64;
        int threeWordBaseline = 64;
        for (Obj object : layout.objects) {
            plannedSize += object.headerWords * 4 + object.dataWords * 4;
            threeWordBaseline += 12 + object.dataWords * 4;
        }

        assertEquals(saved.length, plannedSize,
                "file size must match the planned object headers");
        assertTrue(saved.length < threeWordBaseline,
                "mixed headers must be smaller than the all-3-word layout");
        assertTrue(threeWordBaseline - saved.length > 1000,
                "the size reduction must be measurable");
    }

    @Test
    @DisplayName("raw and gzip saves reload with the same live graph")
    void rawAndGzipRoundTrip() throws Exception {
        Path raw = tempDir.resolve("roundtrip.image");
        Path gzip = tempDir.resolve("roundtrip.image.gz");

        SqueakImage image = new SqueakImage(TestImages.miniImage());
        SqueakVM vm = new SqueakVM(image);
        image.save(raw.toFile());
        image.save(gzip.toFile());

        assertFalse(isGzip(Files.readAllBytes(raw)), "raw target must not be gzip");
        assertTrue(isGzip(Files.readAllBytes(gzip)), "gzip target must be gzip");

        SqueakImage reloadedRaw = new SqueakImage(raw.toFile());
        SqueakVM rawVM = new SqueakVM(reloadedRaw);
        assertNotNull(reloadedRaw.specialObjectsArray);
        assertNotNull(rawVM.activeContext);

        SqueakImage reloadedGzip = new SqueakImage(gzip.toFile());
        SqueakVM gzipVM = new SqueakVM(reloadedGzip);
        assertNotNull(reloadedGzip.specialObjectsArray);
        assertNotNull(gzipVM.activeContext);
        assertEquals(vm.sp, rawVM.sp);
    }

    @Test
    @DisplayName("a missing nil object keeps the fallback placeholder path working")
    void fallbackPlaceholderWhenNilIsUnavailable() throws Exception {
        SqueakImage image = new SqueakImage(TestImages.miniImage());
        new SqueakVM(image);
        Object[] specialPointers = image.specialObjectsArray.pointers;
        Object savedNil = specialPointers[Squeak.splOb_NilObject];
        specialPointers[Squeak.splOb_NilObject] = null;
        try {
            Path target = tempDir.resolve("fallback.image");
            image.save(target.toFile());

            Layout layout = parse(Files.readAllBytes(target));
            Obj first = layout.objects.get(0);
            assertEquals(Squeak.HeaderTypeClass, first.type);
            assertEquals(layout.baseAddr + 4, first.oop);
            assertEquals(0, first.dataWords);
            assertTrue(layout.objects.size() > 1);
        } finally {
            specialPointers[Squeak.splOb_NilObject] = savedNil;
        }
    }

    private byte[] saveRaw(String name) throws Exception {
        SqueakImage image = new SqueakImage(TestImages.miniImage());
        new SqueakVM(image);
        Path target = tempDir.resolve(name);
        image.save(target.toFile());
        return Files.readAllBytes(target);
    }

    private static boolean isGzip(byte[] data) {
        return data.length >= 2 && (data[0] & 0xFF) == 0x1f && (data[1] & 0xFF) == 0x8b;
    }

    private static int pointer(Layout layout, Obj object, int index) {
        return layout.data.getInt(object.dataOffset + index * 4);
    }

    private static Map<Integer, Integer> compactIndexes(Layout layout) {
        Obj specialObjects = layout.byOop.get(layout.specialObjectsOop);
        int compactOop = pointer(layout, specialObjects, Squeak.splOb_CompactClasses);
        Obj compactClasses = layout.byOop.get(compactOop);
        assertNotNull(compactClasses, "compact class table must be present");
        Map<Integer, Integer> indexes = new HashMap<>();
        int count = Math.min(31, compactClasses.dataWords);
        int nilOop = pointer(layout, specialObjects, Squeak.splOb_NilObject);
        for (int i = 0; i < count; i++) {
            int classOop = pointer(layout, compactClasses, i);
            if (classOop == 0 || classOop == nilOop) {
                continue;
            }
            indexes.putIfAbsent(classOop, i + 1);
        }
        return indexes;
    }

    private static int compactClassOop(Layout layout, Map<Integer, Integer> compact, int index) {
        for (Map.Entry<Integer, Integer> entry : compact.entrySet()) {
            if (entry.getValue() == index) {
                return entry.getKey();
            }
        }
        return 0;
    }

    private static Layout parse(byte[] saved) {
        Layout layout = new Layout(ByteBuffer.wrap(saved).order(ByteOrder.BIG_ENDIAN));
        int headerSize = layout.data.getInt(4);
        int endOfMemory = layout.data.getInt(8);
        layout.baseAddr = layout.data.getInt(12);
        layout.specialObjectsOop = layout.data.getInt(16);

        int limit = headerSize + endOfMemory;
        int offset = headerSize;
        while (offset < limit) {
            int relative = offset - headerSize;
            int word0 = layout.data.getInt(offset);
            int type = word0 & Squeak.HeaderTypeMask;
            int headerWords;
            int totalWords;
            int classRef;
            int formatWord;
            switch (type) {
                case Squeak.HeaderTypeSizeAndClass:
                    headerWords = 3;
                    totalWords = word0 >>> 2;
                    classRef = layout.data.getInt(offset + 4);
                    formatWord = layout.data.getInt(offset + 8);
                    offset += 12;
                    break;
                case Squeak.HeaderTypeClass:
                    headerWords = 2;
                    classRef = word0 - Squeak.HeaderTypeClass;
                    formatWord = layout.data.getInt(offset + 4);
                    totalWords = (formatWord >>> 2) & 63;
                    offset += 8;
                    break;
                case Squeak.HeaderTypeShort:
                    headerWords = 1;
                    totalWords = (word0 >>> 2) & 63;
                    classRef = (word0 >>> 12) & 31;
                    formatWord = word0;
                    offset += 4;
                    break;
                default:
                    throw new AssertionError("unexpected header type " + type
                            + " at relative offset " + relative);
            }

            int dataWords = totalWords - 1;
            if (dataWords < 0) {
                throw new AssertionError("negative data size at relative offset " + relative);
            }
            Obj object = new Obj();
            object.offset = relative;
            object.oop = layout.baseAddr + relative + (headerWords - 1) * 4;
            object.type = type;
            object.headerWords = headerWords;
            object.totalWords = totalWords;
            object.dataWords = dataWords;
            object.classRef = classRef;
            object.dataOffset = offset;
            object.format = (formatWord >>> 8) & 15;
            layout.objects.add(object);
            layout.byOop.put(object.oop, object);
            offset += dataWords * 4;
        }
        if (offset != limit) {
            throw new AssertionError("object area ended at " + offset
                    + " instead of " + limit);
        }
        return layout;
    }

    private static final class Layout {
        final ByteBuffer data;
        final List<Obj> objects = new ArrayList<>();
        final Map<Integer, Obj> byOop = new HashMap<>();
        int baseAddr;
        int specialObjectsOop;

        Layout(ByteBuffer data) {
            this.data = data;
        }
    }

    private static final class Obj {
        int offset;
        int oop;
        int type;
        int headerWords;
        int totalWords;
        int dataWords;
        int classRef;
        int dataOffset;
        int format;
    }
}
