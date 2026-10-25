package org.dodotalk.launcher;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LauncherFrameTest {

    @Test
    void acceptsOnlyGzipImageNames() {
        assertTrue(LauncherFrame.isSupportedImageName("squeak.image.gz"));
        assertTrue(LauncherFrame.isSupportedImageName("SQUEAK.IMAGE.GZ"));
        assertFalse(LauncherFrame.isSupportedImageName("squeak.image"));
        assertFalse(LauncherFrame.isSupportedImageName("squeak.gz"));
        assertFalse(LauncherFrame.isSupportedImageName(null));
    }
}
