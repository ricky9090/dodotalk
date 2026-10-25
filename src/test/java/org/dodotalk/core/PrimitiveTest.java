package org.dodotalk.core;

import org.dodotalk.TestImages;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for image saving (tasks 1.8 and 1.9 of the maintenance
 * plan). A saved image must be loadable again and expose the same core state.
 */
class PrimitiveTest {

    private static String nameOfClassObject(Object classObject) {
        assertTrue(classObject instanceof SqueakObject);
        return ((SqueakObject) classObject).classGetName().asString();
    }

    private static void assertCoreState(String stringClassName, int specialObjectsCount,
                                        int specialSelectorsCount) {
        assertNotNull(SqueakVM.nilObj, "nil should be present");
        assertNotNull(SqueakVM.trueObj, "true should be present");
        assertNotNull(SqueakVM.falseObj, "false should be present");
        assertNotSame(SqueakVM.nilObj, SqueakVM.trueObj);
        assertNotSame(SqueakVM.nilObj, SqueakVM.falseObj);
        assertNotSame(SqueakVM.trueObj, SqueakVM.falseObj);

        assertEquals(specialObjectsCount, SqueakVM.specialObjects.length,
                "special objects array size must survive a save");
        assertEquals(specialSelectorsCount, SqueakVM.specialSelectors.length,
                "special selectors must survive a save");

        SqueakObject classString = (SqueakObject) SqueakVM.specialObjects[Squeak.splOb_ClassString];
        assertNotNull(classString, "String class should be present");
        assertEquals(stringClassName, classString.classGetName().asString());

        SqueakObject scheduler = ((SqueakObject) SqueakVM.specialObjects[
                Squeak.splOb_SchedulerAssociation]).getPointerNI(Squeak.Assn_value);
        assertNotNull(scheduler, "scheduler should be present");
        SqueakObject activeProcess = scheduler.getPointerNI(Squeak.ProcSched_activeProcess);
        assertNotNull(activeProcess, "active process should be present");
        assertNotNull(activeProcess.getPointerNI(Squeak.Proc_suspendedContext),
                "active process context should be present");
    }

    private static SqueakObject activeMethod() {
        SqueakObject scheduler = ((SqueakObject) SqueakVM.specialObjects[
                Squeak.splOb_SchedulerAssociation]).getPointerNI(Squeak.Assn_value);
        SqueakObject activeProcess = scheduler.getPointerNI(Squeak.ProcSched_activeProcess);
        SqueakObject context = activeProcess.getPointerNI(Squeak.Proc_suspendedContext);
        return context.getPointerNI(Squeak.Context_method);
    }

    private static int byteChecksum(byte[] bytes) {
        int checksum = 1;
        for (byte b : bytes) {
            checksum = 31 * checksum + b;
        }
        return checksum;
    }

    private static void deleteImage(Path target) throws IOException {
        Files.deleteIfExists(target);
        Files.deleteIfExists(target.resolveSibling(target.getFileName() + ".bak"));
    }

    private static void saveReloadAndCheck(File source, Path target) throws Exception {
        SqueakImage image = new SqueakImage(source);
        new SqueakVM(image);

        String stringClassName = nameOfClassObject(
                SqueakVM.specialObjects[Squeak.splOb_ClassString]);
        int specialObjectsCount = SqueakVM.specialObjects.length;
        int specialSelectorsCount = SqueakVM.specialSelectors.length;
        SqueakObject method = activeMethod();
        int methodHeader = method.methodHeader();
        int methodLiterals = method.methodNumLits();
        int methodChecksum = byteChecksum((byte[]) method.getBits());

        image.save(target.toFile());
        assertTrue(Files.size(target) > 0, "saved image must not be empty");

        SqueakImage reloaded = new SqueakImage(target.toFile());
        new SqueakVM(reloaded);

        assertCoreState(stringClassName, specialObjectsCount, specialSelectorsCount);

        SqueakObject reloadedMethod = activeMethod();
        assertEquals(methodHeader, reloadedMethod.methodHeader());
        assertEquals(methodLiterals, reloadedMethod.methodNumLits());
        assertEquals(methodChecksum, byteChecksum((byte[]) reloadedMethod.getBits()));
    }

    @Test
    @DisplayName("a full image can be saved and loaded again")
    void saveAndReloadFullImage() throws Exception {
        Path target = Files.createTempFile("jsqueak-full-", ".image.gz");
        try {
            saveReloadAndCheck(TestImages.fullImage(), target);
        } finally {
            deleteImage(target);
        }
    }

    @Test
    @DisplayName("a mini image can be saved and loaded again")
    void saveAndReloadMiniImage() throws Exception {
        Path target = Files.createTempFile("jsqueak-mini-", ".image.gz");
        try {
            saveReloadAndCheck(TestImages.miniImage(), target);
        } finally {
            deleteImage(target);
        }
    }

    @Test
    @DisplayName("primitive 97 snapshots the current image file")
    void primitiveSnapshotSavesImage() throws Exception {
        Path target = Files.createTempFile("jsqueak-snapshot-", ".image.gz");
        try {
            Files.copy(TestImages.fullImage().toPath(), target, StandardCopyOption.REPLACE_EXISTING);

            SqueakImage image = new SqueakImage(target.toFile());
            SqueakVM vm = new SqueakVM(image);
            String stringClassName = nameOfClassObject(
                    SqueakVM.specialObjects[Squeak.splOb_ClassString]);
            int specialObjectsCount = SqueakVM.specialObjects.length;
            int specialSelectorsCount = SqueakVM.specialSelectors.length;

            assertTrue(SqueakVM.primHandler.doPrimitive(97, 0),
                    "primitive 97 should report success");
            assertSame(SqueakVM.falseObj, vm.top(),
                    "a successful snapshot must leave false (not resuming) on the stack");

            SqueakImage reloaded = new SqueakImage(target.toFile());
            new SqueakVM(reloaded);
            assertCoreState(stringClassName, specialObjectsCount, specialSelectorsCount);
        } finally {
            deleteImage(target);
        }
    }

    @Test
    @DisplayName("saving an image without a VM keeps the object graph intact")
    void saveWithoutVM() throws Exception {
        Path target = Files.createTempFile("jsqueak-image-", ".image.gz");
        try {
            SqueakImage image = new SqueakImage(TestImages.miniImage());

            image.save(target.toFile());
            assertTrue(Files.size(target) > 0);

            SqueakImage reloaded = new SqueakImage(target.toFile());
            assertNotNull(reloaded.specialObjectsArray);
            assertFalse(reloaded.specialObjectsArray.pointersSize() == 0,
                    "special objects array should not be empty");
        } finally {
            deleteImage(target);
        }
    }
}
