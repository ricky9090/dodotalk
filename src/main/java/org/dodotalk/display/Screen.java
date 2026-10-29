/*
Screen.java
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

package org.dodotalk.display;


import org.dodotalk.uilts.SqueakLogger;
import org.dodotalk.uilts.RuntimeStats;
import org.dodotalk.core.SqueakVM;
import org.dodotalk.input.InputNotifyThread;
import org.dodotalk.input.KeyboardQueue;
import org.dodotalk.input.MouseStatus;
import org.dodotalk.uilts.ScreenUtils;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.*;
import java.lang.reflect.InvocationTargetException;
import java.util.List;

public class Screen {
    private volatile Dimension fExtent;
    private int fDepth;
    private JFrame fFrame;
    private JPanel contentView;
    private JLabel fDisplay;
    private JLabel background;
    private byte[] fDisplayBits;
    private int[] fDisplayBitsInt;
    private MouseStatus fMouseStatus;
    private KeyboardQueue fKeyboardQueue;
    private InputNotifyThread inputNotifyThread;
    private final RuntimeStats runtimeStats;

    private Timer fHeartBeat;
    private final DirtyRegion dirtyRegion = new DirtyRegion(DirtyRegion.DEFAULT_MAX_RECTANGLES);
    private volatile boolean updatesDeferred;
    private long lastStatsSampleNanos;

    private final static boolean WITH_HEARTBEAT = true;
    private final static int FPS = 30;

    // cf. http://doc.novsu.ac.ru/oreilly/java/awt/ch12_02.htm

    public Screen(String title, int width, int height, int depth, SqueakVM vm) {
        fExtent = new Dimension(width, height);
        fDepth = depth;
        runtimeStats = vm.getRuntimeStats();
        fMouseStatus = new MouseStatus(vm::signalInputEvent);
        fKeyboardQueue = new KeyboardQueue(vm::signalInputEvent, vm::handleKeycode);

        // Swing components must be created on the event dispatch thread.
        runOnEdt(() -> createWindow(title));

        inputNotifyThread = new InputNotifyThread(vm);
        inputNotifyThread.start();
    }

    private void createWindow(String title) {
        fFrame = new JFrame(title);
        fFrame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        // The Squeak display Form has a fixed extent; until resize can remap the
        // Smalltalk Display, do not let the window manager change it independently.
        fFrame.setResizable(false);

        contentView = new JPanel(new BorderLayout());
        Icon noDisplay = new Icon() {
            public int getIconWidth() {
                return fExtent.width;
            }

            public int getIconHeight() {
                return fExtent.height;
            }

            public void paintIcon(Component c, Graphics g, int x, int y) {
            }
        };

        fDisplay = new JLabel(noDisplay) {
            @Override
            protected void paintComponent(Graphics graphics) {
                long started = System.nanoTime();
                super.paintComponent(graphics);
                if (runtimeStats.isEnabled()) {
                    runtimeStats.recordPaint(System.nanoTime() - started);
                }
            }
        };
        fDisplay.setSize(fExtent);
        fDisplay.setHorizontalAlignment(SwingConstants.LEFT);
        fDisplay.setVerticalAlignment(SwingConstants.TOP);

        contentView.add(fDisplay);
        fFrame.setContentPane(contentView);

        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        fFrame.setLocation((screen.width - fExtent.width) / 2, (screen.height - fExtent.height) / 2);   // center

        fDisplay.addMouseMotionListener(fMouseStatus);
        fDisplay.addMouseListener(fMouseStatus);

        fDisplay.setFocusable(true);    // enable keyboard input
        fDisplay.addKeyListener(fKeyboardQueue);
        fDisplay.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                // Releases may go to another window, and modifier keys can be
                // stuck after an Alt-Tab. Reset both so the image cannot see
                // phantom buttons or modifiers.
                fMouseStatus.releaseAllButtons();
                fKeyboardQueue.clearModifiers();
            }
        });

        fFrame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                exit();
            }

            @Override
            public void windowActivated(WindowEvent e) {
                fDisplay.requestFocusInWindow();
            }

            @Override
            public void windowDeactivated(WindowEvent e) {
                fMouseStatus.releaseAllButtons();
                fKeyboardQueue.clearModifiers();
            }
        });

        fDisplay.setOpaque(false);
        fDisplay.getRootPane().setDoubleBuffered(false);    // prevents losing intermediate redraws (how?!)
    }

    public JFrame getFrame() {
        return fFrame;
    }

    public void setBitsV2(final int[] rawBits, final int depth) {
        runOnEdt(() -> {
            int required = requiredWords(fExtent.width, fExtent.height, depth);
            if (rawBits == null) {
                throw new IllegalArgumentException("display bits must not be null");
            }
            if (rawBits.length != required) {
                throw new IllegalArgumentException("display buffer has " + rawBits.length
                        + " words, but " + fExtent.width + "x" + fExtent.height
                        + " at depth " + depth + " needs " + required);
            }
            fDepth = depth;
            fDisplayBitsInt = rawBits;
            fDisplay.setIcon(createDisplayAdapterIntV2(fDisplayBitsInt));
            addDirty(0, 0, fExtent.width, fExtent.height);
        });
    }

    byte[] getBits() {
        return fDisplayBits;
    }

    /**
     * Create display adapter using int buffer, which is directly copy from bitblt 32bit bitmap data<br>
     * As comment saying in copyBitmapToByteArray:
     * <pre>
     *      Copy our 32-bit words into a byte array  until we find out
     *      how to make AWT happy with int buffers
     *  </pre>
     * Now its same to be happy working with it :-)
     */
    private Icon createDisplayAdapterIntV2(int[] storage) {
        SqueakLogger.log_D("createDisplayAdapter storage[] length: " + storage.length);
        SqueakLogger.log_D("createDisplayAdapter Extent.width: " + fExtent.width
                + " Extent.height: " + fExtent.height + " Depth: " + fDepth);

        int required = requiredWords(fExtent.width, fExtent.height, fDepth);
        if (storage.length != required) {
            throw new IllegalArgumentException("display storage has " + storage.length
                    + " words, expected " + required);
        }

        DataBuffer buf = new DataBufferInt(storage, storage.length);
        ColorModel colorModel = ScreenUtils.getDisplayColorModel(fDepth);
        Image image;
        if (fDepth == 32) {
            // Squeak 32-bit pixels are non-premultiplied ARGB. The old code used
            // getRGBdefault(), which is premultiplied and rendered opaque colors
            // incorrectly.
            DirectColorModel direct = (DirectColorModel) colorModel;
            SampleModel sm32 = new SinglePixelPackedSampleModel(buf.getDataType(),
                    fExtent.width, fExtent.height,
                    new int[]{direct.getRedMask(), direct.getGreenMask(),
                            direct.getBlueMask(), direct.getAlphaMask()});
            WritableRaster raster32 = Raster.createWritableRaster(sm32, buf, new Point(0, 0));
            image = new BufferedImage(colorModel, raster32, false, null);
        } else {
            SampleModel sm = new MultiPixelPackedSampleModel(DataBuffer.TYPE_INT,
                    fExtent.width, fExtent.height, fDepth);
            WritableRaster raster = Raster.createWritableRaster(sm, buf, new Point(0, 0));
            image = new BufferedImage(colorModel, raster, false, null);
        }
        return new ImageIcon(image);
    }

    /**
     * Number of 32-bit words needed for a display form of the given geometry.
     */
    static int requiredWords(int width, int height, int depth) {
        if (!isSupportedDepth(depth)) {
            throw new IllegalArgumentException("Unsupported color depth: " + depth);
        }
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException("negative display extent: " + width + "x" + height);
        }
        int pixelsPerWord = 32 / depth;
        int pitch = (width + pixelsPerWord - 1) / pixelsPerWord;
        return pitch * height;
    }

    /**
     * True for the Squeak display depths the Java adapter can render.
     */
    public static boolean isSupportedDepth(int depth) {
        return ScreenUtils.isSupportedDepth(depth);
    }

    public void open() {
        runOnEdt(() -> {
            fFrame.pack();
            fFrame.setVisible(true);
            if (WITH_HEARTBEAT) {
                fHeartBeat = new Timer(1000 / FPS /* ms */, new ActionListener() {
                    public void actionPerformed(ActionEvent evt) {
                        // Swing timers execute on the EDT
                        boolean dirty = !dirtyRegion.isEmpty();
                        if (runtimeStats.isEnabled()) {
                            runtimeStats.recordHeartbeat(dirty);
                            long now = System.nanoTime();
                            if (now - lastStatsSampleNanos >= 1_000_000_000L) {
                                lastStatsSampleNanos = now;
                                runtimeStats.recordInputQueues(
                                        fMouseStatus.queueSize(), fKeyboardQueue.size());
                            }
                        }
                        paintDirtyRects();
                    }
                });
                fHeartBeat.start();
            }
            // The display buffer was installed before the frame became
            // visible; schedule its first paint now.
            paintDirtyRects();
        });
    }

    public void close() {
        runOnEdt(() -> {
            if (WITH_HEARTBEAT && fHeartBeat != null) {
                fHeartBeat.stop();
            }
            dirtyRegion.clear();
            fFrame.setVisible(false);
            fFrame.dispose();
        });
    }

    public void redisplay(boolean immediately, Rectangle area) {
        if (area == null) {
            redisplay(immediately);
            return;
        }
        redisplay(immediately, area.x, area.y, area.width, area.height);
    }

    public void redisplay(boolean immediately, final int cornerX, final int cornerY, final int width, final int height) {
        addDirty(cornerX, cornerY, width, height);
        if (immediately) {
            // Primitive 127 / ioShowDisplay: bypass deferral and paint now.
            flushDirtyNow();
        }
    }

    public void redisplay(boolean immediately) {
        Dimension extent = fExtent;
        redisplay(immediately, 0, 0, extent.width, extent.height);
    }

    /**
     * Display>>deferUpdates:. While deferred, dirty areas are still collected
     * but the heartbeat does not push them to the screen until
     * {@link #forceDisplayUpdate()} or {@code deferUpdates: false} plus a
     * normal update.
     */
    public void setUpdatesDeferred(boolean deferred) {
        updatesDeferred = deferred;
    }

    /**
     * Primitive 231 / Display>>forceDisplayUpdate. Paints all pending areas
     * synchronously, even when updates are currently deferred.
     */
    public void forceDisplayUpdate() {
        flushDirtyNow();
    }

    private void addDirty(int x, int y, int width, int height) {
        Dimension extent = fExtent;
        dirtyRegion.add(new Rectangle(x, y, width, height), extent.width, extent.height);
    }

    /**
     * Called on the EDT by the heartbeat. Uses asynchronous repaint so the VM
     * thread is never blocked by a normal screen update.
     */
    private void paintDirtyRects() {
        if (updatesDeferred) {
            return;
        }
        List<Rectangle> pending = dirtyRegion.drain();
        if (pending.isEmpty() || fDisplay == null) {
            return;
        }
        for (Rectangle area : pending) {
            fDisplay.repaint(area.x, area.y, area.width, area.height);
        }
    }

    /**
     * Paints pending areas synchronously. Used by primitives that explicitly
     * request an immediate update (127, 231).
     */
    private void flushDirtyNow() {
        List<Rectangle> pending = dirtyRegion.drain();
        if (pending.isEmpty() || fDisplay == null) {
            return;
        }
        Runnable paint = () -> {
            for (Rectangle area : pending) {
                fDisplay.paintImmediately(area.x, area.y, area.width, area.height);
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            paint.run();
        } else {
            runOnEdt(paint);
        }
    }

    protected boolean scheduleRedisplay(boolean immediately, Runnable trigger) {
        if (immediately) {
            try {
                SwingUtilities.invokeAndWait(trigger);
                return true;
            } catch (InterruptedException e) {
                logRedisplayException(e);
            } catch (InvocationTargetException e) {
                logRedisplayException(e);
            }
            return false;
        } else {
            SwingUtilities.invokeLater(trigger);
            return true;
        }
    }

    // extension point, default is ignorance
    protected void logRedisplayException(Exception e) {
    }

    private final static int Squeak_CURSOR_WIDTH = 16;
    private final static int Squeak_CURSOR_HEIGHT = 16;

    private final static byte C_WHITE = 0;
    private final static byte C_BLACK = 1;
    private final static byte C_TRANSPARENT = 2;
    private final static byte[] kCursorComponentX = new byte[]{-1, 0, 0};
    private final static byte[] kCursorComponentA = new byte[]{-1, -1, 0};

    protected Image createCursorAdapter(byte[] bits, byte[] mask) {
        int bufSize = Squeak_CURSOR_HEIGHT * Squeak_CURSOR_WIDTH;
        DataBuffer buf = new DataBufferByte(new byte[bufSize], bufSize);
        // unpack samples and mask to bytes with transparency:
        int p = 0;
        for (int row = 0; row < Squeak_CURSOR_HEIGHT; row++) {
            for (int x = 0; x < 2; x++) {
                for (int col = 0x80; col != 0; col >>>= 1) {
                    if ((mask[(row * 4) + x] & col) != 0)
                        buf.setElem(p++, (bits[(row * 4) + x] & col) != 0 ? C_BLACK : C_WHITE);
                    else
                        buf.setElem(p++, C_TRANSPARENT);
                }
            }
        }
        SampleModel sm = new SinglePixelPackedSampleModel(DataBuffer.TYPE_BYTE, Squeak_CURSOR_WIDTH, Squeak_CURSOR_HEIGHT, new int[]{255});
        IndexColorModel cm = new IndexColorModel(8, 3, kCursorComponentX, kCursorComponentX, kCursorComponentX, kCursorComponentA);
        WritableRaster raster = Raster.createWritableRaster(sm, buf, new Point(0, 0));
        return new BufferedImage(cm, raster, true, null);
    }

    protected byte[] extractBits(byte[] bitsAndMask, int offset) {
        final int n = bitsAndMask.length / 2;  // 32 bytes -> 8 bytes
        byte[] answer = new byte[n];
        for (int i = 0; i < n; i++) {
            // convert incoming little-endian words to bytes:
            answer[i] = bitsAndMask[offset + i];
        }
        return answer;
    }

    public void setCursor(final byte[] imageAndMask, final int BWMask) {
        runOnEdt(() -> {
            int n = imageAndMask.length;
            for (int i = 0; i < n / 2; i++) {
                imageAndMask[i] ^= BWMask; // reverse cursor bits all the time
                imageAndMask[i + (n / 2)] ^= BWMask;  // reverse transparency if display reversed
            }
            Toolkit tk = Toolkit.getDefaultToolkit();
            Dimension cx = tk.getBestCursorSize(Squeak_CURSOR_WIDTH, Squeak_CURSOR_HEIGHT);

            // using system default cursor, until we find out how to draw cursor with right size
            Cursor c;
            c = Cursor.getDefaultCursor();
            /*if (cx.width == 0 || cx.height == 0)
            {
                c= Cursor.getDefaultCursor();
            }
            else
            {
                Image ci= createCursorAdapter(extractBits(imageAndMask, 0), extractBits(imageAndMask, Squeak_CURSOR_HEIGHT*4));
                c= tk.createCustomCursor(ci, new Point(0, 0), "Smalltalk-78 cursor");
            }*/
            fDisplay.setCursor(c);
        });
    }

    public Dimension getExtent() {
        // Return an immutable snapshot; the EDT may replace the field at any time.
        return new Dimension(fExtent);
    }

    public void setExtent(final Dimension extent) {
        runOnEdt(() -> {
            Dimension newExtent = new Dimension(extent);
            fExtent = newExtent;
            fDisplay.setPreferredSize(newExtent);
            fDisplay.setSize(newExtent);
            contentView.setPreferredSize(newExtent);
            // The window is not user-resizable; pack it exactly around the new
            // display size so the content pane cannot shrink behind our back.
            fFrame.pack();
            addDirty(0, 0, newExtent.width, newExtent.height);
        });
    }

    /**
     * @return the point paired with the next mouse button transition, or the
     * current mouse point when no transition is queued
     */
    public Point peekMousePoint() {
        return fMouseStatus.pollMousePoint();
    }

    /**
     * Consumes one queued press/release transition. When the queue is empty,
     * the current physical button state is returned so drag polling works.
     * Idle polls are capped by {@link MouseStatus#POLL_RATE_HZ}; mouse input
     * wakes a waiting poll immediately.
     */
    public int consumeMouseButtons() {
        return (fMouseStatus.pollMouseButtons() & 7) | fKeyboardQueue.modifierKeys();
    }

    public Point getLastMousePoint() {
        return fMouseStatus.getPoint();
    }

    public int getLastMouseButtonStatus() {
        return (fMouseStatus.getButtons() & 7) | fKeyboardQueue.modifierKeys();
    }

    public int getQueuedMouseEventCount() {
        return fMouseStatus.queueSize();
    }

    public void setMousePoint(final int x, final int y) {
        final Point[] origin = new Point[1];
        runOnEdt(() -> origin[0] = fDisplay.getLocationOnScreen());
        try {
            Robot robot = new Robot();
            robot.mouseMove(x + origin[0].x, y + origin[0].y);
        } catch (AWTException e) {
            // ignore silently?
            SqueakLogger.log_E("Mouse move to " + x + "@" + y + " failed.");
        }
    }

    public int keyboardPeek() {
        return fKeyboardQueue.peek();
    }

    public int keyboardNext() {
        //System.err.println("character code="+fKeyboardQueue.peek());
        return fKeyboardQueue.next();
    }

    public boolean keyboardHasNext() {
        return fKeyboardQueue.hasNext();
    }

    public void logging(String msg) {

    }

    public void exit() {
        inputNotifyThread.quit();
        close();
        // Closing the window is a normal shutdown; exit code 1 made Gradle
        // report BUILD FAILED and hid the diagnostics summary above it.
        System.exit(0);
    }

    private static void runOnEdt(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(action);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the EDT", e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new IllegalStateException("EDT action failed", cause);
        }
    }
}
