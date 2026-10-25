package org.dodotalk;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StarterTest {

    @AfterEach
    void resetBootArgs() {
        Starter.BOOT_ARGS = null;
    }

    @Test
    void nullArgumentsMeanNoImage() {
        Starter.BOOT_ARGS = null;
        assertNull(Starter.requestedImagePath());
    }

    @Test
    void emptyArgumentsMeanNoImage() {
        Starter.BOOT_ARGS = new String[0];
        assertNull(Starter.requestedImagePath());
    }

    @Test
    void singleArgumentIsImagePath() {
        Starter.BOOT_ARGS = new String[]{"/tmp/test.image.gz"};
        assertEquals("/tmp/test.image.gz", Starter.requestedImagePath());
    }

    @Test
    void imageFlagUsesFollowingArgument() {
        Starter.BOOT_ARGS = new String[]{"-image", "/tmp/test.image.gz"};
        assertEquals("/tmp/test.image.gz", Starter.requestedImagePath());
    }

    @Test
    void changesFileFollowsSqueakNaming(@TempDir Path directory) {
        assertEquals(directory.resolve("dodotalk.changes").toFile().getAbsoluteFile(),
                Starter.changesFileFor(directory.resolve("dodotalk.image.gz").toFile()));
        assertEquals(directory.resolve("custom.changes").toFile().getAbsoluteFile(),
                Starter.changesFileFor(directory.resolve("custom.image").toFile()));
    }

    @Test
    void validateImageFileAcceptsGzipImage(@TempDir Path directory) throws Exception {
        Path image = directory.resolve("test.image.gz");
        Files.write(image, new byte[]{0x1f, (byte) 0x8b});

        assertEquals(image.toFile().getAbsoluteFile(),
                Starter.validateImageFile(image.toFile()));
    }

    @Test
    void validateImageFileRejectsMissingFile(@TempDir Path directory) {
        assertThrows(IOException.class,
                () -> Starter.validateImageFile(directory.resolve("missing.image.gz").toFile()));
    }

    @Test
    void validateImageFileRejectsRawImage(@TempDir Path directory) throws Exception {
        Path image = directory.resolve("test.image");
        Files.write(image, new byte[]{0});

        IOException failure = assertThrows(IOException.class,
                () -> Starter.validateImageFile(image.toFile()));
        assertEquals("Only gzip-compressed .image.gz images are supported",
                failure.getMessage());
    }

    @Test
    void validateImageFileRejectsDirectory(@TempDir Path directory) {
        assertThrows(IOException.class,
                () -> Starter.validateImageFile(directory.toFile()));
    }

    @Test
    void validateImageFileRejectsNull() {
        assertThrows(IOException.class, () -> Starter.validateImageFile(null));
    }
}
