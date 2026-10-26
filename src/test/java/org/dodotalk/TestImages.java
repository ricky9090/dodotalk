package org.dodotalk;

import org.dodotalk.uilts.SqueakLogger;
import org.junit.jupiter.api.Assumptions;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Locates user-supplied Squeak images for integration tests.
 * <p>
 * The repository intentionally does not bundle a Squeak 2.2 image. Images can
 * be supplied explicitly with environment variables or system properties:
 *
 * <pre>
 * JSQUEAK_TEST_MINI_IMAGE=/path/to/mini.image.gz
 * JSQUEAK_TEST_FULL_IMAGE=/path/to/squeak22.image.gz
 * </pre>
 *
 * <p>
 * For local development, images may also be placed outside the repository at
 * {@code ../temp/test-images/} (a sibling of the repository root). The
 * resolver checks the following locations in order:
 *
 * <ol>
 *   <li>environment variable;</li>
 *   <li>matching system property;</li>
 *   <li>{@code JSQUEAK_TEST_IMAGES_DIR} or {@code -Djsqueak.test.images.dir};</li>
 *   <li>{@code <repo>/../temp/test-images/}.</li>
 * </ol>
 *
 * <p>
 * An in-project {@code temp/test-images/} directory is intentionally not
 * discovered. Keep test images outside the repository; the
 * {@code checkNoSqueakImages} Gradle task remains as a safety net.
 *
 * <p>
 * Only fixed file names are checked; the resolver never scans arbitrary
 * directories. Tests that need an image are skipped when no image is found.
 * See {@code doc/test-images.md} and {@code ADR-0002}.
 */
public final class TestImages {

    public static final String MINI_ENV = "JSQUEAK_TEST_MINI_IMAGE";
    public static final String FULL_ENV = "JSQUEAK_TEST_FULL_IMAGE";

    /** Optional override for the directory containing the test images. */
    public static final String IMAGE_DIR_ENV = "JSQUEAK_TEST_IMAGES_DIR";
    public static final String IMAGE_DIR_PROPERTY = "jsqueak.test.images.dir";

    /** Recommended directory, relative to the repository root. */
    public static final String DEFAULT_IMAGE_DIRECTORY = "temp/test-images";

    /**
     * Raw {@code .image} loading is not implemented yet. Until it is, a raw
     * image found by the resolver is reported as a skip instead of letting the
     * gzip-only loader fail.
     */
    static final boolean RAW_IMAGE_SUPPORTED = false;

    private static final Set<String> announcedImages = new LinkedHashSet<>();

    private TestImages() {
    }

    public static File miniImage() {
        return required(MINI_ENV, "mini", "mini");
    }

    public static File fullImage() {
        return required(FULL_ENV, "squeak22", "full");
    }

    private static File required(String key, String baseName, String label) {
        String value = firstNonBlank(System.getenv(key), System.getProperty(key));
        if (value != null) {
            File file = new File(value).getAbsoluteFile();
            Assumptions.assumeTrue(file.isFile(),
                    key + " does not point to a file: " + file.getAbsolutePath());
            skipIfRawUnsupported(file);
            announce(label, file, key);
            return file;
        }

        File found = findDefaultImage(baseName);
        if (found == null) {
            Assumptions.assumeTrue(false,
                    key + " is not set and no " + baseName + ".image.gz was found in "
                            + defaultImageDirectories()
                            + ". Place test images in ../temp/test-images/ or see doc/test-images.md.");
            return null; // unreachable; keeps the compiler happy
        }
        skipIfRawUnsupported(found);
        announce(label, found, "auto-detected");
        return found;
    }

    private static void skipIfRawUnsupported(File file) {
        if (!RAW_IMAGE_SUPPORTED && file.getName().toLowerCase().endsWith(".image")) {
            Assumptions.assumeTrue(false,
                    "Found raw image " + file.getAbsolutePath()
                            + " but raw .image loading is not implemented yet. "
                            + "Use a .image.gz instead.");
        }
    }

    /**
     * Finds an image by base name in the default directories.
     */
    static File findDefaultImage(String baseName) {
        for (File directory : defaultImageDirectories()) {
            File found = findImage(directory, baseName);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * Finds an image in one directory. {@code .image.gz} is preferred over a
     * raw {@code .image}; the official {@code Squeak2.2} spelling is accepted
     * as an alias for the full image.
     */
    static File findImage(File directory, String baseName) {
        if (directory == null) {
            return null;
        }
        for (String alias : aliasesFor(baseName)) {
            File gzip = new File(directory, alias + ".image.gz");
            if (gzip.isFile() && gzip.canRead()) {
                return gzip;
            }
        }
        for (String alias : aliasesFor(baseName)) {
            File raw = new File(directory, alias + ".image");
            if (raw.isFile() && raw.canRead()) {
                return raw;
            }
        }
        return null;
    }

    static List<String> aliasesFor(String baseName) {
        if ("squeak22".equals(baseName)) {
            return List.of("squeak22", "Squeak2.2");
        }
        return List.of(baseName);
    }

    /**
     * Directories checked by {@link #findDefaultImage(String)}, in priority
     * order. The repository root is discovered by walking up from the current
     * working directory so IDE runs do not depend on a specific working
     * directory.
     */
    static List<File> defaultImageDirectories() {
        LinkedHashSet<File> directories = new LinkedHashSet<>();

        String configured = firstNonBlank(
                System.getenv(IMAGE_DIR_ENV),
                System.getProperty(IMAGE_DIR_PROPERTY));
        if (configured != null) {
            directories.add(new File(configured).getAbsoluteFile());
        }

        File root = projectRoot();
        File parent = root.getParentFile();
        if (parent != null) {
            directories.add(new File(parent, DEFAULT_IMAGE_DIRECTORY).getAbsoluteFile());
        }

        return new ArrayList<>(directories);
    }

    /**
     * Best-effort repository root discovery: walk up until a Gradle settings
     * file or a {@code .git} entry is found.
     */
    static File projectRoot() {
        File directory = new File(System.getProperty("user.dir", ".")).getAbsoluteFile();
        File candidate = directory;
        for (int i = 0; i < 6 && candidate != null; i++) {
            if (new File(candidate, "settings.gradle.kts").isFile()
                    || new File(candidate, ".git").exists()) {
                return candidate;
            }
            candidate = candidate.getParentFile();
        }
        return directory;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static void announce(String label, File file, String source) {
        String key = label + "=" + file.getAbsolutePath();
        synchronized (announcedImages) {
            if (!announcedImages.add(key)) {
                return;
            }
        }
        SqueakLogger.log_I("test image (" + label + "): " + file.getAbsolutePath() + " (" + source + ")");
    }
}
