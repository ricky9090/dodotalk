package org.dodotalk.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the image validation of {@link SqueakImage}
 * (task 1.5 of the maintenance plan).
 * <p>
 * A broken image has to be reported with a meaningful message instead of a
 * {@link NullPointerException} somewhere deep inside the loader.
 */
class SqueakImageErrorTest {

    private static final int IMAGE_VERSION = 6502;
    private static final int HEADER_SIZE = 64;

    /** builds a gzip compressed image with the given header fields */
    private static byte[] image(int version, int headerSize, int endOfMemory, int oldBaseAddr,
                                int specialObjectsOop, byte[] body) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(version);
            out.writeInt(headerSize);
            out.writeInt(endOfMemory);
            out.writeInt(oldBaseAddr);
            out.writeInt(specialObjectsOop);
            out.writeInt(0); // lastHash
            out.writeInt(0); // savedWindowSize
            out.writeInt(0); // fullScreenFlag
            out.writeInt(0); // extraVMMemory
            out.write(new byte[Math.max(0, headerSize - 36)]);
            if (body != null) {
                out.write(body);
            }
        }
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(compressed)) {
            gz.write(bytes.toByteArray());
        }
        return compressed.toByteArray();
    }

    private static InputStream stream(byte[] image) {
        return new ByteArrayInputStream(image);
    }

    @Test
    @DisplayName("an image with an unknown version is rejected")
    void badVersion() throws Exception {
        byte[] image = image(1234, HEADER_SIZE, 0, 0, 0, null);

        SqueakImageFormatException e = assertThrows(SqueakImageFormatException.class,
                () -> new SqueakImage(stream(image)));
        assertTrue(e.getMessage().contains("bad image version"), e.getMessage());
    }

    @Test
    @DisplayName("an image with a too small header is rejected")
    void badHeaderSize() throws Exception {
        byte[] image = image(IMAGE_VERSION, 8, 16, 0, 0, null);

        SqueakImageFormatException e = assertThrows(SqueakImageFormatException.class,
                () -> new SqueakImage(stream(image)));
        assertTrue(e.getMessage().contains("bad image header size"), e.getMessage());
    }

    @Test
    @DisplayName("an image without any memory is rejected")
    void badEndOfMemory() throws Exception {
        byte[] image = image(IMAGE_VERSION, HEADER_SIZE, 0, 0, 0, null);

        SqueakImageFormatException e = assertThrows(SqueakImageFormatException.class,
                () -> new SqueakImage(stream(image)));
        assertTrue(e.getMessage().contains("bad end of memory"), e.getMessage());
    }

    @Test
    @DisplayName("a truncated image is reported as such")
    void truncatedImage() throws Exception {
        // the header announces 4096 bytes of memory but the file stops right away
        byte[] image = image(IMAGE_VERSION, HEADER_SIZE, 4096, 0, 0, null);

        SqueakImageFormatException e = assertThrows(SqueakImageFormatException.class,
                () -> new SqueakImage(stream(image)));
        assertTrue(e.getMessage().contains("truncated"), e.getMessage());
    }

    @Test
    @DisplayName("an image without a special objects array is rejected")
    void missingSpecialObjects() throws Exception {
        // a single object with a short header and no data words:
        // size = 1 word, type = 3 (short header)
        byte[] object = new byte[]{(byte) 0x00, (byte) 0x00, (byte) 0x00, 0x07};
        byte[] image = image(IMAGE_VERSION, HEADER_SIZE, 4, 0, 999999, object);

        SqueakImageFormatException e = assertThrows(SqueakImageFormatException.class,
                () -> new SqueakImage(stream(image)));
        assertTrue(e.getMessage().contains("special objects array not found"), e.getMessage());
    }
}
