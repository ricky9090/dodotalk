package org.dodotalk.launcher;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LauncherFrameTest {

    @Test
    void acceptsRawAndGzipImageNames() {
        assertTrue(LauncherFrame.isSupportedImageName("squeak.image.gz"));
        assertTrue(LauncherFrame.isSupportedImageName("SQUEAK.IMAGE.GZ"));
        assertTrue(LauncherFrame.isSupportedImageName("squeak.image"));
        assertTrue(LauncherFrame.isSupportedImageName("SQUEAK.IMA"));
        assertFalse(LauncherFrame.isSupportedImageName("squeak.gz"));
        assertFalse(LauncherFrame.isSupportedImageName("squeak.txt"));
        assertFalse(LauncherFrame.isSupportedImageName(null));
    }
}
