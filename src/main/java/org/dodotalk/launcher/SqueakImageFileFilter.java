package org.dodotalk.launcher;

import javax.swing.filechooser.FileFilter;
import java.io.File;

/**
 * File chooser filter for gzip-compressed Squeak images.
 * <p>
 * Raw {@code .image} files are intentionally rejected until raw/gzip sniffing
 * is implemented.
 */
final class SqueakImageFileFilter extends FileFilter {

    @Override
    public boolean accept(File file) {
        if (file == null) {
            return false;
        }
        if (file.isDirectory()) {
            return true;
        }
        return LauncherFrame.isSupportedImageName(file.getName());
    }

    @Override
    public String getDescription() {
        return "Squeak image (*.image.gz)";
    }
}
