package org.dodotalk.launcher;

import javax.swing.filechooser.FileFilter;
import java.io.File;

/**
 * File chooser filter for raw and gzip-compressed Squeak images.
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
        return "Squeak image (*.image, *.image.gz, *.IMA)";
    }
}
