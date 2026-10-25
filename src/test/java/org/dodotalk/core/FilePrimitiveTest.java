package org.dodotalk.core;

import org.dodotalk.TestImages;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the file primitives used when Squeak appends to its
 * changes file before taking a snapshot.
 */
class FilePrimitiveTest {

    private static SqueakVM vm;

    @BeforeAll
    static void loadVM() throws IOException {
        vm = new SqueakVM(new SqueakImage(TestImages.miniImage()));
    }

    private static boolean call(int primitive, Object... operands) {
        for (Object operand : operands) {
            vm.push(operand);
        }
        return SqueakVM.primHandler.doPrimitive(primitive, operands.length - 1);
    }

    @Test
    @DisplayName("open, write, seek and close a file")
    void openWriteSeekClose() throws Exception {
        Path target = Files.createTempFile("jsqueak-file-", ".changes");
        Files.delete(target);
        int savedSp = vm.sp;
        String contents;
        try {
            SqueakObject fileName = SqueakVM.primHandler.makeStString(target.toString());

            assertTrue(call(153, SqueakVM.nilObj, fileName, SqueakVM.trueObj),
                    "file open should succeed");
            Object fileId = vm.top();

            byte[] payload = "hello".getBytes(StandardCharsets.UTF_8);
            SqueakObject byteArray = vm.instantiateClass(Squeak.splOb_ClassByteArray, payload.length);
            for (int i = 0; i < payload.length; i++) {
                byteArray.setByte(i, payload[i]);
            }
            assertTrue(call(158, SqueakVM.nilObj, fileId, byteArray,
                    Integer.valueOf(1), Integer.valueOf(payload.length)),
                    "file write should succeed");
            assertEquals(payload.length, vm.top());

            assertTrue(call(155, SqueakVM.nilObj, fileId, Integer.valueOf(payload.length)),
                    "seek to end should succeed");
            assertTrue(call(158, SqueakVM.nilObj, fileId,
                    SqueakVM.primHandler.makeStString("!"), Integer.valueOf(1), Integer.valueOf(1)),
                    "append should succeed");

            assertTrue(call(151, SqueakVM.nilObj, fileId), "file close should succeed");

            contents = Files.readString(target);
        } finally {
            vm.sp = savedSp;
            Files.deleteIfExists(target);
        }

        assertEquals("hello!", contents);
    }

    @Test
    @DisplayName("primitive 172 is a tracked successful no-op")
    void soundStopNoOp() {
        assertTrue(SqueakPrimitiveHandler.isNoOpStub(172));
        assertTrue(SqueakVM.primHandler.doPrimitive(172, 0));
    }

    @Test
    @DisplayName("SqueakDebug.log is redirected to a sibling logs directory")
    void debugLogRedirected(@TempDir Path directory) throws Exception {
        String requested = directory.resolve("SqueakDebug.log").toString();
        String expected = directory.resolve("logs").resolve("SqueakDebug.log").toString();

        assertEquals(expected, FileSystemPrimitives.resolveRuntimePath(requested));
        assertTrue(Files.isDirectory(directory.resolve("logs")));
        assertEquals(expected, FileSystemPrimitives.resolveRuntimePath(expected),
                "an already-redirected path must not be redirected twice");

        String imagesLog = new File(SqueakImage.defaultSnapshotDirectory(),
                "SqueakDebug.log").getAbsolutePath();
        assertEquals(new File(System.getProperty("user.dir"), "logs" + File.separator
                        + "SqueakDebug.log").getAbsolutePath(),
                FileSystemPrimitives.resolveRuntimePath(imagesLog),
                "SqueakDebug.log under images/ must go to the sibling root logs/");

        int savedSp = vm.sp;
        try {
            SqueakObject fileName = SqueakVM.primHandler.makeStString(requested);
            assertTrue(call(153, SqueakVM.nilObj, fileName, SqueakVM.trueObj));
            Object fileId = vm.top();

            SqueakObject text = SqueakVM.primHandler.makeStString("boom");
            assertTrue(call(158, SqueakVM.nilObj, fileId, text,
                    Integer.valueOf(1), Integer.valueOf(4)));
            assertTrue(call(151, SqueakVM.nilObj, fileId));
        } finally {
            vm.sp = savedSp;
        }

        assertEquals("boom", Files.readString(Path.of(expected)));
        assertFalse(Files.exists(Path.of(requested)),
                "the original project-root path must not be created");
    }
}
