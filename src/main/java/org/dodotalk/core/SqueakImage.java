/*
SqueakImage.java
Copyright (c) 2008  Daniel H. H. Ingalls, Sun Microsystems, Inc.  All rights reserved.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
*/

package org.dodotalk.core;

import org.dodotalk.SqueakConfig;
import org.dodotalk.uilts.SqueakLogger;
import org.dodotalk.uilts.ObjectUtils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * @author Daniel Ingalls
 * <p>
 * A SqueakImage represents the complete state of a running Squeak.
 * This implemenatation uses Java objects (see SqueakObject) for all Squeak objects,
 * with direct pointers between them.  Enumeration is supported by objectTable,
 * which points weakly to all objects.  SmallIntegers are modelled by Java Integers.
 * <p>
 * Some care is taken in reclaiming OT slots, to preserve the order of creation of objects,
 * as this matters for Squeak weak objects, should we ever support them.
 */

public class SqueakImage {
    private static final String DEFAULT_SNAPSHOT_NAME = "dodotalk.image.gz";
    private static final String DEFAULT_IMAGE_DIRECTORY = "images";

    /** Squeak image format version (Squeak 2.2). */
    private static final int IMAGE_VERSION = 6502;

    /** Standard Squeak 2.2 image header size. */
    private static final int IMAGE_HEADER_SIZE = 64;

    /**
     * Fallback object memory base address. It must not be zero (oop 0 marks an
     * unset pointer) and it must leave the low bits free for small integers.
     */
    private static final int FALLBACK_BASE_ADDRESS = 0x10000000;

    private SqueakVM vm;
    //private WeakReference[] objectTable;
    //private int otMaxUsed;
    //private int otMaxOld;
    //private int lastHash;
    //private int lastOTindex;

    private File imageFile;

    /**
     * Directory Squeak uses as {@code vmPath}. Sources and changes are looked
     * up relative to this directory; it is normally the directory containing
     * the selected image.
     */
    private File runtimeDirectory;

    /** Header fields preserved across a save/reload cycle. */
    private int oldBaseAddr = FALLBACK_BASE_ADDRESS;
    private int savedWindowSize = (640 << 16) | 480;
    private int fullScreenFlag;
    private int extraVMMemory;

    /** Generation assigned to the objects read from this image. */
    private int imageGeneration;

    // FIXME: Access this through a method
    SqueakObject specialObjectsArray;

    public SqueakImage(InputStream raw) throws IOException {
        this(raw, defaultSnapshotFile());
    }

    /**
     * Loads an image from a stream while directing snapshots to an explicit
     * writable file. This is mainly used by tests and by callers that do not
     * have a source path.
     */
    public SqueakImage(InputStream raw, File snapshotFile) throws IOException {
        imageFile = Objects.requireNonNull(snapshotFile, "snapshotFile");
        runtimeDirectory = parentOf(imageFile);
        loaded(raw);
    }

    public SqueakImage(File fn) throws IOException {
        this(fn, fn);
    }

    /**
     * Loads an image from {@code source} but saves future snapshots to
     * {@code snapshotFile}. {@code vmPath} follows the selected source image
     * so that a user-supplied {@code SqueakV2.sources} and changes file next
     * to the image are found.
     */
    public SqueakImage(File source, File snapshotFile) throws IOException {
        Objects.requireNonNull(source, "source");
        imageFile = Objects.requireNonNull(snapshotFile, "snapshotFile");
        runtimeDirectory = parentOf(source);
        loaded(source);
    }

    private static File parentOf(File file) {
        File parent = file.getAbsoluteFile().getParentFile();
        return parent == null ? new File(System.getProperty("user.dir")).getAbsoluteFile() : parent;
    }

    /**
     * @return the directory used as Squeak's {@code vmPath}
     */
    public File runtimeDirectory() {
        return runtimeDirectory;
    }

    /**
     * Fallback snapshot directory for stream-loaded images that have no source
     * path. User-selected images use {@link #runtimeDirectory()} instead.
     */
    public static File defaultSnapshotDirectory() {
        return new File(System.getProperty("user.dir"), DEFAULT_IMAGE_DIRECTORY).getAbsoluteFile();
    }

    /**
     * Default snapshot target for images loaded from a classpath resource.
     */
    public static File defaultSnapshotFile() {
        return new File(defaultSnapshotDirectory(), DEFAULT_SNAPSHOT_NAME).getAbsoluteFile();
    }

    /**
     * Writes an image atomically.
     * <p>
     * The physical target extension selects the container: {@code .gz} writes
     * gzip, every other target writes a raw Squeak image. Squeak's built-in
     * Save As always constructs {@code <name>.image}, so Save As creates raw
     * images; a gzip-loaded image keeps gzip when saved in place.
     * <p>
     * The new image is written to a temporary file in the target directory,
     * flushed to disk, then moved into place with an atomic rename. The old
     * image is copied to {@code <name>.bak} first, so a failed write never
     * destroys the last good snapshot.
     */
    public void save(File fn) throws IOException {
        save(fn, false);
    }

    /**
     * Writes an image. {@code markResume} is set by Primitive 97 so the saved
     * context has the boolean result that Squeak expects when the snapshot is
     * resumed; ordinary/test saves leave the current stack untouched.
     */
    void save(File fn, boolean markResume) throws IOException {
        Objects.requireNonNull(fn, "fn");

        Path target = fn.toPath().toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent == null) {
            parent = Paths.get(".").toAbsolutePath().normalize();
            target = parent.resolve(target);
        }
        Files.createDirectories(parent);

        Path temp = Files.createTempFile(parent, "dodotalk-save-", ".tmp");
        Path backup = target.resolveSibling(target.getFileName() + ".bak");
        boolean committed = false;

        SqueakLogger.log_I("Saving image to: " + target);
        try {
            if (isGzipName(target)) {
                writeCompressedImage(temp, markResume);
            } else {
                writeRawImage(temp, markResume);
            }

            if (Files.exists(target)) {
                if (!Files.isRegularFile(target)) {
                    throw new IOException("image target is not a regular file: " + target);
                }
                if (Files.size(target) > 0) {
                    Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
                    SqueakLogger.log_D("Previous image backed up to: " + backup);
                }
            }

            moveIntoPlace(temp, target);
            committed = true;
            imageFile = target.toFile();
            SqueakLogger.log_I("Image saved to: " + target);
        } finally {
            if (!committed) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException cleanupFailure) {
                    SqueakLogger.log_W("Failed to remove temporary image: " + temp, cleanupFailure);
                }
            }
        }
    }

    private void writeCompressedImage(Path target, boolean markResume) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(target.toFile());
             BufferedOutputStream bos = new BufferedOutputStream(fos);
             GZIPOutputStream gz = new GZIPOutputStream(bos);
             DataOutputStream ser = new DataOutputStream(gz)) {
            writeImage(ser, markResume);
            ser.flush();
            // Write the gzip trailer before forcing the bytes to disk.
            gz.finish();
            bos.flush();
            fos.getFD().sync();
        }
    }

    private void writeRawImage(Path target, boolean markResume) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(target.toFile());
             BufferedOutputStream bos = new BufferedOutputStream(fos);
             DataOutputStream ser = new DataOutputStream(bos)) {
            writeImage(ser, markResume);
            ser.flush();
            bos.flush();
            fos.getFD().sync();
        }
    }

    private static boolean isGzipName(Path target) {
        return target.getFileName() != null
                && target.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gz");
    }

    private static void moveIntoPlace(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            SqueakLogger.log_W("Atomic image move is not supported; using a regular move: " + e.getMessage());
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * @return the file that Primitive 97 snapshots to, which may differ from
     * the source image when booting from a bundled resource
     */
    public File imageFile() {
        return imageFile;
    }

    /**
     * Sets the logical image name used for subsequent snapshots.
     * <p>
     * Squeak passes a logical name such as {@code foobar.image}. The physical
     * target is exactly that name: Save As therefore writes a raw
     * {@code foobar.image}, while a gzip-loaded image saved in place keeps its
     * existing {@code .image.gz} target. {@link #imageName()} still answers the
     * logical name, keeping Squeak's changes-file derivation
     * (baseNameFor(imageName) + ".changes") stable.
     */
    void setImageName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("image name must not be blank");
        }
        imageFile = new File(name).getAbsoluteFile();
    }

    /**
     * Squeak derives the changes file name by replacing the last extension of
     * the image name with "changes". Images are stored gzip-compressed here, so
     * the ".gz" suffix must not become part of the logical image name.
     */
    String imageName() {
        String path = imageFile.getAbsolutePath();
        if (path.endsWith(".gz")) {
            return path.substring(0, path.length() - 3);
        }
        return path;
    }

    void bindVM(SqueakVM theVM) {
        vm = theVM;
    }

    private void loaded(InputStream input) throws IOException {
        BufferedInputStream fp = new BufferedInputStream(input);
        fp.mark(2);
        int first = fp.read();
        int second = fp.read();
        fp.reset();

        // The extension is only advisory. Accept raw images and gzip wrappers
        // regardless of whether the file is named .image or .image.gz.
        InputStream imageStream = (first == 0x1f && second == 0x8b)
                ? new GZIPInputStream(fp)
                : fp;
        DataInputStream ser = new DataInputStream(imageStream);
        readImage(ser);
    }

    private void loaded(File fn) throws IOException {
        try (FileInputStream unbuffered = new FileInputStream(fn)) {
            loaded(unbuffered);
        }
    }

    public short registerObject(SqueakObject obj) {
        return SqueakVM.objectMemory.registerObject(obj);
    }

    private void writeImage(DataOutput ser, boolean markResume) throws IOException {
        if (specialObjectsArray == null) {
            throw new IOException("cannot save an image without a special objects array");
        }

        // The active context only has its instruction and stack pointers stored in
        // the image when the VM switches contexts. Primitive 97 runs in the middle
        // of a context, so persist the current registers before serializing.
        storeCurrentRegisters();

        // While a process is running, its scheduler slot may contain nil for
        // suspendedContext. The snapshot must make the active context reachable
        // from the scheduler, otherwise loadInitialContext() starts from nil.
        // Set it only for the duration of serialization and restore it afterwards.
        SqueakObject activeProcess = activeProcess();
        Object previousSuspendedContext = null;
        boolean restoreActiveProcess = false;
        if (activeProcess != null && vm != null
                && vm.activeContext != null && vm.activeContext != SqueakVM.nilObj
                && activeProcess.pointersSize() > Squeak.Proc_suspendedContext) {
            previousSuspendedContext = activeProcess.getPointer(Squeak.Proc_suspendedContext);
            activeProcess.setPointer(Squeak.Proc_suspendedContext, vm.activeContext);
            restoreActiveProcess = true;
        }

        // Squeak's snapshot primitive returns true when the saved context is
        // resumed. The saved stack top is that result slot; without this,
        // snapshot:andQuit: sees a stale object and sends #not to it.
        Object previousStackTop = null;
        boolean restoreStackTop = false;
        if (markResume && vm != null && vm.activeContext != null
                && vm.activeContext != SqueakVM.nilObj
                && vm.activeContext.pointers != null
                && vm.sp >= 0 && vm.sp < vm.activeContext.pointers.length) {
            previousStackTop = vm.activeContext.pointers[vm.sp];
            vm.activeContext.pointers[vm.sp] = specialObjectsArray
                    .pointers[Squeak.splOb_TrueObject];
            restoreStackTop = true;
        }

        try {
            List<SqueakObject> objects = collectLiveObjects();

            IdentityHashMap<SqueakObject, Integer> dataWords =
                    new IdentityHashMap<>(objects.size() * 2);
            for (SqueakObject object : objects) {
                dataWords.put(object, objectWordCount(object));
            }

            SqueakObject nilObject = specialObject(Squeak.splOb_NilObject);
            List<SqueakObject> leading = leadingSpecialObjects(objects, dataWords);
            boolean placeholder = leading.isEmpty();
            if (!placeholder) {
                objects = moveLeadingObjectsToFront(objects, leading);
            }

            Map<SqueakObject, Integer> compactClassIndexes =
                    ImageHeaderPlanner.compactClassIndexes(specialObjectsArray, nilObject);
            IdentityHashMap<SqueakObject, ImageHeaderPlanner.HeaderPlan> plans =
                    new IdentityHashMap<>(objects.size() * 2);
            for (int i = 0; i < objects.size(); i++) {
                SqueakObject object = objects.get(i);
                int compactIndex = compactClassIndex(object, compactClassIndexes);
                ImageHeaderPlanner.HeaderPlan plan =
                        ImageHeaderPlanner.plan(dataWords.get(object), compactIndex);
                if (i < leading.size()) {
                    // SqueakJS looks up the first object at oldBaseAddr + 4.
                    // A two-word class header puts nil's oop exactly there.
                    plan = plan.asClassHeader();
                }
                plans.put(object, plan);
            }

            int baseAddr = imageBaseAddress();
            Map<SqueakObject, Integer> oops = new IdentityHashMap<>(objects.size() * 2);
            int endOfMemory = placeholder ? 8 : 0;
            for (SqueakObject object : objects) {
                ImageHeaderPlanner.HeaderPlan plan = plans.get(object);
                int headerWords = plan.kind().words();
                int oop = baseAddr + endOfMemory + (headerWords - 1) * 4;
                oops.put(object, oop);
                endOfMemory += headerWords * 4 + plan.dataWords() * 4;
            }

            Integer specialOop = oops.get(specialObjectsArray);
            if (specialOop == null) {
                throw new IOException("special objects array is not part of the live object graph");
            }

            ser.writeInt(IMAGE_VERSION);
            ser.writeInt(IMAGE_HEADER_SIZE);
            ser.writeInt(endOfMemory);
            ser.writeInt(baseAddr);
            ser.writeInt(specialOop);
            ser.writeInt(SqueakVM.objectMemory.getLastHash());
            ser.writeInt(savedWindowSize);
            ser.writeInt(fullScreenFlag);
            ser.writeInt(extraVMMemory);
            for (int i = 9 * 4; i < IMAGE_HEADER_SIZE; i++) {
                ser.writeByte(0);
            }

            if (placeholder) {
                writePlaceholder(ser, oops, nilObject, objects);
            }

            for (SqueakObject object : objects) {
                writeObject(ser, object, oops, plans.get(object));
            }
        } finally {
            if (restoreStackTop) {
                vm.activeContext.pointers[vm.sp] = previousStackTop;
            }
            if (restoreActiveProcess) {
                activeProcess.setPointer(Squeak.Proc_suspendedContext,
                        previousSuspendedContext);
            }
        }
    }

    /**
     * Returns the live special objects that should lead the image: {@code nil},
     * then {@code false} and {@code true} when they are usable. The first
     * object must use a two-word class header so its oop is
     * {@code oldBaseAddr + 4}; SqueakJS looks the first object up at that
     * address.
     */
    private List<SqueakObject> leadingSpecialObjects(
            List<SqueakObject> objects,
            IdentityHashMap<SqueakObject, Integer> dataWords) {
        SqueakObject nilObject = specialObject(Squeak.splOb_NilObject);
        if (!isUsableLeadingObject(nilObject, objects, dataWords)) {
            return List.of();
        }
        List<SqueakObject> leading = new ArrayList<>(3);
        leading.add(nilObject);
        addLeadingObject(leading, specialObject(Squeak.splOb_FalseObject),
                objects, dataWords);
        addLeadingObject(leading, specialObject(Squeak.splOb_TrueObject),
                objects, dataWords);
        return leading;
    }

    private void addLeadingObject(List<SqueakObject> leading,
                                  SqueakObject candidate,
                                  List<SqueakObject> objects,
                                  IdentityHashMap<SqueakObject, Integer> dataWords) {
        if (candidate == null || containsIdentity(leading, candidate)) {
            return;
        }
        if (isUsableLeadingObject(candidate, objects, dataWords)) {
            leading.add(candidate);
        }
    }

    private boolean isUsableLeadingObject(
            SqueakObject candidate,
            List<SqueakObject> objects,
            IdentityHashMap<SqueakObject, Integer> dataWords) {
        if (candidate == null || !containsIdentity(objects, candidate)) {
            return false;
        }
        Integer words = dataWords.get(candidate);
        if (words == null || words != 0) {
            return false;
        }
        return candidate.sqClass instanceof SqueakObject
                && containsIdentity(objects, (SqueakObject) candidate.sqClass);
    }

    private static boolean containsIdentity(List<SqueakObject> objects,
                                            SqueakObject candidate) {
        for (SqueakObject object : objects) {
            if (object == candidate) {
                return true;
            }
        }
        return false;
    }

    private static List<SqueakObject> moveLeadingObjectsToFront(
            List<SqueakObject> objects, List<SqueakObject> leading) {
        List<SqueakObject> ordered = new ArrayList<>(objects.size());
        IdentityHashMap<SqueakObject, Boolean> moved =
                new IdentityHashMap<>(leading.size() * 2);
        for (SqueakObject object : leading) {
            ordered.add(object);
            moved.put(object, Boolean.TRUE);
        }
        for (SqueakObject object : objects) {
            if (!moved.containsKey(object)) {
                ordered.add(object);
            }
        }
        return ordered;
    }

    private SqueakObject specialObject(int index) {
        Object[] pointers = specialObjectsArray.pointers;
        if (pointers == null || index < 0 || index >= pointers.length) {
            return null;
        }
        Object value = pointers[index];
        return value instanceof SqueakObject ? (SqueakObject) value : null;
    }

    private static int compactClassIndex(
            SqueakObject object, Map<SqueakObject, Integer> compactClassIndexes) {
        if (!(object.sqClass instanceof SqueakObject)) {
            return 0;
        }
        Integer index = compactClassIndexes.get((SqueakObject) object.sqClass);
        return index == null ? 0 : index;
    }

    private void writePlaceholder(DataOutput ser,
                                  Map<SqueakObject, Integer> oops,
                                  SqueakObject nilObject,
                                  List<SqueakObject> objects) throws IOException {
        Integer classOop = null;
        if (nilObject != null && nilObject.sqClass instanceof SqueakObject) {
            classOop = oops.get((SqueakObject) nilObject.sqClass);
        }
        if (classOop == null && !objects.isEmpty()
                && objects.get(0).sqClass instanceof SqueakObject) {
            classOop = oops.get((SqueakObject) objects.get(0).sqClass);
        }
        if (classOop == null || classOop < 32 || (classOop & 3) != 0) {
            throw new IOException("cannot write the image placeholder class");
        }
        ser.writeInt(Squeak.HeaderTypeClass + classOop);
        ser.writeInt(1 << 2); // one header word, zero data words
    }

    private SqueakObject activeProcess() {
        try {
            SqueakObject schedulerAssociation = specialObjectsArray
                    .getPointerNI(Squeak.splOb_SchedulerAssociation);
            SqueakObject scheduler = schedulerAssociation.getPointerNI(Squeak.Assn_value);
            return scheduler.getPointerNI(Squeak.ProcSched_activeProcess);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void storeCurrentRegisters() {
        if (vm == null || vm.activeContext == null || vm.activeContext == SqueakVM.nilObj
                || vm.method == null || vm.method == SqueakVM.nilObj
                || !(vm.method.bits instanceof byte[])) {
            return;
        }
        vm.storeContextRegisters();
    }

    /**
     * Collects the live object graph reachable from the special objects array
     * and the active context.
     * <p>
     * The object table also contains garbage and objects from older image
     * generations. Rooting every object in the table would drag dead processes
     * (including processes whose suspended context is nil) and other garbage
     * into the saved image, making it larger and leaving invalid process state
     * for another VM.
     */
    private List<SqueakObject> collectLiveObjects() {
        IdentityHashMap<SqueakObject, Boolean> seen = new IdentityHashMap<>();
        ArrayDeque<SqueakObject> pending = new ArrayDeque<>();

        addObject(pending, seen, specialObjectsArray);
        if (vm != null) {
            addObject(pending, seen, vm.activeContext);
        }

        List<SqueakObject> objects = new ArrayList<>(seen.size());
        while (!pending.isEmpty()) {
            SqueakObject object = pending.poll();
            objects.add(object);

            addObject(pending, seen, object.sqClass);
            Object[] pointers = object.pointers;
            if (pointers != null) {
                for (Object pointer : pointers) {
                    addObject(pending, seen, pointer);
                }
            }
        }
        return objects;
    }

    private static void addObject(ArrayDeque<SqueakObject> pending,
                                  IdentityHashMap<SqueakObject, Boolean> seen,
                                  Object candidate) {
        if (!(candidate instanceof SqueakObject)) {
            return;
        }
        SqueakObject object = (SqueakObject) candidate;
        if (seen.put(object, Boolean.TRUE) == null) {
            pending.add(object);
        }
    }

    private int imageBaseAddress() {
        // The image base address becomes the oop of the first object, so it has
        // to be non-zero and word aligned.
        if (oldBaseAddr >= 32 && (oldBaseAddr & 3) == 0) {
            return oldBaseAddr;
        }
        return FALLBACK_BASE_ADDRESS;
    }

    private int objectWordCount(SqueakObject object) throws IOException {
        int format = object.format;
        int pointerWords = object.pointers == null ? 0 : object.pointers.length;

        if (format < 5) {
            return pointerWords;
        }
        if (format == 6) {
            if (object.bits == null) {
                return 0;
            }
            if (object.bits instanceof Double) {
                return 2;
            }
            if (object.bits instanceof int[]) {
                return ((int[]) object.bits).length;
            }
            throw new IOException("format 6 object has " + object.bits.getClass()
                    + " data instead of words");
        }
        if (format >= 8) {
            int byteWords = byteWordCount(object);
            return format >= 12 ? pointerWords + byteWords : byteWords;
        }

        // Formats 5 and 7 are unused, but preserve any word data verbatim.
        if (object.bits == null) {
            return 0;
        }
        if (object.bits instanceof int[]) {
            return ((int[]) object.bits).length;
        }
        throw new IOException("format " + format + " object has "
                + object.bits.getClass() + " data instead of words");
    }

    private int byteWordCount(SqueakObject object) throws IOException {
        if (object.bits == null) {
            return 0;
        }
        if (!(object.bits instanceof byte[])) {
            throw new IOException("byte object has " + object.bits.getClass()
                    + " data instead of bytes");
        }
        return (((byte[]) object.bits).length + 3) / 4;
    }

    private int normalizedFormat(SqueakObject object) throws IOException {
        int format = object.format;
        if (format >= 8) {
            int byteLength = object.bits == null ? 0 : ((byte[]) object.bits).length;
            int byteWords = (byteLength + 3) / 4;
            int padding = byteWords * 4 - byteLength;
            return (format >= 12 ? 12 : 8) + padding;
        }
        return format;
    }

    private void writeObject(DataOutput ser, SqueakObject object,
                             Map<SqueakObject, Integer> oops,
                             ImageHeaderPlanner.HeaderPlan plan) throws IOException {
        int dataWords = plan.dataWords();
        int size = plan.totalWords();
        int format = normalizedFormat(object);
        int hash = object.hash & 0xFFF;
        int formatAndHash = ((format & 0xF) << 8) | (hash << 17);

        if (plan.kind() != ImageHeaderPlanner.HeaderKind.SIZE_AND_CLASS
                && size > ImageHeaderPlanner.MAX_SMALL_OBJECT_WORDS) {
            throw new IOException("object size " + size
                    + " does not fit a short or class header");
        }

        switch (plan.kind()) {
            case SHORT:
                if (plan.compactClassIndex() < 1
                        || plan.compactClassIndex() > ImageHeaderPlanner.MAX_COMPACT_CLASS_INDEX) {
                    throw new IOException("invalid compact class index: "
                            + plan.compactClassIndex());
                }
                ser.writeInt(formatAndHash
                        | (plan.compactClassIndex() << 12)
                        | (size << 2)
                        | Squeak.HeaderTypeShort);
                break;
            case CLASS: {
                int classOop = checkedClassOop(object, oops);
                ser.writeInt(classOop + Squeak.HeaderTypeClass);
                ser.writeInt(formatAndHash | (size << 2));
                break;
            }
            case SIZE_AND_CLASS: {
                int classOop = checkedClassOop(object, oops);
                ser.writeInt((size << 2) | Squeak.HeaderTypeSizeAndClass);
                ser.writeInt(classOop);
                ser.writeInt(formatAndHash);
                break;
            }
            default:
                throw new IOException("unknown object header kind: " + plan.kind());
        }

        if (format < 5) {
            writePointers(ser, object, oops);
        } else if (format == 6 || format == 5 || format == 7) {
            writeWords(ser, object);
        } else if (format < 12) {
            writeBytes(ser, object);
        } else {
            writePointers(ser, object, oops);
            writeBytes(ser, object);
        }
    }

    private int checkedClassOop(SqueakObject object,
                                Map<SqueakObject, Integer> oops) throws IOException {
        int classOop = oopOf(object.sqClass, oops);
        if (classOop < 32) {
            // Values 1..31 are compact class indices, not oops.
            throw new IOException("class oop " + classOop + " is in the compact class range");
        }
        if ((classOop & 3) != 0) {
            throw new IOException("class oop " + classOop + " is not word aligned");
        }
        return classOop;
    }

    private int oopOf(Object value, Map<SqueakObject, Integer> oops) throws IOException {
        if (!(value instanceof SqueakObject)) {
            throw new IOException("object has no class object: " + value);
        }
        Integer oop = oops.get(value);
        if (oop == null) {
            throw new IOException("object is not part of the saved image: " + value);
        }
        return oop;
    }

    private void writePointers(DataOutput ser, SqueakObject object,
                               Map<SqueakObject, Integer> oops) throws IOException {
        if (object.pointers == null) {
            return;
        }
        for (Object pointer : object.pointers) {
            ser.writeInt(encodePointer(pointer, oops));
        }
    }

    private int encodePointer(Object pointer, Map<SqueakObject, Integer> oops) throws IOException {
        if (pointer == null) {
            return 0; // decoded as null on the next load
        }
        if (pointer instanceof Integer) {
            return (((Integer) pointer) << 1) | 1;
        }
        return oopOf(pointer, oops);
    }

    private void writeWords(DataOutput ser, SqueakObject object) throws IOException {
        if (object.bits instanceof Double) {
            long bits = Double.doubleToRawLongBits((Double) object.bits);
            ser.writeInt((int) (bits >>> 32));
            ser.writeInt((int) bits);
        } else if (object.bits instanceof int[]) {
            for (int word : (int[]) object.bits) {
                ser.writeInt(word);
            }
        } else if (object.bits != null) {
            throw new IOException("format 6 object has " + object.bits.getClass()
                    + " data instead of words");
        }
    }

    private void writeBytes(DataOutput ser, SqueakObject object) throws IOException {
        if (object.bits == null) {
            return;
        }
        if (!(object.bits instanceof byte[])) {
            throw new IOException("byte object has " + object.bits.getClass()
                    + " data instead of bytes");
        }
        byte[] bytes = (byte[]) object.bits;
        int fullWords = bytes.length / 4;
        for (int i = 0; i < fullWords; i++) {
            int offset = i * 4;
            ser.writeInt(((bytes[offset] & 0xFF) << 24)
                    | ((bytes[offset + 1] & 0xFF) << 16)
                    | ((bytes[offset + 2] & 0xFF) << 8)
                    | (bytes[offset + 3] & 0xFF));
        }
        int remainder = bytes.length - fullWords * 4;
        if (remainder > 0) {
            int word = 0;
            for (int i = 0; i < remainder; i++) {
                word |= (bytes[fullWords * 4 + i] & 0xFF) << (8 * (3 - i));
            }
            ser.writeInt(word);
        }
    }

    private void readImage(DataInput in) throws IOException {
        //System.err.println("-3.0" + Double.doubleToLongBits(-3.0d));
        SqueakLogger.log_I("Start reading at " + System.currentTimeMillis());
        try {
            readImageContent(in);
        } catch (EOFException e) {
            throw new SqueakImageFormatException("image file is truncated", e);
        }
    }

    private void readImageContent(DataInput in) throws IOException {
        Hashtable<Object, Object> oopMap = new Hashtable<>(30000);
        int generation = SqueakVM.objectMemory.nextImageGeneration();
        imageGeneration = generation;
        boolean doSwap = false;
        int version = intFromInputSwapped(in, doSwap);
        if (version != 6502) {
            version = swapInt(version);
            if (version != 6502)
                throw new SqueakImageFormatException("bad image version: " + version);
            doSwap = true;
        }
        int headerSize = intFromInputSwapped(in, doSwap);
        int endOfMemory = intFromInputSwapped(in, doSwap); //first unused location in heap
        int imageBaseAddr = intFromInputSwapped(in, doSwap); //object memory base address of image
        int specialObjectsOopInt = intFromInputSwapped(in, doSwap); //oop of array of special oops
        SqueakVM.objectMemory.setLastHash(intFromInputSwapped(in, doSwap)); //Should be loaded from, and saved to the image header
        int imageSavedWindowSize = intFromInputSwapped(in, doSwap);
        int imageFullScreenFlag = intFromInputSwapped(in, doSwap);
        int imageExtraVMMemory = intFromInputSwapped(in, doSwap);
        if (headerSize < 9 * 4) {
            throw new SqueakImageFormatException("bad image header size: " + headerSize);
        }
        if (endOfMemory <= 0) {
            throw new SqueakImageFormatException("bad end of memory: " + endOfMemory);
        }
        in.skipBytes(headerSize - (9 * 4)); //skip to end of header

        oldBaseAddr = imageBaseAddr;
        savedWindowSize = imageSavedWindowSize;
        fullScreenFlag = imageFullScreenFlag;
        extraVMMemory = imageExtraVMMemory;

        for (int i = 0; i < endOfMemory; ) {
            int nWords = 0;
            int classInt = 0;
            int[] data;
            int format = 0;
            int hash = 0;
            int header = intFromInputSwapped(in, doSwap);
            switch (header & Squeak.HeaderTypeMask) {
                case Squeak.HeaderTypeSizeAndClass:
                    nWords = header >> 2;
                    classInt = intFromInputSwapped(in, doSwap) - Squeak.HeaderTypeSizeAndClass;
                    header = intFromInputSwapped(in, doSwap);
                    i = i + 12;
                    break;
                case Squeak.HeaderTypeClass:
                    classInt = header - Squeak.HeaderTypeClass;
                    header = intFromInputSwapped(in, doSwap);
                    i = i + 8;
                    nWords = (header >> 2) & 63;
                    break;
                case Squeak.HeaderTypeFree:
                    throw new SqueakImageFormatException("unexpected free block at offset " + i);
                case Squeak.HeaderTypeShort:
                    i = i + 4;
                    classInt = (header >> 12) & 31; //compact class index
                    //Note classInt<32 implies compact class index
                    nWords = (header >> 2) & 63;
                    break;
                default:
                    throw new SqueakImageFormatException(
                            "unknown object header type at offset " + i + ": " + (header & Squeak.HeaderTypeMask));
            }
            int baseAddr = i - 4; //0-rel byte oop of this object (base header)
            nWords--;  //length includes base header which we have already read
            format = ((header >> 8) & 15);
            hash = ((header >> 17) & 4095);
            if (nWords < 0) {
                throw new SqueakImageFormatException(
                        "object at offset " + baseAddr + " has a negative size: " + nWords);
            }
            if (nWords > (endOfMemory / 4) + 1) {
                throw new SqueakImageFormatException("object at offset " + baseAddr
                        + " claims " + nWords + " words, which exceeds the image size");
            }

            // Note classInt and data are just raw data; no base addr adjustment and no Int conversion
            data = new int[nWords];
            for (int j = 0; j < nWords; j++)
                data[j] = intFromInputSwapped(in, doSwap);
            i = i + (nWords * 4);

            SqueakObject javaObject = new SqueakObject(classInt, (short) format, (short) hash, data);
            javaObject.imageGeneration = generation;
            SqueakVM.objectMemory.registerObject(javaObject);
            //oopMap is from old oops to new objects
            //Why can't we use ints as keys??...
            oopMap.put(baseAddr + oldBaseAddr, javaObject);
        }

        //Temp version of spl objs needed for makeCCArray; not a good object yet
        specialObjectsArray = (SqueakObject) (oopMap.get(specialObjectsOopInt));
        if (specialObjectsArray == null) {
            throw new SqueakImageFormatException(
                    "special objects array not found in image (oop " + specialObjectsOopInt + ")");
        }
        Integer[] ccArray = makeCCArray(oopMap, specialObjectsArray);
        int oldOop = specialObjectsArray.oldOopAt(Squeak.splOb_ClassFloat);
        SqueakObject floatClass = ((SqueakObject) oopMap.get(oldOop));
        if (floatClass == null) {
            throw new SqueakImageFormatException(
                    "Float class not found in image (oop " + oldOop + ")");
        }

        long installBeginTime = System.currentTimeMillis();
        SqueakLogger.log_I("Start installing at " + installBeginTime);
        SqueakVM.objectMemory.installObjects(oopMap, ccArray, floatClass, generation);
        long installEndTime = System.currentTimeMillis();
        SqueakLogger.log_I("Done  installing at " + installEndTime);
        long installCostTime = installEndTime - installBeginTime;
        SqueakLogger.log_I("Installing cost time: " + installCostTime + "ms");

        if (SqueakConfig.ENABLE_OBJECT_DUMP) {
            ObjectUtils.dumpObjOfImage();
        }
        //Proper version of spl objs -- it's a good object
        specialObjectsArray = (SqueakObject) (oopMap.get(specialObjectsOopInt));

    }

    private int intFromInputSwapped(DataInput in, boolean doSwap) throws IOException {
        // Return an int from stream 'in', swizzled if doSwap is true
        int tmp = in.readInt();
        if (doSwap)
            return swapInt(tmp);
        else
            return tmp;
    }

    private int swapInt(int toSwap) {
        // Return an int with byte order reversed
        int incoming = toSwap;
        int outgoing = 0;
        for (int i = 0; i < 4; i++) {
            int lowByte = incoming & 255;
            // FIXME use bit op instead of add lowByte
            outgoing = (outgoing << 8) | lowByte;
            incoming = incoming >> 8;
        }
        return outgoing;
    }

    private Integer[] makeCCArray(Hashtable<Object, Object> oopMap, SqueakObject splObs)
            throws SqueakImageFormatException {
        //Makes an aray of the complact classes as oldOops (still need to be mapped)
        int oldOop = splObs.oldOopAt(Squeak.splOb_CompactClasses);
        SqueakObject compactClassesArray = ((SqueakObject) oopMap.get(oldOop));
        if (compactClassesArray == null) {
            throw new SqueakImageFormatException(
                    "compact classes array not found in image (oop " + oldOop + ")");
        }
        if (!(compactClassesArray.bits instanceof int[]) || ((int[]) compactClassesArray.bits).length < 31) {
            throw new SqueakImageFormatException("compact classes array is malformed");
        }
        Integer[] ccArray = new Integer[31];
        for (int i = 0; i < 31; i++) {
            ccArray[i] = compactClassesArray.oldOopAt(i);
        }
        return ccArray;
    }
}
