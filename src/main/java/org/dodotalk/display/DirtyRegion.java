package org.dodotalk.display;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

/**
 * Collects and merges the rectangles that need an AWT repaint.
 * <p>
 * The VM thread, the AWT event thread and the 30 FPS heartbeat can all submit
 * dirty areas. Keeping a small merged list avoids repainting the entire
 * 640x480 (or larger) display for a one-character cursor update, while still
 * bounding the number of Swing repaint calls.
 * <p>
 * The class is intentionally independent of Swing so it can be unit-tested
 * headlessly.
 */
final class DirtyRegion {

    static final int DEFAULT_MAX_RECTANGLES = 8;

    private final int maxRectangles;
    private final List<Rectangle> rectangles = new ArrayList<>();

    DirtyRegion() {
        this(DEFAULT_MAX_RECTANGLES);
    }

    DirtyRegion(int maxRectangles) {
        if (maxRectangles < 1) {
            throw new IllegalArgumentException("maxRectangles must be positive");
        }
        this.maxRectangles = maxRectangles;
    }

    /**
     * Adds and clips an area, merging it with any overlapping pending area.
     */
    synchronized void add(Rectangle area, int displayWidth, int displayHeight) {
        if (area == null || displayWidth <= 0 || displayHeight <= 0) {
            return;
        }
        Rectangle clipped = area.intersection(new Rectangle(0, 0, displayWidth, displayHeight));
        if (clipped.isEmpty()) {
            return;
        }

        rectangles.add(clipped);
        boolean mergedAgain;
        do {
            mergedAgain = false;
            for (int i = 0; i < rectangles.size(); i++) {
                Rectangle first = rectangles.get(i);
                for (int j = i + 1; j < rectangles.size(); ) {
                    Rectangle second = rectangles.get(j);
                    if (first.intersects(second)
                            || first.contains(second) || second.contains(first)) {
                        first = first.union(second);
                        rectangles.set(i, first);
                        rectangles.remove(j);
                        mergedAgain = true;
                    } else {
                        j++;
                    }
                }
            }
        } while (mergedAgain);

        if (rectangles.size() > maxRectangles) {
            // A highly fragmented update would cost more than one full-screen
            // repaint; collapse to the bounding box instead.
            Rectangle bounds = rectangles.get(0);
            for (int i = 1; i < rectangles.size(); i++) {
                bounds = bounds.union(rectangles.get(i));
            }
            rectangles.clear();
            rectangles.add(bounds);
        }
    }

    synchronized boolean isEmpty() {
        return rectangles.isEmpty();
    }

    /**
     * Returns the pending rectangles and clears the region. The returned list
     * is a snapshot; a concurrent producer may add new areas immediately.
     */
    synchronized List<Rectangle> drain() {
        if (rectangles.isEmpty()) {
            return List.of();
        }
        List<Rectangle> pending = new ArrayList<>(rectangles);
        rectangles.clear();
        return pending;
    }

    synchronized void clear() {
        rectangles.clear();
    }

    synchronized int size() {
        return rectangles.size();
    }
}
