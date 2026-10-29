package org.dodotalk;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void sourcesFileLivesNextToImage(@TempDir Path directory) {
        assertEquals(directory.resolve("SqueakV2.sources").toFile().getAbsoluteFile(),
                Starter.sourcesFileFor(directory.resolve("dodotalk.image.gz").toFile()));
    }

    @Test
    void missingSupportFilesProduceWarnings(@TempDir Path directory) throws Exception {
        Path image = directory.resolve("dodotalk.image.gz");
        Files.write(image, new byte[]{0x1f, (byte) 0x8b});

        List<String> warnings = Starter.supportFileWarnings(image.toFile());

        assertEquals(2, warnings.size());
        assertTrue(warnings.get(0).contains("SqueakV2.sources"), warnings.toString());
        assertTrue(warnings.get(1).contains("dodotalk.changes"), warnings.toString());
    }

    @Test
    void presentSupportFilesProduceNoWarnings(@TempDir Path directory) throws Exception {
        Path image = directory.resolve("dodotalk.image.gz");
        Files.write(image, new byte[]{0x1f, (byte) 0x8b});
        Files.write(directory.resolve("SqueakV2.sources"), new byte[0]);
        Files.write(directory.resolve("dodotalk.changes"), new byte[0]);

        assertTrue(Starter.supportFileWarnings(image.toFile()).isEmpty());
    }

    @Test
    void nullImageProducesNoWarnings() {
        assertTrue(Starter.supportFileWarnings(null).isEmpty());
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
