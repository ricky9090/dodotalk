package org.dodotalk.launcher;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LaunchOptionsTest {

    @Test
    void noArgumentsMeansGuiMode() {
        LaunchOptions options = LaunchOptions.parse(new String[0]);

        assertNull(options.imagePath());
        assertFalse(options.help());
    }

    @Test
    void bareArgumentIsImagePath() {
        LaunchOptions options = LaunchOptions.parse(new String[]{"/tmp/test.image.gz"});

        assertEquals("/tmp/test.image.gz", options.imagePath());
    }

    @Test
    void imageFlagTakesFollowingArgument() {
        LaunchOptions options = LaunchOptions.parse(
                new String[]{"-image", "/tmp/test.image.gz"});

        assertEquals("/tmp/test.image.gz", options.imagePath());
    }

    @Test
    void helpFlags() {
        assertTrue(LaunchOptions.parse(new String[]{"--help"}).help());
        assertTrue(LaunchOptions.parse(new String[]{"-h"}).help());
    }

    @Test
    void imageFlagRequiresPath() {
        assertThrows(IllegalArgumentException.class,
                () -> LaunchOptions.parse(new String[]{"-image"}));
    }

    @Test
    void blankImagePathIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> LaunchOptions.parse(new String[]{" "}));
    }

    @Test
    void unknownOptionIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> LaunchOptions.parse(new String[]{"--no-such-option"}));
    }

    @Test
    void extraArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> LaunchOptions.parse(new String[]{"-image", "a", "b"}));
    }
}
