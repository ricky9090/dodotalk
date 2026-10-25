package org.dodotalk.core;

import org.dodotalk.TestImages;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    @DisplayName("primitive 121 sets the image name and the save-as target")
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
            Path physical = tempDir.resolve("renamed.image.gz");
            assertEquals(renamed.toAbsolutePath().toString(), SqueakVM.image.imageName());
            assertEquals(physical.toAbsolutePath().toString(),
                    SqueakVM.image.imageFile().getAbsolutePath());

            image.save(image.imageFile());
            assertTrue(Files.isRegularFile(physical));
            assertFalse(Files.exists(renamed),
                    "the logical .image name must not contain gzip bytes");
            assertNotNull(new SqueakImage(physical.toFile()).specialObjectsArray,
                    "a gzip image saved under the logical .image name must reload");
        } finally {
            vm.sp = savedSp;
        }
    }
}
