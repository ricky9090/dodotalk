package org.dodotalk.display;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.dodotalk.uilts.ScreenUtils;

import java.awt.Point;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferInt;
import java.awt.image.DirectColorModel;
import java.awt.image.MultiPixelPackedSampleModel;
import java.awt.image.Raster;
import java.awt.image.SinglePixelPackedSampleModel;
import java.awt.image.WritableRaster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the display color models and buffer geometry
 * (rendering review items 3.4 and 3.5).
 */
class ScreenUtilsTest {

    @Test
    @DisplayName("all Squeak display depths have a color model")
    void supportedDepths() {
        for (int depth : new int[]{1, 2, 4, 8, 16, 32}) {
            assertTrue(ScreenUtils.isSupportedDepth(depth), "depth " + depth);
            assertTrue(Screen.isSupportedDepth(depth), "depth " + depth);
            assertEquals(depth, ScreenUtils.getDisplayColorModel(depth).getPixelSize(),
                    "color model pixel size for depth " + depth);
        }
        assertFalse(ScreenUtils.isSupportedDepth(0));
        assertFalse(ScreenUtils.isSupportedDepth(3));
        assertFalse(ScreenUtils.isSupportedDepth(64));
        assertThrows(IllegalArgumentException.class,
                () -> ScreenUtils.getDisplayColorModel(3));
    }

    @Test
    @DisplayName("2-bit and 4-bit maps match Squeak's fixed indexed colors")
    void indexedPalettes() {
        ColorModel model2 = ScreenUtils.getDisplayColorModel(2);
        assertEquals(0xFFFFFFFF, model2.getRGB(0)); // white/transparent entry
        assertEquals(0xFF000000, model2.getRGB(1)); // black
        assertEquals(0xFFFFFFFF, model2.getRGB(2)); // opaque white
        assertEquals(0xFF808080, model2.getRGB(3)); // 1/2 gray

        ColorModel model4 = ScreenUtils.getDisplayColorModel(4);
        assertEquals(0xFFFF0000, model4.getRGB(4)); // red
        assertEquals(0xFF00FF00, model4.getRGB(5)); // green
        assertEquals(0xFF0000FF, model4.getRGB(6)); // blue
        assertEquals(0xFF00FFFF, model4.getRGB(7)); // cyan
        assertEquals(0xFF202020, model4.getRGB(10)); // 1/8 gray
        assertEquals(0xFFE0E0E0, model4.getRGB(15)); // 7/8 gray
    }

    @Test
    @DisplayName("16-bit pixels use RGB555")
    void rgb555() {
        ColorModel model = ScreenUtils.getDisplayColorModel(16);
        assertEquals(0xFFFF0000, model.getRGB(0x7C00)); // red
        assertEquals(0xFF00FF00, model.getRGB(0x03E0)); // green
        assertEquals(0xFF0000FF, model.getRGB(0x001F)); // blue
        assertEquals(0xFFFFFFFF, model.getRGB(0x7FFF)); // white
    }

    @Test
    @DisplayName("32-bit pixels are non-premultiplied ARGB")
    void argb32() {
        DirectColorModel model = (DirectColorModel) ScreenUtils.getDisplayColorModel(32);
        assertEquals(0x00FF0000, model.getRedMask());
        assertEquals(0x0000FF00, model.getGreenMask());
        assertEquals(0x000000FF, model.getBlueMask());
        assertEquals(0xFF000000, model.getAlphaMask());
        assertFalse(model.isAlphaPremultiplied());
        assertEquals(0xFF112233, model.getRGB(0xFF112233));
    }

    @Test
    @DisplayName("AWT can render every supported depth")
    void awtAdapterForEveryDepth() {
        int width = 17;
        int height = 3;
        for (int depth : new int[]{1, 2, 4, 8, 16, 32}) {
            int words = Screen.requiredWords(width, height, depth);
            DataBuffer buffer = new DataBufferInt(new int[words], words);
            ColorModel model = ScreenUtils.getDisplayColorModel(depth);
            WritableRaster raster;
            if (depth == 32) {
                DirectColorModel direct = (DirectColorModel) model;
                raster = Raster.createWritableRaster(new SinglePixelPackedSampleModel(
                        DataBuffer.TYPE_INT, width, height,
                        new int[]{direct.getRedMask(), direct.getGreenMask(),
                                direct.getBlueMask(), direct.getAlphaMask()}),
                        buffer, new Point(0, 0));
            } else {
                raster = Raster.createWritableRaster(new MultiPixelPackedSampleModel(
                        DataBuffer.TYPE_INT, width, height, depth), buffer, new Point(0, 0));
            }
            BufferedImage image = new BufferedImage(model, raster, false, null);
            assertEquals(width, image.getWidth());
            assertEquals(height, image.getHeight());
        }
    }

    @Test
    @DisplayName("required display buffer size matches Squeak's pitch")
    void requiredWords() {
        assertEquals(20 * 480, Screen.requiredWords(640, 480, 1));
        assertEquals(40 * 480, Screen.requiredWords(640, 480, 2));
        assertEquals(80 * 480, Screen.requiredWords(640, 480, 4));
        assertEquals(160 * 480, Screen.requiredWords(640, 480, 8));
        assertEquals(320 * 480, Screen.requiredWords(640, 480, 16));
        assertEquals(640 * 480, Screen.requiredWords(640, 480, 32));
        // An odd width needs a partially used word.
        assertEquals(3 * 9, Screen.requiredWords(17, 3, 16));
        assertThrows(IllegalArgumentException.class, () -> Screen.requiredWords(10, 10, 3));
        assertThrows(IllegalArgumentException.class, () -> Screen.requiredWords(-1, 10, 8));
    }
}
