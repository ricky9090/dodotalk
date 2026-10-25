package org.dodotalk.display;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless tests for the display dirty-region merger.
 */
class DirtyRegionTest {

    @Test
    @DisplayName("overlapping dirty rectangles are merged")
    void mergesOverlappingRectangles() {
        DirtyRegion region = new DirtyRegion(8);
        region.add(new Rectangle(0, 0, 10, 10), 100, 100);
        region.add(new Rectangle(5, 5, 10, 10), 100, 100);

        assertEquals(1, region.size());
        List<Rectangle> pending = region.drain();
        assertEquals(List.of(new Rectangle(0, 0, 15, 15)), pending);
        assertTrue(region.isEmpty());
    }

    @Test
    @DisplayName("dirty rectangles are clipped to the display")
    void clipsToDisplay() {
        DirtyRegion region = new DirtyRegion(8);
        region.add(new Rectangle(-10, -10, 20, 20), 16, 16);
        region.add(new Rectangle(100, 100, 5, 5), 16, 16);
        region.add(new Rectangle(20, 20, 5, 5), 16, 16);

        assertEquals(1, region.size());
        assertEquals(new Rectangle(0, 0, 10, 10), region.drain().get(0));
    }

    @Test
    @DisplayName("fragmented updates collapse to a bounding box")
    void collapsesManyRectangles() {
        DirtyRegion region = new DirtyRegion(4);
        region.add(new Rectangle(0, 0, 2, 2), 100, 100);
        region.add(new Rectangle(10, 10, 2, 2), 100, 100);
        region.add(new Rectangle(20, 20, 2, 2), 100, 100);
        region.add(new Rectangle(30, 30, 2, 2), 100, 100);
        region.add(new Rectangle(40, 40, 2, 2), 100, 100);

        assertEquals(1, region.size());
        assertEquals(new Rectangle(0, 0, 42, 42), region.drain().get(0));
    }

    @Test
    @DisplayName("an empty region drains to an empty list")
    void emptyRegion() {
        DirtyRegion region = new DirtyRegion();
        assertTrue(region.isEmpty());
        assertTrue(region.drain().isEmpty());
        assertFalse(region.size() > 0);
    }
}
