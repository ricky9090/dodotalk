package org.dodotalk.core;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Chooses the traditional Squeak object headers used by the image writer.
 * <p>
 * Squeak 2.2 images mix three header layouts: a one-word short header, a
 * two-word class header, and a three-word size-and-class header. The writer
 * has to decide the layout before it can assign oops, because the header size
 * determines where the object's oop points.
 */
final class ImageHeaderPlanner {

    /**
     * Largest object size that fits in the six-bit size field of a short or
     * class header. The value includes the header word itself.
     */
    static final int MAX_SMALL_OBJECT_WORDS = 63;

    /** Compact class indices are encoded in five bits, so only 1..31 are valid. */
    static final int MAX_COMPACT_CLASS_INDEX = 31;

    enum HeaderKind {
        SHORT(1),
        CLASS(2),
        SIZE_AND_CLASS(3);

        private final int words;

        HeaderKind(int words) {
            this.words = words;
        }

        int words() {
            return words;
        }
    }

    /**
     * Header decision for one object. {@code compactClassIndex} is zero when
     * the object's class is not in the compact class table.
     */
    static final class HeaderPlan {
        private final HeaderKind kind;
        private final int dataWords;
        private final int compactClassIndex;

        private HeaderPlan(HeaderKind kind, int dataWords, int compactClassIndex) {
            this.kind = kind;
            this.dataWords = dataWords;
            this.compactClassIndex = compactClassIndex;
        }

        HeaderKind kind() {
            return kind;
        }

        int dataWords() {
            return dataWords;
        }

        int compactClassIndex() {
            return compactClassIndex;
        }

        int totalWords() {
            return dataWords + 1;
        }

        /**
         * Forces the two-word class header. Used for the leading
         * {@code nil}, {@code false}, and {@code true} objects so the first
         * object's oop is {@code oldBaseAddr + 4}.
         */
        HeaderPlan asClassHeader() {
            if (kind == HeaderKind.CLASS) {
                return this;
            }
            return new HeaderPlan(HeaderKind.CLASS, dataWords, compactClassIndex);
        }
    }

    private ImageHeaderPlanner() {
    }

    static HeaderPlan plan(int dataWords, int compactClassIndex) {
        if (dataWords < 0) {
            throw new IllegalArgumentException("dataWords must not be negative: " + dataWords);
        }
        int totalWords = dataWords + 1;
        if (compactClassIndex >= 1
                && compactClassIndex <= MAX_COMPACT_CLASS_INDEX
                && totalWords <= MAX_SMALL_OBJECT_WORDS) {
            return new HeaderPlan(HeaderKind.SHORT, dataWords, compactClassIndex);
        }
        if (totalWords <= MAX_SMALL_OBJECT_WORDS) {
            return new HeaderPlan(HeaderKind.CLASS, dataWords, compactClassIndex);
        }
        return new HeaderPlan(HeaderKind.SIZE_AND_CLASS, dataWords, compactClassIndex);
    }

    /**
     * Builds a class object to compact index map from the live compact class
     * table. Entries are one-based in the image format; duplicate classes keep
     * the smallest index. Missing or malformed tables yield an empty map so the
     * writer falls back to class/size-and-class headers instead of failing.
     */
    static Map<SqueakObject, Integer> compactClassIndexes(
            SqueakObject specialObjectsArray, SqueakObject nilObject) {
        IdentityHashMap<SqueakObject, Integer> indexes = new IdentityHashMap<>();
        Object[] specialPointers = specialObjectsArray == null
                ? null : specialObjectsArray.pointers;
        if (specialPointers == null
                || Squeak.splOb_CompactClasses >= specialPointers.length) {
            return indexes;
        }
        Object compactArray = specialPointers[Squeak.splOb_CompactClasses];
        if (!(compactArray instanceof SqueakObject)) {
            return indexes;
        }
        Object[] classes = ((SqueakObject) compactArray).pointers;
        if (classes == null) {
            return indexes;
        }
        int count = Math.min(MAX_COMPACT_CLASS_INDEX, classes.length);
        for (int i = 0; i < count; i++) {
            Object entry = classes[i];
            if (!(entry instanceof SqueakObject)) {
                continue;
            }
            SqueakObject classObject = (SqueakObject) entry;
            if (classObject == nilObject) {
                continue; // unused compact slot
            }
            indexes.putIfAbsent(classObject, i + 1);
        }
        return indexes;
    }
}
