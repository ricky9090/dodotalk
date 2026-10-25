package org.dodotalk;

import org.junit.jupiter.api.Assumptions;

import java.io.File;

/**
 * Locates user-supplied Squeak images for integration tests.
 * <p>
 * The repository intentionally does not bundle a Squeak 2.2 image. Set one or
 * both environment variables to run the image-dependent tests:
 *
 * <pre>
 * JSQUEAK_TEST_MINI_IMAGE=/path/to/mini.image.gz
 * JSQUEAK_TEST_FULL_IMAGE=/path/to/squeak22.image.gz
 * </pre>
 *
 * Tests that need an image are skipped when the corresponding variable is
 * absent. This keeps {@code ./gradlew test} green in a clean checkout and
 * avoids redistributing non-MIT image assets.
 */
public final class TestImages {

    public static final String MINI_ENV = "JSQUEAK_TEST_MINI_IMAGE";
    public static final String FULL_ENV = "JSQUEAK_TEST_FULL_IMAGE";

    private TestImages() {
    }

    public static File miniImage() {
        return required(MINI_ENV);
    }

    public static File fullImage() {
        return required(FULL_ENV);
    }

    private static File required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            value = System.getProperty(key);
        }
        Assumptions.assumeTrue(value != null && !value.isBlank(),
                key + " must point to a user-supplied Squeak image");
        File file = new File(value);
        Assumptions.assumeTrue(file.isFile(),
                key + " does not point to a file: " + file.getAbsolutePath());
        return file;
    }
}
