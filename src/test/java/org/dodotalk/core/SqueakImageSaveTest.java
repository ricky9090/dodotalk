package org.dodotalk.core;

import org.dodotalk.TestImages;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for atomic image saving and snapshot-path handling.
 * A failed save must never destroy the previous image.
 */
class SqueakImageSaveTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("saving replaces the image atomically and keeps the previous file as .bak")
    void saveKeepsBackup() throws Exception {
        Path target = tempDir.resolve("saved.image.gz");
        Files.copy(TestImages.miniImage().toPath(), target);
        byte[] previous = Files.readAllBytes(target);

        SqueakImage image = new SqueakImage(target.toFile());
        new SqueakVM(image);
        image.save(target.toFile());

        assertEquals(target.toAbsolutePath().toString(), image.imageFile().getAbsolutePath());
        assertEquals(tempDir.resolve("saved.image").toAbsolutePath().toString(), image.imageName());
        assertTrue(Files.size(target) > 0, "saved image must not be empty");

        Path backup = tempDir.resolve("saved.image.gz.bak");
        assertTrue(Files.exists(backup), "the previous image must be backed up");
        assertArrayEquals(previous, Files.readAllBytes(backup),
                "the backup must contain the pre-save image");

        SqueakImage reloaded = new SqueakImage(target.toFile());
        assertNotNull(reloaded.specialObjectsArray);
    }

    @Test
    @DisplayName("a failed write leaves the original image untouched and removes the temp file")
    void failedSaveLeavesOriginalIntact() throws Exception {
        Path target = tempDir.resolve("broken.image.gz");
        Files.copy(TestImages.miniImage().toPath(), target);
        byte[] previous = Files.readAllBytes(target);

        SqueakImage image = new SqueakImage(target.toFile());
        // Force writeImage() to fail before any backup or move is attempted.
        image.specialObjectsArray = null;

        assertThrows(IOException.class, () -> image.save(target.toFile()));

        assertArrayEquals(previous, Files.readAllBytes(target),
                "a failed save must not modify the original image");
        assertFalse(Files.exists(tempDir.resolve("broken.image.gz.bak")),
                "no backup should be created for a failed write");
        try (Stream<Path> entries = Files.list(tempDir)) {
            assertFalse(entries.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")),
                    "temporary files must be cleaned up");
        }
    }

    @Test
    @DisplayName("saving a new image does not create a backup")
    void saveWithoutPreviousImage() throws Exception {
        Path target = tempDir.resolve("fresh.image.gz");

        SqueakImage image = new SqueakImage(TestImages.miniImage());
        image.save(target.toFile());

        assertTrue(Files.exists(target));
        assertFalse(Files.exists(tempDir.resolve("fresh.image.gz.bak")));
    }

    @Test
    @DisplayName("a stream-loaded image snapshots to the default images directory")
    void streamImageUsesWritableSnapshotTarget() throws Exception {
        try (InputStream input = Files.newInputStream(TestImages.miniImage().toPath())) {
            SqueakImage image = new SqueakImage(input);

            assertEquals(SqueakImage.defaultSnapshotFile().getAbsoluteFile(),
                    image.imageFile().getAbsoluteFile());
            assertEquals(new File(System.getProperty("user.dir"), "images").getAbsoluteFile(),
                    SqueakImage.defaultSnapshotDirectory());
            assertEquals(SqueakImage.defaultSnapshotDirectory(),
                    image.imageFile().getAbsoluteFile().getParentFile());
            assertFalse(image.imageFile().getAbsolutePath().contains(
                            "build" + File.separator + "resources"),
                    "snapshots must not target the build output");

            SqueakVM vm = new SqueakVM(image);
            int savedSp = vm.sp;
            try {
                vm.push(SqueakVM.nilObj);
                assertTrue(SqueakVM.primHandler.doPrimitive(142, 0));
                assertEquals(SqueakImage.defaultSnapshotDirectory().getAbsolutePath()
                                + File.separator,
                        ((SqueakObject) vm.top()).asString(),
                        "vmPath must point at the runtime image directory");
            } finally {
                vm.sp = savedSp;
            }
        }
    }

    @Test
    @DisplayName("an explicit snapshot target is separate from the source image")
    void explicitSnapshotTarget() throws Exception {
        Path snapshot = tempDir.resolve("snapshot.image.gz");

        SqueakImage image = new SqueakImage(TestImages.miniImage(), snapshot.toFile());

        assertEquals(snapshot.toAbsolutePath().toString(), image.imageFile().getAbsolutePath());
        assertEquals(tempDir.resolve("snapshot.image").toAbsolutePath().toString(), image.imageName());
    }

    @Test
    @DisplayName("primitive 121 sets a raw .image save-as target")
    void primitive121SetsImageName() throws Exception {
        Path renamed = tempDir.resolve("renamed.image");
        SqueakImage image = new SqueakImage(
                TestImages.miniImage(), tempDir.resolve("old.image").toFile());
        SqueakVM vm = new SqueakVM(image);

        int savedSp = vm.sp;
        try {
            SqueakObject name = SqueakVM.primHandler.makeStString(renamed.toString());
            vm.push(SqueakVM.nilObj);
            vm.push(name);

            assertTrue(SqueakVM.primHandler.doPrimitive(121, 1));
            assertEquals(renamed.toAbsolutePath().toString(), SqueakVM.image.imageName());
            assertEquals(renamed.toAbsolutePath().toString(),
                    SqueakVM.image.imageFile().getAbsolutePath());

            image.save(image.imageFile());
            assertTrue(Files.isRegularFile(renamed));
            byte[] saved = Files.readAllBytes(renamed);
            assertFalse(saved.length >= 2 && (saved[0] & 0xFF) == 0x1f
                            && (saved[1] & 0xFF) == 0x8b,
                    "Save As .image targets must be raw, not gzip");
            assertNotNull(new SqueakImage(renamed.toFile()).specialObjectsArray,
                    "a raw image saved under the logical .image name must reload");
        } finally {
            vm.sp = savedSp;
        }
    }

    @Test
    @DisplayName("raw images load and save atomically with a .bak")
    void rawImageRoundTrip() throws Exception {
        Path raw = copyAsRaw(TestImages.miniImage().toPath(), tempDir.resolve("mini.image"));
        SqueakImage image = new SqueakImage(raw.toFile());
        new SqueakVM(image);

        assertEquals(raw.toAbsolutePath().toString(), image.imageFile().getAbsolutePath());
        image.save(raw.toFile());

        assertTrue(Files.size(raw) > 0, "saved raw image must not be empty");
        assertTrue(Files.exists(tempDir.resolve("mini.image.bak")),
                "raw saves must keep the previous image as .bak");
        assertNotNull(new SqueakImage(raw.toFile()).specialObjectsArray,
                "raw image must reload");
    }

    @Test
    @DisplayName("magic bytes decide gzip/raw regardless of the file extension")
    void magicBytesOverrideExtension() throws Exception {
        Path disguisedGzip = copyAsGzip(TestImages.miniImage().toPath(),
                tempDir.resolve("disguised.image"));
        assertNotNull(new SqueakImage(disguisedGzip.toFile()).specialObjectsArray,
                "gzip bytes named .image must load");

        Path disguisedRaw = copyAsRaw(TestImages.miniImage().toPath(),
                tempDir.resolve("disguised.image.gz"));
        assertNotNull(new SqueakImage(disguisedRaw.toFile()).specialObjectsArray,
                "raw bytes named .image.gz must load");
    }

    @Test
    @DisplayName("unreachable objects are not written into the saved image")
    void unreachableObjectsAreNotSaved() throws Exception {
        SqueakImage image = new SqueakImage(TestImages.miniImage());
        SqueakVM vm = new SqueakVM(image);

        String marker = "dodotalk-unreachable-marker-9c7f";
        SqueakObject stringClass = (SqueakObject) vm.getSpecialObject(
                Squeak.splOb_ClassString);
        SqueakObject unreachable = new SqueakObject(image, stringClass, 0, SqueakVM.nilObj);
        unreachable.bits = marker.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        Path target = tempDir.resolve("reachable.image");
        image.save(target.toFile());

        byte[] saved = Files.readAllBytes(target);
        assertFalse(indexOf(saved, marker.getBytes(
                        java.nio.charset.StandardCharsets.UTF_8)) >= 0,
                "unreachable objects must not be serialized");
    }

    @Test
    @DisplayName("raw saves start with the SqueakJS-compatible nil object")
    void rawSaveStartsWithNilObject() throws Exception {
        SqueakImage image = new SqueakImage(TestImages.miniImage());
        new SqueakVM(image);
        Path raw = tempDir.resolve("placeholder.image");
        image.save(raw.toFile());

        byte[] saved = Files.readAllBytes(raw);
        int firstHeader = java.nio.ByteBuffer.wrap(saved, 64, 4).getInt();
        assertEquals(Squeak.HeaderTypeClass, firstHeader & Squeak.HeaderTypeMask,
                "the first object must use a 2-word HeaderTypeClass header");
        int firstSize = java.nio.ByteBuffer.wrap(saved, 68, 4).getInt();
        assertEquals(1, (firstSize >>> 2) & 63,
                "the first object must be the zero-data nil object");
    }

    @Test
    @DisplayName("primitive snapshots mark the resumed stack top with true")
    void primitiveSnapshotMarksResumeResult() throws Exception {
        SqueakImage image = new SqueakImage(TestImages.miniImage());
        new SqueakVM(image);
        Path target = tempDir.resolve("resume.image");
        image.save(target.toFile(), true);

        SqueakVM reloaded = new SqueakVM(new SqueakImage(target.toFile()));
        assertEquals(SqueakVM.trueObj,
                reloaded.activeContext.getPointer(reloaded.sp),
                "the snapshot resume result must be true");
    }

    @Test
    @DisplayName("save preserves active process context when the running process holds nil")
    void savePreservesActiveProcessContext() throws Exception {
        SqueakImage image = new SqueakImage(TestImages.miniImage());
        SqueakVM vm = new SqueakVM(image);

        SqueakObject schedulerAssociation = vm.getSpecialObject(
                Squeak.splOb_SchedulerAssociation);
        SqueakObject scheduler = schedulerAssociation.getPointerNI(Squeak.Assn_value);
        SqueakObject activeProcess = scheduler.getPointerNI(Squeak.ProcSched_activeProcess);

        // A running process normally has nil here; this is the state that made
        // the saved image fail in loadInitialContext().
        activeProcess.setPointer(Squeak.Proc_suspendedContext, SqueakVM.nilObj);
        Path target = tempDir.resolve("running.image");
        image.save(target.toFile());

        assertEquals(SqueakVM.nilObj,
                activeProcess.getPointer(Squeak.Proc_suspendedContext),
                "save must restore the live process state");

        SqueakVM reloaded = new SqueakVM(new SqueakImage(target.toFile()));
        SqueakObject reloadedScheduler = reloaded.getSpecialObject(
                        Squeak.splOb_SchedulerAssociation)
                .getPointerNI(Squeak.Assn_value);
        SqueakObject reloadedProcess = reloadedScheduler
                .getPointerNI(Squeak.ProcSched_activeProcess);
        SqueakObject context = reloadedProcess
                .getPointerNI(Squeak.Proc_suspendedContext);

        assertNotSame(SqueakVM.nilObj, context,
                "the saved active process must have a current context");
        assertTrue(context.pointersSize() > Squeak.Context_method,
                "the reloaded active context must be a real context object");
    }

    private static Path copyAsRaw(Path source, Path target) throws IOException {
        if (!isGzip(source)) {
            Files.copy(source, target);
            return target;
        }
        try (InputStream input = new GZIPInputStream(Files.newInputStream(source))) {
            Files.copy(input, target);
        }
        return target;
    }

    private static Path copyAsGzip(Path source, Path target) throws IOException {
        if (isGzip(source)) {
            Files.copy(source, target);
            return target;
        }
        try (InputStream input = Files.newInputStream(source);
             OutputStream output = new GZIPOutputStream(Files.newOutputStream(target))) {
            input.transferTo(output);
        }
        return target;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static boolean isGzip(Path source) throws IOException {
        try (InputStream input = Files.newInputStream(source)) {
            int first = input.read();
            int second = input.read();
            return first == 0x1f && second == 0x8b;
        }
    }
}
