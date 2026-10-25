/*
Starter.java
Copyright (c) 2008  Daniel H. H. Ingalls, Sun Microsystems, Inc.  All rights reserved.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
*/

package org.dodotalk;

import org.dodotalk.core.SqueakImage;
import org.dodotalk.core.SqueakVM;
import org.dodotalk.uilts.SqueakLogger;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

public class Starter {

    public static String[] BOOT_ARGS = null;

    /**
     * Loads and validates the image and creates the VM without starting the
     * interpreter. The launcher uses this to report load errors before hiding
     * its window; {@link #boot(File)} uses the same path for command-line
     * startup.
     */
    public static SqueakVM load(File imageFile) throws IOException {
        File file = validateImageFile(imageFile);
        SqueakLogger.log_I("Load image from: " + file);
        SqueakImage image = new SqueakImage(file);
        SqueakVM vm = new SqueakVM(image);
        SqueakVM.INSTANCE = vm;
        vm.getRuntimeStats().installShutdownHook();
        return vm;
    }

    /**
     * Boots the VM using the supplied image. Snapshots are written next to the
     * selected image; the previous image is kept as {@code <name>.bak}.
     */
    public static void boot(File imageFile) throws IOException {
        load(imageFile).run();
    }

    /**
     * Backward-compatible entry point for callers that only set
     * {@link #BOOT_ARGS}. New code should use {@link #boot(File)}.
     */
    public static void boot() throws IOException {
        String imagePath = requestedImagePath();
        if (imagePath == null) {
            throw new IOException("No image file was specified");
        }
        boot(new File(imagePath));
    }

    /**
     * Validates a user-supplied image path. This release supports only
     * gzip-compressed {@code .image.gz} files; raw image support is planned
     * separately.
     */
    public static File validateImageFile(File imageFile) throws IOException {
        if (imageFile == null) {
            throw new IOException("No image file was specified");
        }
        File file = imageFile.getAbsoluteFile();
        if (!file.isFile()) {
            throw new IOException("Image file not found: " + file);
        }
        if (!file.canRead()) {
            throw new IOException("Image file is not readable: " + file);
        }
        if (!file.getName().toLowerCase(Locale.ROOT).endsWith(".image.gz")) {
            throw new IOException("Only gzip-compressed .image.gz images are supported");
        }
        return file;
    }

    /**
     * Mirrors Squeak's changes-name derivation: baseNameFor(imageName) + ".changes".
     */
    static File changesFileFor(File imageFile) {
        File absolute = imageFile.getAbsoluteFile();
        String name = absolute.getName();
        if (name.endsWith(".gz")) {
            name = name.substring(0, name.length() - 3);
        }
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        return new File(absolute.getParentFile(), name + ".changes");
    }

    /**
     * Supports both {@code <image>} and {@code -image <image>}.
     * Package-private for regression tests.
     */
    static String requestedImagePath() {
        if (BOOT_ARGS == null || BOOT_ARGS.length == 0) {
            return null;
        }
        if (BOOT_ARGS.length > 1 && "-image".equals(BOOT_ARGS[0])) {
            return BOOT_ARGS[1];
        }
        return BOOT_ARGS[0];
    }
}
