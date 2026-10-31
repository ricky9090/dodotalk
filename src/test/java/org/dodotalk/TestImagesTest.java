package org.dodotalk;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestImagesTest {

    @TempDir
    Path tempDir;

    @Test
    void findsGzipBeforeRaw() throws IOException {
        Files.writeString(tempDir.resolve("squeak22.image"), "raw");
        Files.writeString(tempDir.resolve("squeak22.image.gz"), "gzip");

        File found = TestImages.findImage(tempDir.toFile(), "squeak22");

        assertEquals(tempDir.resolve("squeak22.image.gz").toFile().getAbsoluteFile(),
                found.getAbsoluteFile());
    }

    @Test
    void acceptsOfficialFullImageName() throws IOException {
        Files.writeString(tempDir.resolve("Squeak2.2.image.gz"), "gzip");

        File found = TestImages.findImage(tempDir.toFile(), "squeak22");

        assertEquals(tempDir.resolve("Squeak2.2.image.gz").toFile().getAbsoluteFile(),
                found.getAbsoluteFile());
    }

    @Test
    void findsRawImageWhenGzipIsAbsent() throws IOException {
        Files.writeString(tempDir.resolve("squeak22.image"), "raw");

        File found = TestImages.findImage(tempDir.toFile(), "squeak22");

        assertEquals(tempDir.resolve("squeak22.image").toFile().getAbsoluteFile(),
                found.getAbsoluteFile());
    }

    @Test
    void returnsNullWhenNoCandidateExists() {
        assertNull(TestImages.findImage(tempDir.toFile(), "mini"));
    }

    @Test
    void findsImageInConfiguredDirectory() throws IOException {
        Path configured = Files.createDirectory(tempDir.resolve("configured"));
        Path image = Files.writeString(configured.resolve("mini.image.gz"), "gzip");
        String previous = System.getProperty(TestImages.IMAGE_DIR_PROPERTY);
        try {
            System.setProperty(TestImages.IMAGE_DIR_PROPERTY, configured.toString());
            assertEquals(image.toFile().getAbsoluteFile(),
                    TestImages.findDefaultImage("mini").getAbsoluteFile());
        } finally {
            if (previous == null) {
                System.clearProperty(TestImages.IMAGE_DIR_PROPERTY);
            } else {
                System.setProperty(TestImages.IMAGE_DIR_PROPERTY, previous);
            }
        }
    }

    @Test
    void defaultDirectoriesIncludeSiblingButNotProjectTemp() {
        String previous = System.getProperty(TestImages.IMAGE_DIR_PROPERTY);
        System.clearProperty(TestImages.IMAGE_DIR_PROPERTY);
        try {
            Assumptions.assumeTrue(System.getenv(TestImages.IMAGE_DIR_ENV) == null,
                    "JSQUEAK_TEST_IMAGES_DIR would override the default directory order");

            File root = TestImages.projectRoot();
            List<File> directories = TestImages.defaultImageDirectories();
            File sibling = new File(root.getParentFile(),
                    TestImages.DEFAULT_IMAGE_DIRECTORY).getAbsoluteFile();
            File projectTemp = new File(root,
                    TestImages.DEFAULT_IMAGE_DIRECTORY).getAbsoluteFile();

            assertTrue(directories.contains(sibling));
            assertFalse(directories.contains(projectTemp));
        } finally {
            if (previous == null) {
                System.clearProperty(TestImages.IMAGE_DIR_PROPERTY);
            } else {
                System.setProperty(TestImages.IMAGE_DIR_PROPERTY, previous);
            }
        }
    }

    @Test
    void fullImageAliasesKeepPriorityOrder() {
        assertEquals(List.of("squeak22", "Squeak2.2"), TestImages.aliasesFor("squeak22"));
        assertEquals(List.of("mini"), TestImages.aliasesFor("mini"));
    }
}
