package org.dodotalk.core;

import org.dodotalk.uilts.SqueakLogger;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Hashtable;

/**
 * ObjectMemory for memory management.
 * Use ArrayList instead of Array for objectTable
 * The memory usage and GC performance are almost the same as the old implementation
 */
public class ObjectMemory {

    private final static int OTMinSize = 120000;
    private final static int OTMaxSize = 640000;  // not used
    private final static int OTGrowSize = 10000;

    private SqueakVM vm;

    private final ArrayList<WeakReference<Object>> objectTable;

    private int currentCapacity;
    private int lastObjectIndex;

    private int lastHash;

    private int nullCount = 0;

    private int imageGeneration = 0;

    public ObjectMemory() {
        currentCapacity = OTMinSize;
        this.objectTable = new ArrayList<>(currentCapacity);
    }

    /**
     * The object memory is created while {@code SqueakVM} is still being
     * initialized, so it cannot read {@code SqueakVM.INSTANCE} in its
     * constructor. The VM binds itself here once its state is ready.
     */
    void bindVM(SqueakVM vm) {
        this.vm = vm;
    }

    SqueakVM getVM() {
        return vm;
    }

    public int getLastHash() {
        return lastHash;
    }

    public void setLastHash(int lastHash) {
        this.lastHash = lastHash;
    }

    public int getObjectTableLength() {
        return objectTable.size();
    }

    public WeakReference<Object> getObjectAt(int index) {
        if (objectTable.size() <= index) {
            return null;
        }
        return objectTable.get(index);
    }

    public boolean bulkBecome(Object[] fromPointers, Object[] toPointers, boolean twoWay) {
        int n = fromPointers.length;
        Object p;
        Object ptr;
        Object[] body;
        Object mut;
        SqueakObject obj;
        if (n != toPointers.length)
            return false;
        Hashtable<Object, Object> mutations = new Hashtable<>(n * 4 * (twoWay ? 2 : 1));
        for (int i = 0; i < n; i++) {
            p = fromPointers[i];
            if (!(p instanceof SqueakObject))
                return false;  //non-objects in from array
            if (mutations.get(p) != null)
                return false; //repeated oops in from array
            else
                mutations.put(p, toPointers[i]);
        }
        if (twoWay) {
            for (int i = 0; i < n; i++) {
                p = toPointers[i];
                if (!(p instanceof SqueakObject))
                    return false;  //non-objects in to array
                if (mutations.get(p) != null)
                    return false; //repeated oops in to array
                else
                    mutations.put(p, fromPointers[i]);
            }
        }
        final int length = objectTable.size();
        for (int i = 0; i < length; i++) {
            // Now, for every object...
            obj = (SqueakObject) objectTable.get(i).get();
            if (obj != null) {
                // mutate the class
                mut = (SqueakObject) mutations.get(obj.sqClass);
                if (mut != null)
                    obj.sqClass = mut;
                if ((body = obj.pointers) != null) {
                    // and mutate body pointers
                    for (int j = 0; j < body.length; j++) {
                        ptr = body[j];
                        mut = mutations.get(ptr);
                        if (mut != null)
                            body[j] = mut;
                    }
                }
            }
        }
        return true;
    }

    //Enumeration...
    public SqueakObject nextInstance(int startingIndex, SqueakObject sqClass) {
        //if sqClass is null, then find next object, else find next instance of sqClass
        final int length = objectTable.size();
        int start = Math.max(0, startingIndex);
        for (int i = start; i < length; i++) {
            // For every object...
            SqueakObject obj = (SqueakObject) objectTable.get(i).get();
            if (obj != null && (sqClass == null | obj.sqClass == sqClass)) {
                lastObjectIndex = i; // save hint for next scan
                return obj;
            }
        }
        return SqueakVM.nilObj;  // Return nil if none found
    }

    public int otIndexOfObject(SqueakObject lastObj) {
        final int length = objectTable.size();

        // lastObjectIndex is only a hint. GC may compact objectTable after the
        // hint was recorded, so never use it without a bounds check.
        if (lastObjectIndex >= 0 && lastObjectIndex < length) {
            SqueakObject obj = (SqueakObject) objectTable.get(lastObjectIndex).get();
            if (obj == lastObj) {
                return lastObjectIndex;
            }
        }
        // Alas no; have to find it again...
        for (int i = 0; i < length; i++) {
            SqueakObject obj = (SqueakObject) objectTable.get(i).get();
            if (obj == lastObj) {
                lastObjectIndex = i;
                return i;
            }
        }
        return -1;  // object is no longer in the table
    }

    public short registerObject(SqueakObject obj) {
        //All enumerable objects must be registered
        int currentSize = objectTable.size();
        if (currentSize + 1 > currentCapacity) {  // reach capacity
            performGC();
        }

        objectTable.add(new WeakReference<>(obj));
        lastHash = 13849 + (27181 * lastHash);
        return (short) (lastHash & 0xFFF);
    }

    private void performGC() {
        //SqueakLogger.log_D("freeSpace " + System.currentTimeMillis() + ", currentObjectCount: " + objectTable.size());
        for (int i = 0; i < 5; i++) {
            if (i == 2 && vm != null) {
                vm.clearCaches(); //only flush caches after two tries
            }
            partialGC();
            if (nullCount >= OTGrowSize) {
                return; // do not need increase capacity
            }
        }

        // Sigh -- really need more space...
        fullGC();
        objectTable.ensureCapacity(currentCapacity + OTGrowSize);
        currentCapacity += OTGrowSize;
    }

    public int partialGC() {
        System.gc();
        reclaimNullOTSlots();
        return spaceLeft();
    }

    int fullGC() {
        if (vm == null) {
            return spaceLeft();
        }
        vm.clearCaches();
        for (int i = 0; i < 5; i++) {
            partialGC();
        }
        return spaceLeft();
    }

    public int spaceLeft() {
        return (int) Math.min(Runtime.getRuntime().freeMemory(), (long) SqueakVM.maxSmallInt);
    }

    private int reclaimNullOTSlots() {
        // Java GC will null out slots in the weak Object Table.
        // Compact occupied slots in place (retaining order) in a single pass.
        nullCount = 0;
        int writePtr = 0;
        int size = objectTable.size();
        for (int readPtr = 0; readPtr < size; readPtr++) {
            WeakReference<Object> ref = objectTable.get(readPtr);
            if (ref != null && ref.get() != null) {
                objectTable.set(writePtr, ref);
                writePtr++;
            } else {
                nullCount++;
            }
        }
        if (writePtr < size) {
            objectTable.subList(writePtr, size).clear();
        }
        // Compaction changes indices, so any previous enumeration hint is stale.
        lastObjectIndex = -1;

        return objectTable.size();
    }

    public void installObjects(Hashtable<Object, Object> oopMap, Integer[] ccArray, SqueakObject floatClass,
                               int generation) throws SqueakImageFormatException {
        int failed = 0;
        for (int i = 0; i < objectTable.size(); i++) {
            SqueakObject t = (SqueakObject) objectTable.get(i).get();
            if (t == null) {
                continue; // the object was collected in the meantime
            }
            if (t.imageGeneration != generation) {
                // the object does not belong to the image that is being loaded
                continue;
            }
            try {
                t.install(oopMap, ccArray, floatClass);
            } catch (SqueakImageFormatException e) {
                // a malformed object means the image itself is broken: report it
                // instead of continuing with a half installed image
                throw new SqueakImageFormatException("cannot install object " + i
                        + " (format " + t.getFormat() + "): " + e.getMessage(), e);
            } catch (RuntimeException e) {
                failed++;
                if (failed <= 10) {
                    SqueakLogger.log_W("Failed to install object " + i
                            + " (format " + t.getFormat() + "): " + e);
                }
            }
        }
        if (failed > 0) {
            SqueakLogger.log_W("Failed to install " + failed + " objects of the image");
        }
    }

    /**
     * Starts a new image generation. Every object that is created while the
     * image is read belongs to this generation, so that a later image load does
     * not try to install the objects of an earlier (possibly broken) one.
     */
    public int nextImageGeneration() {
        return ++imageGeneration;
    }
}
