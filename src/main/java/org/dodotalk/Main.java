/*
Main.java
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

/*
 The author is indebted to Helge Horch for the outer framework of this VM.
 I knew nothing about how to write a Java program, and he provided the basic
 structure of main program, mouse input and display output for me.
 The object model, interpreter and primitives are all my design, but Helge
 continued to help whenever I was particularly stuck during the project.
*/

package org.dodotalk;

import org.dodotalk.launcher.LaunchOptions;
import org.dodotalk.launcher.LauncherFrame;
import org.dodotalk.uilts.SqueakLogger;

import java.awt.GraphicsEnvironment;
import java.io.File;

public class Main {

    public static void main(String[] args) {
        Starter.BOOT_ARGS = args;
        try {
            LaunchOptions options = LaunchOptions.parse(args);

            if (options.help()) {
                System.out.println(LaunchOptions.usage());
                return;
            }
            if (options.imagePath() != null) {
                Starter.boot(new File(options.imagePath()));
                return;
            }
            if (GraphicsEnvironment.isHeadless()) {
                System.err.println("No Squeak image was supplied and the GUI is unavailable.");
                System.err.println(LaunchOptions.usage());
                System.exit(1);
                return;
            }
            LauncherFrame.showLauncher();
        } catch (IllegalArgumentException ex) {
            System.err.println(ex.getMessage());
            System.err.println(LaunchOptions.usage());
            System.exit(1);
        } catch (Exception ex) {
            SqueakLogger.log_E("Failed to start the VM", ex);
            System.exit(1);
        }
    }
}
