package org.dodotalk.core;

import org.dodotalk.TestImages;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Image-loading regression tests using user-supplied images.
 * <p>
 * The repository does not bundle a Squeak image. Set
 * {@code JSQUEAK_TEST_FULL_IMAGE} / {@code JSQUEAK_TEST_MINI_IMAGE} to run
 * these tests; otherwise they are skipped.
 */
class SqueakImageLoadTest {

    @Test
    void testLoadFullImageFromFile() throws Exception {
        SqueakImage image = new SqueakImage(TestImages.fullImage());

        assertNotNull(image, "SqueakImage instance must not be null");
        assertNotNull(image.specialObjectsArray, "specialObjectsArray must not be null");
    }

    @Test
    void testLoadFullImageCreatesVMSuccessfully() throws Exception {
        SqueakImage image = new SqueakImage(TestImages.fullImage());

        // Creating the VM initializes all core objects.
        new SqueakVM(image);

        assertNotNull(SqueakVM.nilObj, "nilObj must be initialized");
        assertNotNull(SqueakVM.trueObj, "trueObj must be initialized");
        assertNotNull(SqueakVM.falseObj, "falseObj must be initialized");

        assertNotSame(SqueakVM.trueObj, SqueakVM.falseObj,
                "trueObj and falseObj must be different");
        assertNotSame(SqueakVM.nilObj, SqueakVM.trueObj,
                "nilObj and trueObj must be different");
        assertNotSame(SqueakVM.nilObj, SqueakVM.falseObj,
                "nilObj and falseObj must be different");

        assertNotNull(SqueakVM.specialObjects, "specialObjects must not be null");
        assertTrue(SqueakVM.specialObjects.length > 30,
                "specialObjects must contain enough entries");
        assertNotNull(SqueakVM.specialSelectors, "specialSelectors must not be null");
        assertTrue(SqueakVM.specialSelectors.length > 0,
                "specialSelectors must contain selectors");

        SqueakObject classInteger = (SqueakObject) SqueakVM.specialObjects[Squeak.splOb_ClassInteger];
        SqueakObject classString = (SqueakObject) SqueakVM.specialObjects[Squeak.splOb_ClassString];
        SqueakObject classArray = (SqueakObject) SqueakVM.specialObjects[Squeak.splOb_ClassArray];
        SqueakObject classFloat = (SqueakObject) SqueakVM.specialObjects[Squeak.splOb_ClassFloat];

        assertNotNull(classInteger, "ClassInteger must be loaded");
        assertNotNull(classString, "ClassString must be loaded");
        assertNotNull(classArray, "ClassArray must be loaded");
        assertNotNull(classFloat, "ClassFloat must be loaded");

        SqueakObject schedulerAssn = (SqueakObject) SqueakVM.specialObjects[
                Squeak.splOb_SchedulerAssociation];
        assertNotNull(schedulerAssn, "SchedulerAssociation must be loaded");

        SqueakObject scheduler = schedulerAssn.getPointerNI(Squeak.Assn_value);
        assertNotNull(scheduler, "scheduler must not be null");

        SqueakObject activeProcess = scheduler.getPointerNI(Squeak.ProcSched_activeProcess);
        assertNotNull(activeProcess, "active process must not be null");

        SqueakObject activeContext = activeProcess.getPointerNI(Squeak.Proc_suspendedContext);
        assertNotNull(activeContext, "initial active context must not be null");
    }

    @Test
    void testLoadMiniImageFromFile() throws Exception {
        SqueakImage image = new SqueakImage(TestImages.miniImage());

        assertNotNull(image);
        assertNotNull(image.specialObjectsArray);

        new SqueakVM(image);

        assertNotNull(SqueakVM.nilObj);
        assertNotNull(SqueakVM.trueObj);
        assertNotNull(SqueakVM.falseObj);
    }

    @Test
    void testImageNameDropsGzipSuffix() throws Exception {
        File compressed = TestImages.fullImage();
        String name = compressed.getName();
        assertTrue(name.endsWith(".image.gz"),
                "the test image should be named *.image.gz: " + compressed);

        String logicalName = name.substring(0, name.length() - ".gz".length());
        File logical = new File(compressed.getParentFile(), logicalName);

        SqueakImage image = new SqueakImage(compressed);

        assertEquals(logical.getAbsolutePath(), image.imageName(),
                "Squeak must derive changesName from the logical image name");
    }

    @Test
    void testRuntimeDirectoryFollowsImageParent() throws Exception {
        File imageFile = TestImages.miniImage();
        SqueakImage image = new SqueakImage(imageFile);

        assertEquals(imageFile.getAbsoluteFile().getParentFile(),
                image.runtimeDirectory(),
                "vmPath must follow the selected image directory");
    }

    @Test
    void testPrimitiveVmPathUsesImageParent() throws Exception {
        File imageFile = TestImages.miniImage();
        SqueakImage image = new SqueakImage(imageFile);
        SqueakVM vm = new SqueakVM(image);

        int savedSp = vm.sp;
        try {
            vm.push(SqueakVM.nilObj);
            assertTrue(SqueakVM.primHandler.doPrimitive(142, 0));
            assertEquals(imageFile.getAbsoluteFile().getParentFile().getAbsolutePath()
                            + File.separator,
                    ((SqueakObject) vm.top()).asString());
        } finally {
            vm.sp = savedSp;
        }
    }
}
