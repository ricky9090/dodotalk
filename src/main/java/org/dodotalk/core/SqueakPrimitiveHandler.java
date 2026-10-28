/*
SqueakPrimitiveHandler.java
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

package org.dodotalk.core;

import org.dodotalk.SqueakConfig;
import org.dodotalk.uilts.RuntimeStats;
import org.dodotalk.uilts.SqueakLogger;
import org.dodotalk.display.Screen;

import java.awt.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;

import static org.dodotalk.core.SqueakVM.falseObj;
import static org.dodotalk.core.SqueakVM.trueObj;

/**
 * @author Daniel Ingalls
 * <p>
 * Implements the indexed primitives for the Squeak VM.
 */
public class SqueakPrimitiveHandler {
    /**
     * Primitives that are intentionally implemented as successful no-ops so
     * image shutdown/snapshot can complete before the corresponding subsystem
     * is ported. Search for this set when auditing remaining stubs.
     */
    private static final Set<Integer> NO_OP_STUB_PRIMITIVES = Set.of(172);

    private final PrimitiveFailedException PrimitiveFailed = new PrimitiveFailedException();

    private final SqueakVM vm;
    private final SqueakImage image;
    private final BitBlt bitbltTable;

    private final FileSystemPrimitives fileSystemPrimitives = new FileSystemPrimitives(this);

    private Screen theDisplay;
    private int[] displayBitmap;
    private int displayRaster;
    private byte[] displayBitmapInBytes;
    private int[] displayBitmapInInts;
    private int[] displayBitmapFromOrg;
    private int displayWidth;
    private int displayHeight;
    private int displayPitch;
    private int displayDepth;
    private int BWMask = 0;
    private boolean fullScreenWarningLogged;

    private final PrimitiveExecuteContext context = new PrimitiveExecuteContext();

    /** Classification of the last primitive failure, consumed by doPrimitive(). */
    private RuntimeStats.PrimitiveFailure lastFailureKind;


    // Its purpose of the at-cache is to allow fast (bytecode) access to at/atput code
    // without having to check whether this object has overridden at, etc.
    private final int atCacheSize = 32; // must be power of 2
    private final int atCacheMask = atCacheSize - 1; //...so this is a mask

    private AtCacheInfo[] atCache;
    private AtCacheInfo[] atPutCache;
    private AtCacheInfo nonCachedInfo;

    SqueakPrimitiveHandler(SqueakVM theVM) {
        vm = theVM;
        image = SqueakVM.image;
        bitbltTable = new BitBlt(vm);
        initAtCache();
    }

    /**
     * A singleton instance of this class should be thrown to signal that a
     * primitive has failed.
     */
    private static class PrimitiveFailedException extends RuntimeException {
    }

    private static class AtCacheInfo {
        SqueakObject array;
        int size;
        int ivarOffset;
        boolean convertChars;
    }

    private void initAtCache() {
        atCache = new AtCacheInfo[atCacheSize];
        atPutCache = new AtCacheInfo[atCacheSize];
        nonCachedInfo = new AtCacheInfo();
        for (int i = 0; i < atCacheSize; i++) {
            atCache[i] = new AtCacheInfo();
            atPutCache[i] = new AtCacheInfo();
        }
    }

    /**
     * Clear at-cache pointers (prior to GC).
     */
    void clearAtCache() {
        for (int i = 0; i < atCacheSize; i++) {
            atCache[i].array = null;
            atPutCache[i].array = null;
        }
    }

    private AtCacheInfo makeCacheInfo(AtCacheInfo[] atOrPutCache, Object atOrPutSelector, SqueakObject array, boolean convertChars, boolean includeInstVars) {
        //Make up an info object and store it in the atCache or the atPutCache.
        //If it's not cacheable (not a non-super send of at: or at:put:)
        //then return the info in nonCachedInfo.
        //Note that info for objectAt (includeInstVars) will have
        //a zero ivarOffset, and a size that includes the extra instVars
        AtCacheInfo info;
        boolean cacheable = (vm.verifyAtSelector == atOrPutSelector) //is at or atPut
                && (vm.verifyAtClass == array.getSqClass())         //not a super send
                && (array.format == 3 && vm.isContext(array));        //not a context (size can change)
        if (cacheable) {
            info = atOrPutCache[array.hashCode() & atCacheMask];
        } else {
            info = nonCachedInfo;
        }
        info.array = array;
        info.convertChars = convertChars;
        if (includeInstVars) {
            info.size = Math.max(0, indexableSize(array)) + array.instSize();
            info.ivarOffset = 0;
        } else {
            info.size = indexableSize(array);
            info.ivarOffset = (array.format < 6) ? array.instSize() : 0;
        }
        return info;
    }

    // Quick Sends from inner Interpreter
    boolean quickSendOther(Object rcvr, int lobits) {
        // QuickSendOther returns true if it succeeds
        context.reset();
        switch (lobits) {
            case 0x0:
                return popNandPushIfOK(2, primitiveAt(true, true, false)); // at:
            case 0x1:
                return popNandPushIfOK(3, primitiveAtPut(true, true, false)); // at:put:
            case 0x2:
                return popNandPushIfOK(1, primitiveSize()); // size
            case 0x3:
                return false; // next
            case 0x4:
                return false; // nextPut
            case 0x5:
                return false; // atEnd
            case 0x6:
                return pop2andDoBoolIfOK(primitiveEq(vm.stackValue(1), vm.stackValue(0))); // ==
            case 0x7:
                return popNandPushIfOK(1, vm.getClass(vm.top())); // class
            case 0x8:
                return popNandPushIfOK(2, primitiveBlockCopy()); // blockCopy:
            case 0x9:
                return primitiveBlockValue(0); // value
            case 0xa:
                return primitiveBlockValue(1); // value:
            case 0xb:
                return false; // do:
            case 0xc:
                return false; // new
            case 0xd:
                return false; // new:
            case 0xe:
                return false; // x
            case 0xf:
                return false; // y
            default:
                return false;
        }
    }

    private boolean primitiveEq(Object arg1, Object arg2) {
        // == must work for uninterned small ints
        if (InterpreterHelper.isSTInteger(arg1) && InterpreterHelper.isSTInteger(arg2)) {
            return ((Integer) arg1).intValue() == ((Integer) arg2).intValue();
        }
        return arg1 == arg2;
    }

    private Object primitiveBitAnd() {
        int rcvr = stackPos32BitValue(1);
        int arg = stackPos32BitValue(0);
        if (!context.success) {
            return SqueakVM.nilObj;
        }

        return pos32BitIntFor(rcvr & arg);
    }

    private Object primitiveBitOr() {
        int rcvr = stackPos32BitValue(1);
        int arg = stackPos32BitValue(0);
        if (!context.success) {
            return SqueakVM.nilObj;
        }

        return pos32BitIntFor(rcvr | arg);
    }

    private Object primitiveBitXor() {
        int rcvr = stackPos32BitValue(1);
        int arg = stackPos32BitValue(0);
        if (!context.success) {
            return SqueakVM.nilObj;
        }

        return pos32BitIntFor(rcvr ^ arg);
    }

    private Object primitiveBitShift() {
        int rcvr = stackPos32BitValue(1);
        int arg = stackInteger(0);
        if (!context.success) {
            return SqueakVM.nilObj;
        }

        return pos32BitIntFor(InterpreterHelper.safeShift(rcvr, arg));
    }

    private int doQuo(int rcvr, int arg) {
        if (arg == 0) {
            context.success = false;
            return 0;
        }

        if (rcvr > 0) {
            if (arg > 0) {
                return rcvr / arg;
            } else {
                return 0 - (rcvr / (0 - arg));
            }
        } else {
            if (arg > 0) {
                return 0 - ((0 - rcvr) / arg);
            } else {
                return (0 - rcvr) / (0 - arg);
            }
        }
    }

    /**
     * Dispatches a primitive and records diagnostics around it.
     * <p>
     * The failure kind is classified by the implementation: a missing branch
     * or TODO is {@link RuntimeStats.PrimitiveFailure#UNIMPLEMENTED}, a
     * rejected argument/state is {@link RuntimeStats.PrimitiveFailure#FAILED},
     * and an escaped Java exception is {@link RuntimeStats.PrimitiveFailure#EXCEPTION}.
     */
    boolean doPrimitive(int index, int argCount) {
        RuntimeStats stats = vm.getRuntimeStats();
        boolean collect = stats.isEnabled();
        if (collect) {
            stats.recordPrimitiveCall(index);
        }
        lastFailureKind = null;

        boolean success;
        try {
            success = doPrimitiveInternal(index, argCount);
        } catch (RuntimeException e) {
            lastFailureKind = RuntimeStats.PrimitiveFailure.EXCEPTION;
            if (collect) {
                stats.recordPrimitiveFailure(index, lastFailureKind);
            }
            throw e;
        }

        if (!success) {
            if (lastFailureKind == null) {
                // A helper rejected the operation without calling primitiveFail().
                lastFailureKind = RuntimeStats.PrimitiveFailure.FAILED;
            }
            if (collect) {
                stats.recordPrimitiveFailure(index, lastFailureKind);
            }
        }
        return success;
    }

    RuntimeStats.PrimitiveFailure getLastFailureKind() {
        return lastFailureKind;
    }

    /**
     * Reason for the most recent BitBlt/scanner failure, or an empty string
     * when the last load succeeded. Package-visible for regression tests.
     */
    String lastBitBltFailure() {
        return bitbltTable.lastFailure();
    }

    private boolean doPrimitiveInternal(int index, int argCount) {
        //SqueakLogger.log_D("doPrimitive: " + index + ", time: " + System.currentTimeMillis());
        context.reset();
        switch (index) {
            // 0..127

            /*
            "Integer Primitives (0-19)"
            (0 primitiveFail)
            (1 primitiveAdd)
            (2 primitiveSubtract)
            (3 primitiveLessThan)
            (4 primitiveGreaterThan)
            (5 primitiveLessOrEqual)
            (6 primitiveGreaterOrEqual)
            (7 primitiveEqual)
            (8 primitiveNotEqual)
            (9 primitiveMultiply)
            (10 primitiveDivide)
            (11 primitiveMod)
            (12 primitiveDiv)
            (13 primitiveQuo)
            (14 primitiveBitAnd)
            (15 primitiveBitOr)
            (16 primitiveBitXor)
            (17 primitiveBitShift)
            (18 primitiveMakePoint)
            (19 primitiveFail)
            */
            case 0:
                return primitiveIntentionalFail(0);
            case 1:
                return popNandPushIntIfOK(2, stackInteger(1) + stackInteger(0));  // Integer.add
            case 2:
                return popNandPushIntIfOK(2, stackInteger(1) - stackInteger(0));  // Integer.subtract
            case 3:
                return pop2andDoBoolIfOK(stackInteger(1) < stackInteger(0));  // Integer.less
            case 4:
                return pop2andDoBoolIfOK(stackInteger(1) > stackInteger(0));  // Integer.greater
            case 5:
                return pop2andDoBoolIfOK(stackInteger(1) <= stackInteger(0));  // Integer.leq
            case 6:
                return pop2andDoBoolIfOK(stackInteger(1) >= stackInteger(0));  // Integer.geq
            case 7:
                return pop2andDoBoolIfOK(stackInteger(1) == stackInteger(0));  // Integer.equal
            case 8:
                return pop2andDoBoolIfOK(stackInteger(1) != stackInteger(0));  // Integer.notequal
            case 9:
                return popNandPushIntIfOK(2, InterpreterHelper.safeMultiply(stackInteger(1), stackInteger(0)));  // Integer.multiply *
            case 10:
                return popNandPushIntIfOK(2, InterpreterHelper.quickDivide(stackInteger(1), stackInteger(0)));  // Integer.divide /  (fails unless exact exact)
            case 11:
                return popNandPushIntIfOK(2, InterpreterHelper.mod(stackInteger(1),stackInteger(0)));  // Integer.mod \\
            case 12:
                return popNandPushIntIfOK(2, InterpreterHelper.div(stackInteger(1), stackInteger(0)));  // Integer.div //
            case 13:
                return popNandPushIntIfOK(2, doQuo(stackInteger(1), stackInteger(0)));  // Integer.quo
            case 14:
                return popNandPushIfOK(2, primitiveBitAnd());  // SmallInt.bitAnd
            case 15:
                return popNandPushIfOK(2, primitiveBitOr());  // SmallInt.bitOr
            case 16:
                return popNandPushIfOK(2, primitiveBitXor());  // SmallInt.bitXor
            case 17:
                return popNandPushIfOK(2, primitiveBitShift());  // SmallInt.bitShift
            case 18:
                return primitiveMakePoint();
            case 19:
                return primitiveIntentionalFail(19);  // Guard primitive for simulation -- *must* fail

            /*
            "LargeInteger Primitives (20-39)"
            "32-bit logic is aliased to Integer prims above"
            (20 39 primitiveFail)
             */

            /*
            "Float Primitives (40-59)"
             */
            case 40:  // primitiveAsFloat
                return primitiveAsFloat();
            case 41:  // primitiveFloatAdd (Float +)
                return popNandPushFloatIfOK(2, stackFloat(1) + stackFloat(0));
            case 42:  // primitiveFloatSubtract (Float -)
                return popNandPushFloatIfOK(2, stackFloat(1) - stackFloat(0));
            case 43:  // primitiveFloatLessThan (Float <)
                return pop2andDoBoolIfOK(stackFloat(1) < stackFloat(0));
            case 44:  // primitiveFloatGreaterThan (Float >)
                return pop2andDoBoolIfOK(stackFloat(1) > stackFloat(0));
            case 45:  // primitiveFloatLessOrEqual (Float <=)
                return pop2andDoBoolIfOK(stackFloat(1) <= stackFloat(0));
            case 46:  // primitiveFloatGreaterOrEqual (Float >=)
                return pop2andDoBoolIfOK(stackFloat(1) >= stackFloat(0));
            case 47:  // primitiveFloatEqual (Float =)
                return pop2andDoBoolIfOK(stackFloat(1) == stackFloat(0));
            case 48:  // primitiveFloatNotEqual (Float !=)
                return pop2andDoBoolIfOK(stackFloat(1) != stackFloat(0));
            case 49:  // primitiveFloatMultiply (Float.mul)
                return popNandPushFloatIfOK(2, stackFloat(1) * stackFloat(0));
            case 50:  // primitiveFloatDivide (Float.div)
                return popNandPushFloatIfOK(2, safeFDiv(stackFloat(1), stackFloat(0)));
            case 51:  // primitiveTruncated
                return popNandPushIfOK(1, primitiveTruncate(stackFloat(0)));
            case 52:  // primitiveFractionalPart
                return popNandPushFloatIfOK(1, primitiveFractionalPart(stackFloat(0)));
            case 53:  // primitiveExponent (Float.exponent)
                return popNandPushIntIfOK(1, primitiveExponent(stackFloat(0)));
            case 54:  // primitiveTimesTwoPower (Float.timesTwoPower)
                return popNandPushFloatIfOK(1, primitiveTimesTwoPower(stackFloat(1), stackInteger(0)));
            case 55:  // primitiveSquareRoot
                return popNandPushFloatIfOK(1, Math.sqrt(stackFloat(0)));
            case 56:  // primitiveSine
                return popNandPushFloatIfOK(1, Math.sin(stackFloat(0)));
            case 57:  // primitiveArcTan
                return popNandPushFloatIfOK(1, Math.atan(stackFloat(0)));
            case 58:  // primitiveLogN (Float.ln)
                return popNandPushFloatIfOK(1, StrictMath.log(stackFloat(0)));
            case 59: // primitiveExp
                return popNandPushFloatIfOK(1, Math.exp(stackFloat(0)));

            /*
            "Subscript and Stream Primitives (60-67)"
            (60 primitiveAt)
            (61 primitiveAtPut)
            (62 primitiveSize)
            (63 primitiveStringAt)
            (64 primitiveStringAtPut)
            (65 primitiveNext)
            (66 primitiveNextPut)
            (67 primitiveAtEnd)
             */
            case 60:
                return popNandPushIfOK(2, primitiveAt(false, false, false)); // basicAt:
            case 61:
                return popNandPushIfOK(3, primitiveAtPut(false, false, false)); // basicAt:put:
            case 62:
                return popNandPushIfOK(1, primitiveSize()); // size
            case 63:
                return popNandPushIfOK(2, primitiveAt(false, true, false)); // basicAt:
            case 64:
                return popNandPushIfOK(3, primitiveAtPut(false, true, false)); // basicAt:put:
            case 65:
                return primitiveStreamNext();
            case 66:
                return primitiveStreamNextPut();
            case 67:
                return primitiveStreamAtEnd();

            /*
            "StorageManagement Primitives (68-79)"
            (68 primitiveObjectAt)
            (69 primitiveObjectAtPut)
            (70 primitiveNew)
            (71 primitiveNewWithArg)
            (72 primitiveFail)                  "Blue Book: primitiveBecome"
            (73 primitiveInstVarAt)
            (74 primitiveInstVarAtPut)
            (75 primitiveAsOop)
            (76 primitiveFail)                   "Blue Book: primitiveAsObject"
            (77 primitiveSomeInstance)
            (78 primitiveNextInstance)
            (79 primitiveNewMethod)
             */
            case 68:
                return popNandPushIfOK(2, primitiveAt(false, false, true)); // Method.objectAt:
            case 69:
                return popNandPushIfOK(3, primitiveAtPut(false, false, true)); // Method.objectAt:put:
            case 70:
                return popNandPushIfOK(1, vm.instantiateClass(stackNonInteger(0), 0)); // Class.new
            case 71:
                return popNandPushIfOK(2, primitiveNewWithSize()); // Class.new
            case 72:
                return popNandPushIfOK(2, primitiveArrayBecome(false));
            case 73:
                return popNandPushIfOK(2, primitiveAt(false, false, true)); // instVarAt:
            case 74:
                return popNandPushIfOK(3, primitiveAtPut(false, false, true)); // instVarAt:put:
            case 75:
                return popNandPushIfOK(1, primitiveHash()); // Class.identityHash
            case 77:
                return popNandPushIfOK(1, primitiveSomeInstance(stackNonInteger(0))); // Class.someInstance
            case 78:
                return popNandPushIfOK(1, primitiveNextInstance(stackNonInteger(0))); // Class.someInstance
            case 79:
                return popNandPushIfOK(3, primitiveNewMethod()); // Compiledmethod.new

            /*
            "Control Primitives (80-89)"
            (80 primitiveFail)                      "Blue Book:  primitiveBlockCopy"
            (81 primitiveValue)
            (82 primitiveValueWithArgs)
            (83 primitivePerform)
            (84 primitivePerformWithArgs)
            (85 primitiveSignal)
            (86 primitiveWait)
            (87 primitiveResume)
            (88 primitiveSuspend)
            (89 primitiveFlushCache)
             */
            case 80:
                return popNandPushIfOK(2, primitiveBlockCopy()); // Context.blockCopy:
            case 81:
                return primitiveBlockValue(argCount); // BlockContext.value
            case 83:
                return vm.primitivePerform(argCount); // rcvr.perform:(with:)*
            case 84:
                return vm.primitivePerformWithArgs(vm.getClass(vm.stackValue(2))); // rcvr.perform:withArguments:
            case 85:
                return semaphoreSignal(); // Semaphore.wait
            case 86:
                return semaphoreWait(); // Semaphore.wait
            case 87:
                return processResume(); // Process.resume
            case 88:
                return processSuspend(); // Process.suspend
            case 89:
                return vm.clearMethodCache();  // selective

            /*
            "Input/Output Primitives (90-109)"
            (90 primitiveMousePoint)
            (91 primitiveFail)                  "Blue Book: primitiveCursorLocPut"
            (92 primitiveFail)                  "Blue Book: primitiveCursorLink"
            (93 primitiveInputSemaphore)
            (94 primitiveFail)                  "Blue Book: primitiveSampleInterval"
            (95 primitiveInputWord)
            (96 primitiveCopyBits)
            (97 primitiveSnapshot)
            (98 primitiveFail)                  "Blue Book: primitiveTimeWordsInto"
            (99 primitiveFail)                  "Blue Book: primitiveTickWordsInto"
            (100 primitiveFail)                 "Blue Book: primitiveSignalAtTick"
            (101 primitiveBeCursor)
            (102 primitiveBeDisplay)
            (103 primitiveScanCharacters)
            (104 primitiveDrawLoop)
            (105 primitiveStringReplace)
            (106 primitiveScreenSize)
            (107 primitiveMouseButtons)
            (108 primitiveKbdNext)
            (109 primitiveKbdPeek)
             */
            case 90:
                return popNandPushIfOK(1, primitiveMousePoint()); // mousePoint
            case 91:
                return primitiveIntentionalFail(91);  // Blue Book primitiveCursorLocPut
            case 96:
                if (argCount == 0) return primitiveCopyBits((SqueakObject) vm.top(), 0);
                else return primitiveCopyBits((SqueakObject) vm.stackValue(1), 1);
            case 97:
                primitiveSnapshot();
                // Squeak's primitiveSnapshot leaves falseObj on success
                // ("resuming" is false in the process that saves the image).
                return popNandPushIfOK(1, falseObj);
            case 100:
                return vm.primitivePerformInSuperclass((SqueakObject) vm.top()); // rcvr.perform:withArguments:InSuperclass
            case 101:
                return beCursor(argCount); // Cursor.beCursor
            case 102:
                return beDisplay(argCount); // DisplayScreen.beDisplay
            case 103:
                return primitiveScanCharacters(argCount);
            case 105:
                return popNandPushIfOK(5, primitiveStringReplace()); // string and array replace
            case 106:
                return primitiveScreenSize();
            case 107:
                return popNandPushIfOK(1, primitiveMouseButtons()); // Sensor mouseButtons
            case 108:
                return popNandPushIfOK(1, primitiveKbdNext()); // Sensor kbdNext
            case 109:
                return popNandPushIfOK(1, primitiveKbdPeek()); // Sensor kbdPeek

            /*
            "System Primitives (110-119)"
            (110 primitiveEquivalent)
            (111 primitiveClass)
            (112 primitiveBytesLeft)
            (113 primitiveQuit)
            (114 primitiveExitToDebugger)
            (115 primitiveFail)                 "Blue Book: primitiveOopsLeft"
            (116 primitiveFail)
            (117 primitiveFail)
            (118 primitiveDoPrimitiveWithArgs)
            (119 primitiveFlushCacheSelective)
             */
            case 110:
                return popNandPushIfOK(2, (vm.stackValue(1) == vm.stackValue(0)) ? trueObj : falseObj); // ==
            case 112:
                return popNandPushIfOK(1, InterpreterHelper.smallFromInt(SqueakVM.objectMemory.spaceLeft())); // bytesLeft
            case 113: {
                System.exit(0);
                return true;
            }
            case 116:
                return vm.flushMethodCacheForMethod((SqueakObject) vm.top());  // after Squeak 2.2 uses 119
            case 119:
                return vm.flushMethodCacheForSelector((SqueakObject) vm.top());  // before Squeak 2.3 uses 116

            /*
            "Miscellaneous Primitives (120-127)"
            (120 primitiveFail)
            (121 primitiveImageName)
            (122 primitiveNoop)                 "Blue Book: primitiveImageVolume"
            (123 primitiveFail)
            (124 primitiveLowSpaceSemaphore)
            (125 primitiveSignalAtBytesLeft)
             */
            case 120:
                return primitiveIntentionalFail(120);
            case 121:
                return popNandPushIfOK(1 + argCount, primitiveImageFileName(argCount)); //imageName
            case 122: {
                BWMask = ~BWMask;
                return true;
            }
            case 123:
                return primitiveIntentionalFail(123);
            case 124:
                return popNandPushIfOK(2, registerSemaphore(Squeak.splOb_TheLowSpaceSemaphore));
            case 125:
                return popNandPushIfOK(2, setLowSpaceThreshold());

            // "Squeak Primitives Start Here"

            /*
            "Squeak Miscellaneous Primitives (128-149)"
            (126 primitiveDeferDisplayUpdates)
            (127 primitiveShowDisplayRect)
            (128 primitiveArrayBecome)
            (129 primitiveSpecialObjectsOop)
            (130 primitiveFullGC)
            (131 primitiveIncrementalGC)
            (132 primitiveObjectPointsTo)
            (133 primitiveSetInterruptKey)
            (134 primitiveInterruptSemaphore)
            (135 primitiveMillisecondClock)
            (136 primitiveSignalAtMilliseconds)
            (137 primitiveSecondsClock)
            (138 primitiveSomeObject)
            (139 primitiveNextObject)
            (140 primitiveBeep)
            (141 primitiveClipboardText)
            (142 primitiveVMPath)
            (143 primitiveShortAt)
            (144 primitiveShortAtPut)
            (145 primitiveConstantFill)
            (146 primitiveReadJoystick)
            (147 primitiveWarpBits)
            (148 primitiveClone)
            (149 primitiveGetAttribute)
             */
            case 126:  // primitiveDeferDisplayUpdates
                return primitiveDeferDisplayUpdates(argCount);
            case 127:
                return primitiveShowDisplayRect(argCount);
            case 128:
                return popNandPushIfOK(2, primitiveArrayBecome(true));
            case 129:
                return popNandPushIfOK(1, image.specialObjectsArray);
            case 130:
                return popNandPushIfOK(1, InterpreterHelper.smallFromInt(SqueakVM.objectMemory.fullGC())); // GC
            case 131:
                return popNandPushIfOK(1, InterpreterHelper.smallFromInt(SqueakVM.objectMemory.partialGC())); // GCmost
            case 132:
                return primitiveObjectPointsTo();
            case 133:
                return primitiveSetInterruptKey(argCount);
            case 134:
                return popNandPushIfOK(2, registerSemaphore(Squeak.splOb_TheInterruptSemaphore));
            case 135:
                return popNandPushIfOK(1, millisecondClockValue());
            case 136:
                return popNandPushIfOK(3, primitiveSignalAtMilliseconds()); //Delay signal:atMs:());
            case 137:
                return popNandPushIfOK(1, primSeconds()); //Seconds since Jan 1, 1901
            case 138:
                return popNandPushIfOK(1, primitiveSomeObject()); // Class.someInstance
            case 139:
                return popNandPushIfOK(1, primitiveNextObject(stackNonInteger(0))); // Class.someInstance
            case 140:
                return primitiveBeep();
            case 141:
                return primitiveClipboardText(argCount);
            case 142:
                return popNandPushIfOK(1, primitiveVmPath()); //vmPath
            case 148:
                return popNandPushIfOK(1, ((SqueakObject) vm.top()).cloneIn(image)); //imageName
            case 149:
                return popNandPushIfOK(2, SqueakVM.nilObj); //getAttribute

            /*
            "File Primitives (150-169)"
            (150 primitiveFileAtEnd)
            (151 primitiveFileClose)
            (152 primitiveFileGetPosition)
            (153 primitiveFileOpen)
            (154 primitiveFileRead)
            (155 primitiveFileSetPosition)
            (156 primitiveFileDelete)
            (157 primitiveFileSize)
            (158 primitiveFileWrite)
            (159 primitiveFileRename)
            (160 primitiveDirectoryCreate)
            (161 primitiveDirectoryDelimitor)
            (162 primitiveDirectoryLookup)
            (163 168 primitiveFail)
            (169 primitiveDirectorySetMacTypeAndCreator)
             */
            case 150:
            case 151:
            case 152:
            case 153:
            case 154:
            case 155:
            case 156:
            case 157:
            case 158:
            case 159:
            case 160:
            case 162:
            case 163:
            case 164:
            case 165:
            case 166:
            case 167:
            case 168:
            case 169:
                return doFilePrimitive(index, argCount);
            case 161:
                return popNandPushIfOK(1, fileSystemPrimitives.directoryDelimitor()); //path delimiter

            /*
            "Sound Primitives (170-199)"
            (170 primitiveSoundStart)
            (171 primitiveSoundStartWithSemaphore)
            (172 primitiveSoundStop)
            (173 primitiveSoundAvailableSpace)
            (174 primitiveSoundPlaySamples)
            (175 primitiveSoundPlaySilence)		"obsolete; will be removed in the future"
            (176 primWaveTableSoundmixSampleCountintostartingAtpan)
            (177 primFMSoundmixSampleCountintostartingAtpan)
            (178 primPluckedSoundmixSampleCountintostartingAtpan)
            (179 primSampledSoundmixSampleCountintostartingAtpan)
            (180 primFMSoundmixSampleCountintostartingAtleftVolrightVol)
            (181 primPluckedSoundmixSampleCountintostartingAtleftVolrightVol)
            (182 primSampledSoundmixSampleCountintostartingAtleftVolrightVol)
            (183 primReverbSoundapplyReverbTostartingAtcount)
            (184 primLoopedSampledSoundmixSampleCountintostartingAtleftVolrightVol)
            (185 188 primitiveFail)
            (189 primitiveSoundInsertSamples)
            (190 primitiveSoundStartRecording)
            (191 primitiveSoundStopRecording)
            (192 primitiveSoundGetRecordingSampleRate)
            (193 primitiveSoundRecordSamples)
            (194 primitiveSoundSetRecordLevel)
            (195 199 primitiveFail)
             */

            case 172:  // primitiveSoundStop
                // Sound output is not ported yet, but SoundPlayer>>shutDown
                // calls primSoundStop while preparing a snapshot. Returning
                // success here lets snapshot:andQuit: reach Primitive 97.
                return noOpPrimitiveStub(index, "primSoundStop");

            /*
            "Networking Primitives (200-229)"
            (200 primitiveInitializeNetwork)
            (201 primitiveResolverStartNameLookup)
            (202 primitiveResolverNameLookupResult)
            (203 primitiveResolverStartAddressLookup)
            (204 primitiveResolverAddressLookupResult)
            (205 primitiveResolverAbortLookup)
            (206 primitiveResolverLocalAddress)
            (207 primitiveResolverStatus)
            (208 primitiveResolverError)
            (209 primitiveSocketCreate)
            (210 primitiveSocketDestroy)
            (211 primitiveSocketConnectionStatus)
            (212 primitiveSocketError)
            (213 primitiveSocketLocalAddress)
            (214 primitiveSocketLocalPort)
            (215 primitiveSocketRemoteAddress)
            (216 primitiveSocketRemotePort)
            (217 primitiveSocketConnectToPort)
            (218 primitiveSocketListenOnPort)
            (219 primitiveSocketCloseConnection)
            (220 primitiveSocketAbortConnection)
            (221 primitiveSocketReceiveDataBufCount)
            (222 primitiveSocketReceiveDataAvailable)
            (223 primitiveSocketSendDataBufCount)
            (224 primitiveSocketSendDone)
            (225 229 primitiveFail)
             */

            /*
            "Other Primitives (230-249)"
            (230 primitiveRelinquishProcessor)
            (231 primitiveForceDisplayUpdate)
            (232 primitiveFormPrint)
            (233 primitiveSetFullScreen)
            (234 primBitmapdecompressfromByteArrayat)
            (235 primStringcomparewithcollated)
            (236 primSampledSoundconvert8bitSignedFromto16Bit)
            (237 primBitmapcompresstoByteArray)
            (238 primitiveSerialPortOpen)
            (239 primitiveSerialPortClose)
            (240 primitiveSerialPortWrite)
            (241 primitiveSerialPortRead)
            (242 primitiveFail)
            (243 primStringtranslatefromtotable)
            (244 primStringfindFirstInStringinSetstartingAt)
            (245 primStringindexOfAsciiinStringstartingAt)
            (246 249 primitiveFail)
             */
            case 230:
                return primitiveYield(argCount); //yield for 10ms
            case 231:
                return primitiveForceDisplayUpdate(argCount);
            case 233:
                return primitiveSetFullScreen();
            case 234:
                return primitiveBitmapDecompress();
            case 235:
                return primitiveStringCompareWithCollated(argCount);
            case 242:
            case 243:
            case 244:
            case 245:
            case 246:
            case 247:
            case 248:
            case 249:
                return primitiveFailNoLog(index);

            /*
            "VM Implementor Primitives (250-255)"
            (250 clearProfile)
            (251 dumpProfile)
            (252 startProfiling)
            (253 stopProfiling)
            (254 primitiveVMParameter)
            (255 primitiveFail)
             */

            /*
            "Quick Push Const Methods"
            (256 primitivePushSelf)
            (257 primitivePushTrue)
            (258 primitivePushFalse)
            (259 primitivePushNil)
            (260 primitivePushMinusOne)
            (261 primitivePushZero)
            (262 primitivePushOne)
            (263 primitivePushTwo)
             */

            /*
            "Quick Push Const Methods"
            (264 519 primitiveLoadInstVar)
             */

            /*
            "MIDI Primitives (520-539)"
            (520 primitiveFail)
            (521 primitiveMIDIClosePort)
            (522 primitiveMIDIGetClock)
            (523 primitiveMIDIGetPortCount)
            (524 primitiveMIDIGetPortDirectionality)
            (525 primitiveMIDIGetPortName)
            (526 primitiveMIDIOpenPort)
            (527 primitiveMIDIParameterGetOrSet)
            (528 primitiveMIDIRead)
            (529 primitiveMIDIWrite)
            (530 539 primitiveFail)  "reserved for extended MIDI primitives"
             */

            /*
            "Experimental Asynchrous File Primitives"
            (540 primitiveAsyncFileClose)
            (541 primitiveAsyncFileOpen)
            (542 primitiveAsyncFileReadResult)
            (543 primitiveAsyncFileReadStart)
            (544 primitiveAsyncFileWriteResult)
            (545 primitiveAsyncFileWriteStart)
             */

            /*
            "Unassigned Primitives"
            (546 700 primitiveFail)).
             */
            case 699:
                primitiveDebug();
                break;
            default:
                // LargeInteger primitives 20-39 are documented as
                // primitiveFail in Squeak 2.2; do not count them as missing
                // work. Every other unassigned index is an implementation gap.
                lastFailureKind = (index >= 20 && index <= 39)
                        ? RuntimeStats.PrimitiveFailure.INTENTIONAL
                        : RuntimeStats.PrimitiveFailure.UNIMPLEMENTED;
                if (SqueakConfig.PRIMITIVE_FAILED_LOGGING) {
                    SqueakLogger.log_D("primitiveFailed at: " + index);
                }
                return false;
        }
        return context.success;
    }

    /**
     * Dispatches the file primitives. {@link FileSystemPrimitives} uses an
     * exception for primitive failure, so translate it back into the normal
     * "primitive failed, run the Smalltalk fallback" result instead of letting
     * a missing file or a bad argument escape as a Java exception.
     */
    private boolean doFilePrimitive(int index, int argCount) {
        try {
            switch (index) {
                case 150:
                    return popNandPushIfOK(2, fileSystemPrimitives.fileAtEnd(argCount));
                case 151:
                    return popNandPushIfOK(2, fileSystemPrimitives.fileClose(argCount));
                case 152:
                    return popNandPushIfOK(2, fileSystemPrimitives.getPosition(argCount));
                case 153:
                    return popNandPushIfOK(3, fileSystemPrimitives.openWritable(argCount));
                case 154:
                    return popNandPushIfOK(5, fileSystemPrimitives.readIntoStartingAtCount(argCount));
                case 155:
                    return popNandPushIfOK(3, fileSystemPrimitives.fileSetPosition(argCount));
                case 156:
                    return popNandPushIfOK(2, fileSystemPrimitives.fileDelete(argCount));
                case 157:
                    return popNandPushIfOK(2, fileSystemPrimitives.fileSize(argCount));
                case 158:
                    return popNandPushIfOK(5, fileSystemPrimitives.fileWrite(argCount));
                case 159:
                    return popNandPushIfOK(3, fileSystemPrimitives.fileRename(argCount));
                case 160:
                    return popNandPushIfOK(2, fileSystemPrimitives.directoryCreate(argCount));
                case 162:
                    return popNandPushIfOK(3, fileSystemPrimitives.lookupEntryInIndex(argCount));
                default:
                    // 163-168 are reserved; 169 sets Mac type/creator, which
                    // has no meaning on this platform.
                    return primitiveFailNoLog(index);
            }
        } catch (PrimitiveFailedException e) {
            return false;
        } catch (RuntimeException e) {
            // A malformed file argument must become a Smalltalk primitive
            // failure, not escape and terminate the VM.
            SqueakLogger.log_E("File primitive " + index + " failed", e);
            return false;
        }
    }

    /**
     * snapshotPrimitive
     * "Primitive. Write the current state of the object memory on a file in the
     * same format as the Smalltalk-80 release. The file can later be resumed,
     * returning you to this exact state. Return normally after writing the file.
     * Essential. See Object documentation whatIsAPrimitive."
     * <p>
     * <primitive: 97>
     * ^nil "indicates error writing image file"
     */
    private void primitiveSnapshot() {
        try {
            SqueakVM.image.save(SqueakVM.image.imageFile());
        } catch (IOException e) {
            context.success = false;
            SqueakLogger.log_E("Failed to save the image to "
                    + SqueakVM.image.imageFile().getAbsolutePath(), e);
        }
    }

    /**
     * Primitive 121
     * "When called with a single string argument, record the string
     * as the current image file name. When called with zero
     * arguments, return a string containing the current image file
     * name."
     */
    private Object primitiveImageFileName(int argCount) {
        if (argCount == 0) {
            return makeStString(SqueakVM.image.imageName());
        }

        if (argCount == 1) {
            Object name = vm.stackValue(0);
            if (name instanceof SqueakObject
                    && isA(name, Squeak.splOb_ClassString)) {
                String imageName = ((SqueakObject) name).asString();
                try {
                    SqueakVM.image.setImageName(imageName);
                    return stackReceiver(1);
                } catch (IllegalArgumentException e) {
                    SqueakLogger.log_W("Cannot set image name to '"
                            + imageName + "': " + e.getMessage());
                }
            }
        }

        context.success = false;
        return null;
    }

    /**
     * SystemDictionary>>vmPath.
     * Primitive 142.
     * <p>
     * primVmPath
     * "Answer the path for the directory containing the Smalltalk virtual machine.
     * Return the empty string if this primitive is not implemented."
     * "Smalltalk vmPath"
     * <p>
     * <primitive: 142>
     * ^ ''
     */
    private SqueakObject primitiveVmPath() {
        java.io.File runtimeDirectory = SqueakVM.image == null
                ? null
                : SqueakVM.image.runtimeDirectory();
        String path = (runtimeDirectory == null
                ? SqueakImage.defaultSnapshotDirectory()
                : runtimeDirectory).getAbsolutePath();
        if (!path.endsWith(java.io.File.separator)) {
            path += java.io.File.separator;
        }
        return makeStString(path);
    }

    /*private boolean pop2andDoBool(boolean bool) {
        vm.success = true; // FIXME: Why have a side effect here?
        success = true;
        return vm.pushBoolAndPeek(bool);
    }*/


    /*private void popNandPush(int nToPop, Object returnValue) {
        if (returnValue == null)
            new Exception("NULL in popNandPush()").printStackTrace(); // FIXME: Did I break this by not checking for a null return value?

        vm.popNandPush(nToPop, returnValue);
    }*/

    /*private void popNandPushInt(int nToPop, int returnValue) {
        Integer value = SqueakVM.smallFromInt(returnValue);
        if (value == null)
            this.success = false;

        popNandPush(nToPop, value);
    }*/

    /*private void popNandPushFloat(int nToPop, double returnValue) {

        popNandPush(nToPop, makeFloat(returnValue));
    }*/

    boolean stackBool(int nDeep) {
        return checkBool(vm.stackValue(nDeep));
    }

    private boolean checkBool(Object maybeBool) {
        if (maybeBool == trueObj) {
            return true;
        }
        if (maybeBool == falseObj) {
            return false;
        }
        context.success = false;
        return false;
    }

    int stackInteger(int nDeep) {
        return checkSmallInt(vm.stackValue(nDeep));
    }

    /**
     * If maybeSmall is a small integer, return its value, fail otherwise.
     */
    private int checkSmallInt(Object maybeSmall) {
        if (InterpreterHelper.isSTInteger(maybeSmall)) {
            return (Integer) maybeSmall;
        }

        context.success = false;
        return 0;
    }

    private double stackFloat(int nDeep) {
        return checkFloat(vm.stackValue(nDeep));
    }

    /**
     * If maybeFloat is a Squeak Float return its value, fail otherwise
     */
    private double checkFloat(Object maybeFloat) {
        if (vm.getClass(maybeFloat) == SqueakVM.specialObjects[Squeak.splOb_ClassFloat]) {
            return ((SqueakObject) maybeFloat).getFloatBits();
        }

        // Squeak supplies SmallInt arguments to Float primitives (for example
        // Float>>timesTwoPower:), so accepting integers here is intentional.
        // A non-numeric argument still fails below.
        if (InterpreterHelper.isSTInteger(maybeFloat)) {
            return ((Integer) maybeFloat).doubleValue();
        }

        context.success = false;
        return 0.0d;
    }

    private double safeFDiv(double dividend, double divisor) {
        if (divisor == 0.0d) {
            context.success = false;
            return 1.0d;
        }

        return dividend / divisor;
    }

    /**
     * Fail if maybeSmall is not a SmallInteger
     *
     * @param maybeSmall
     * @return
     */
    private SqueakObject checkNonSmallInt(Object maybeSmall) {
        // returns a SqObj and sets success

        if (InterpreterHelper.isSTInteger(maybeSmall)) {
            context.success = false;
            return SqueakVM.nilObj;
        }

        return (SqueakObject) maybeSmall;
    }

    int stackPos32BitValue(int nDeep) {
        Object stackVal = vm.stackValue(nDeep);
        if (InterpreterHelper.isSTInteger(stackVal)) {
            int value = (Integer) stackVal;
            if (value >= 0) {
                return value;
            }

            context.success = false;
            return 0;
        }

        if (!isA(stackVal, Squeak.splOb_ClassLargePositiveInteger)) {
            context.success = false;
            return 0;
        }


        byte[] bytes = (byte[]) ((SqueakObject) stackVal).bits;
        int value = 0;
        for (int i = 0; i < 4; i++) {
            value = value + ((bytes[i] & 255) << (8 * i));
        }
        return value;
    }

    Object pos32BitIntFor(long pos32Val) {
        if (pos32Val < Integer.MIN_VALUE ||
                pos32Val > Integer.MAX_VALUE) {
            SqueakLogger.log_E("long to int overflow");
            context.success = false;
        }

        return pos32BitIntFor((int) pos32Val);
    }

    Object pos32BitIntFor(int pos32Val) {
        // Return the 32-bit quantity as a positive 32-bit integer
        if (pos32Val >= 0) {
            Object smallInt = InterpreterHelper.smallFromInt(pos32Val);
            if (smallInt != null) {
                return smallInt;
            }
        }
        SqueakObject lgIntClass = (SqueakObject) SqueakVM.specialObjects[Squeak.splOb_ClassLargePositiveInteger];
        SqueakObject lgIntObj = vm.instantiateClass(lgIntClass, 4);
        byte[] bytes = (byte[]) lgIntObj.bits;
        for (int i = 0; i < 4; i++) {
            bytes[i] = (byte) ((pos32Val >>> (8 * i)) & 255);
        }
        return lgIntObj;
    }

    SqueakObject stackNonInteger(int nDeep) {
        return checkNonSmallInt(vm.stackValue(nDeep));
    }

    SqueakObject squeakArray(Object[] javaArray) {
        SqueakObject array = vm.instantiateClass(Squeak.splOb_ClassArray, javaArray.length);
        for (int index = 0; index < javaArray.length; index++) {
            array.setPointer(index, javaArray[index]);
        }

        return array;
    }

    Object squeakSeconds(long millis) {
        int secs = (int) (millis / 1000); //milliseconds -> seconds
        secs += (69 * 365 + 17) * 24 * 3600; //Adjust from 1901 to 1970

        return pos32BitIntFor(secs);
    }


    SqueakObject squeakNil() {
        return SqueakVM.nilObj;
    }

    SqueakObject squeakBool(boolean bool) {
        return bool ? trueObj : falseObj;
    }

    /**
     * Note: this method does not check to see that the passed
     * object is an instance of Boolean.
     *
     * @return true iff object is the special Squeak true object
     */
    boolean javaBool(SqueakObject object) {
        return object == trueObj;
    }

    private boolean primitiveAsFloat() {
        int intValue = stackInteger(0);
        if (!context.success) {
            return false;
        }
        vm.popNandPush(1, makeFloat(intValue));
        return true;
    }

    private SqueakObject makeFloat(double value) {
        SqueakObject floatClass = (SqueakObject) SqueakVM.specialObjects[Squeak.splOb_ClassFloat];
        SqueakObject newFloat = vm.instantiateClass(floatClass, -1);
        newFloat.setFloatBits(value);
        return newFloat;
    }

    boolean primitiveMakePoint() {
        Object x = vm.stackValue(1);
        Object y = vm.stackValue(0);
        if (!InterpreterHelper.isSTInteger(x) || !InterpreterHelper.isSTInteger(y)) {
            context.success = false;
            return false;
        }

        vm.popNandPush(2, makePointWithXandY(x, y));
        return true;
    }

    private SqueakObject makePointWithXandY(Object x, Object y) {
        //SqueakLogger.log_D("make point x: " + x + ",  y: " + y);
        SqueakObject pointClass = (SqueakObject) SqueakVM.specialObjects[Squeak.splOb_ClassPoint];
        SqueakObject newPoint = vm.instantiateClass(pointClass, 0);
        newPoint.setPointer(Squeak.Point_x, x);
        newPoint.setPointer(Squeak.Point_y, y);
        return newPoint;
    }

    private SqueakObject primitiveNewWithSize() {
        int size = stackPos32BitValue(0);
        if (!context.success) {
            return SqueakVM.nilObj;
        }

        return vm.instantiateClass(((SqueakObject) vm.stackValue(1)), size);
    }

    private SqueakObject primitiveNewMethod() {
        Object headerInt = vm.top();
        int byteCount = stackInteger(1);
        int methodHeader = checkSmallInt(headerInt);
        if (!context.success) {
            return SqueakVM.nilObj;
        }
        int litCount = (methodHeader >> 9) & 0xFF;
        SqueakObject method = vm.instantiateClass(((SqueakObject) vm.stackValue(2)), byteCount);
        Object[] pointers = new Object[litCount + 1];
        Arrays.fill(pointers, SqueakVM.nilObj);
        pointers[0] = headerInt;
        method.methodAddPointers(pointers);
        return method;
    }


    //String and Array Primitives
    // FIXME: makeStString() but squeakBool() ? Pick one!
    SqueakObject makeStString(String javaString) {
        byte[] byteString = javaString.getBytes(StandardCharsets.UTF_8);
        SqueakObject stString = vm.instantiateClass(
                (SqueakObject) SqueakVM.specialObjects[Squeak.splOb_ClassString],
                byteString.length);
        System.arraycopy(byteString, 0, stString.bits, 0, byteString.length);
        return stString;
    }

    /*
    !Interpreter methodsFor: 'array and stream primitives'!
    primitiveSize

    | rcvr sz |
    rcvr _ self stackTop.
    (self isIntegerObject: rcvr)
        ifTrue: [sz _ 0]  "integers have no indexable fields"
        ifFalse: [sz _ self stSizeOf: rcvr].
    successFlag
        ifTrue: [self pop: 1. self pushInteger: sz]
        ifFalse: [self failSpecialPrim: 62].! !
     */
    /**
     * Returns size Integer (but may set success false)
     */
    private Object primitiveSize() {
        Object rcvr = vm.top();
        int size = indexableSize(rcvr);
        if (size == -1) {
            //not indexable
            context.success = false;
        }

        return pos32BitIntFor(size);
    }

    /**
     * Stream layout shared by Primitive 65-67.
     */
    private static final class StreamState {
        final SqueakObject array;
        final int index;
        final int limit;
        final boolean stringy;

        StreamState(SqueakObject array, int index, int limit, boolean stringy) {
            this.array = array;
            this.index = index;
            this.limit = limit;
            this.stringy = stringy;
        }
    }

    private StreamState loadStreamState(SqueakObject stream) {
        if (stream == null || stream.pointers == null || stream.pointersSize() < 3) {
            return null;
        }
        Object arrayObject = stream.getPointerNI(Squeak.Stream_array);
        Integer index = stream.getPointerI(Squeak.Stream_position);
        Integer limit = stream.getPointerI(Squeak.Stream_limit);
        if (!(arrayObject instanceof SqueakObject) || index == null || limit == null
                || index < 0 || limit < 0) {
            return null;
        }
        SqueakObject array = (SqueakObject) arrayObject;
        SqueakObject arrayClass = vm.getClass(array);
        boolean stringy = arrayClass == SqueakVM.specialObjects[Squeak.splOb_ClassString];
        if (!stringy && !isStreamArrayClass(arrayClass)) {
            return null;
        }
        return new StreamState(array, index, limit, stringy);
    }

    private static boolean isStreamArrayClass(SqueakObject arrayClass) {
        return arrayClass == SqueakVM.specialObjects[Squeak.splOb_ClassString]
                || arrayClass == SqueakVM.specialObjects[Squeak.splOb_ClassArray]
                || arrayClass == SqueakVM.specialObjects[Squeak.splOb_ClassByteArray]
                || arrayClass == SqueakVM.specialObjects[Squeak.splOb_ClassBitmap];
    }

    /**
     * Fetches a one-based element from a stream's backing collection. Byte and
     * word arrays are returned as SmallIntegers; pointer arrays keep their
     * object identity.
     */
    private Object streamElementAt(SqueakObject array, int oneBasedIndex) {
        if (oneBasedIndex < 1) {
            return null;
        }
        if (array.format < 6) {
            if (array.pointers == null || oneBasedIndex > array.pointersSize()) {
                return null;
            }
            return array.pointers[oneBasedIndex - 1];
        }
        if (array.format < 8) {
            if (!(array.bits instanceof int[]) || oneBasedIndex > array.bitsSize()) {
                return null;
            }
            return InterpreterHelper.smallFromInt(((int[]) array.bits)[oneBasedIndex - 1]);
        }
        if (array.format < 12) {
            if (!(array.bits instanceof byte[]) || oneBasedIndex > array.bitsSize()) {
                return null;
            }
            return InterpreterHelper.smallFromInt(((byte[]) array.bits)[oneBasedIndex - 1] & 0xFF);
        }
        return null;
    }

    private boolean storeStreamElement(SqueakObject array, int oneBasedIndex, Object value) {
        if (oneBasedIndex < 1) {
            return false;
        }
        if (array.format < 6) {
            array.pointers[oneBasedIndex - 1] = value;
            return true;
        }
        if (array.format < 8) {
            int intValue = checkSmallInt(value);
            if (!context.success) {
                return false;
            }
            ((int[]) array.bits)[oneBasedIndex - 1] = intValue;
            return true;
        }
        if (array.format < 12) {
            int intValue = checkSmallInt(value);
            if (!context.success) {
                return false;
            }
            ((byte[]) array.bits)[oneBasedIndex - 1] = (byte) intValue;
            return true;
        }
        return false;
    }

    private int characterAscii(Object value) {
        if (!(value instanceof SqueakObject)) {
            return -1;
        }
        SqueakObject character = (SqueakObject) value;
        if (vm.getClass(character) != SqueakVM.specialObjects[Squeak.splOb_ClassCharacter]) {
            return -1;
        }
        Integer ascii = character.getPointerI(0);
        return ascii == null ? -1 : ascii;
    }

    private boolean primitiveStreamNext() {
        SqueakObject stream = stackNonInteger(0);
        if (!context.success) {
            return false;
        }
        StreamState state = loadStreamState(stream);
        if (state == null) {
            context.success = false;
            return false;
        }
        int size = indexableSize(state.array);
        if (state.index >= state.limit || state.index >= size) {
            context.success = false;
            return false;
        }

        Object element = streamElementAt(state.array, state.index + 1);
        if (element == null) {
            context.success = false;
            return false;
        }

        Object result = element;
        if (state.stringy) {
            int ascii = checkSmallInt(element);
            if (!context.success) {
                return false;
            }
            result = charFromInt(ascii);
        }
        stream.setPointer(Squeak.Stream_position,
                InterpreterHelper.smallFromInt(state.index + 1));
        return popNandPushIfOK(1, result);
    }

    private boolean primitiveStreamNextPut() {
        Object value = vm.stackValue(0);
        SqueakObject stream = stackNonInteger(1);
        if (!context.success) {
            return false;
        }
        StreamState state = loadStreamState(stream);
        if (state == null) {
            context.success = false;
            return false;
        }
        int size = indexableSize(state.array);
        if (state.index >= state.limit || state.index >= size) {
            context.success = false;
            return false;
        }

        Object valueToStore = value;
        if (state.stringy) {
            int ascii = characterAscii(value);
            if (ascii < 0) {
                context.success = false;
                return false;
            }
            valueToStore = InterpreterHelper.smallFromInt(ascii);
        }
        if (!storeStreamElement(state.array, state.index + 1, valueToStore)) {
            context.success = false;
            return false;
        }
        stream.setPointer(Squeak.Stream_position,
                InterpreterHelper.smallFromInt(state.index + 1));

        // nextPut: answers its argument and leaves it on the stack.
        return popNandPushIfOK(2, value);
    }

    private boolean primitiveStreamAtEnd() {
        SqueakObject stream = stackNonInteger(0);
        if (!context.success) {
            return false;
        }
        StreamState state = loadStreamState(stream);
        if (state == null) {
            context.success = false;
            return false;
        }
        int size = indexableSize(state.array);
        boolean atEnd = state.index >= state.limit || state.index >= size;
        return popNandPushIfOK(1, atEnd ? trueObj : falseObj);
    }

    private Object primitiveAt(boolean cameFromAtBytecode, boolean convertChars, boolean includeInstVars) {
        //Returns result of at: or sets success false
        SqueakObject array = stackNonInteger(1);
        int index = stackPos32BitValue(0); //note non-int returns zero
        if (!context.success) {
            return array;
        }

        AtCacheInfo info;
        if (cameFromAtBytecode) {
            // fast entry checks cache
            info = atCache[array.hashCode() & atCacheMask];
            if (info.array != array) {
                context.success = false;
                return array;
            }

        } else {
            // slow entry installs in cache if appropriate
            if (array.format == 6 && isA(array, Squeak.splOb_ClassFloat)) {
                // hack to make Float hash work
                long floatBits = Double.doubleToRawLongBits(array.getFloatBits());
                if (index == 1) {
                    return pos32BitIntFor((int) (floatBits >>> 32));
                }
                if (index == 2) {
                    return pos32BitIntFor((int) (floatBits & 0xFFFFFFFF));
                }

                context.success = false;
                return array;
            }
            info = makeCacheInfo(atCache, SqueakVM.specialSelectors[32], array, convertChars, includeInstVars);
        }
        if (index < 1 || index > info.size) {
            context.success = false;
            return array;
        }


        if (includeInstVars) { //pointers...   instVarAt and objectAt
            return array.pointers[index - 1];
        }
        if (array.format < 6) {  //pointers...   normal at:
            return array.pointers[index - 1 + info.ivarOffset];
        }
        if (array.format < 8) {  // words...
            int value = ((int[]) array.bits)[index - 1];
            return pos32BitIntFor(value);
        }
        if (array.format < 12) { // bytes...
            int value = (((byte[]) array.bits)[index - 1]) & 0xFF;
            if (info.convertChars) {
                return charFromInt(value);
            } else {
                return InterpreterHelper.smallFromInt(value);
            }
        }
        // methods (format>=12) must simulate Squeak's method indexing
        int offset = array.pointersSize() * 4;
        if (index - 1 - offset < 0) { //reading lits as bytes
            context.success = false;
            return array;
        }

        return InterpreterHelper.smallFromInt((((byte[]) array.bits)[index - 1 - offset]) & 0xFF);
    }

    SqueakObject charFromInt(int ascii) {
        SqueakObject charTable = (SqueakObject) SqueakVM.specialObjects[Squeak.splOb_CharacterTable];
        return charTable.getPointerNI(ascii);
    }

    /**
     * @return result of at:put:
     */
    private Object primitiveAtPut(boolean cameFromAtBytecode, boolean convertChars, boolean includeInstVars) {
        SqueakObject array = stackNonInteger(2);
        int index = stackPos32BitValue(1); //note non-int returns zero
        if (!context.success) {
            return array;
        }

        AtCacheInfo info;
        if (cameFromAtBytecode) {
            // fast entry checks cache
            info = atPutCache[array.hashCode() & atCacheMask];
            if (info.array != array) {
                context.success = false;
                return array;
            }
        } else {
            // slow entry installs in cache if appropriate
            info = makeCacheInfo(atPutCache, SqueakVM.specialSelectors[34], array, convertChars, includeInstVars);
        }
        if (index < 1 || index > info.size) {
            context.success = false;
            return array;
        }

        Object objToPut = vm.stackValue(0);
        if (includeInstVars) {
            // pointers...   instVarAtPut and objectAtPut
            array.pointers[index - 1] = objToPut; //eg, objectAt:
            return objToPut;
        }
        if (array.format < 6) {
            // pointers...   normal atPut
            array.pointers[index - 1 + info.ivarOffset] = objToPut;
            return objToPut;
        }
        int intToPut;
        if (array.format < 8) {
            // words...
            intToPut = stackPos32BitValue(0);
            if (!context.success) {
                return objToPut;
            }

            ((int[]) array.bits)[index - 1] = intToPut;
            return objToPut;
        }
        // bytes...
        if (info.convertChars) {
            // put a character...
            if (InterpreterHelper.isSTInteger(objToPut)) {
                context.success = false;
                return objToPut;
            }

            SqueakObject sqObjToPut = (SqueakObject) objToPut;
            if ((sqObjToPut.sqClass != SqueakVM.specialObjects[Squeak.splOb_ClassCharacter])) {
                context.success = false;
                return objToPut;
            }

            Object asciiToPut = sqObjToPut.getPointer(0);
            if (!(InterpreterHelper.isSTInteger(asciiToPut))) {
                context.success = false;
                return objToPut;
            }

            intToPut = (Integer) asciiToPut;
        } else {
            // put a byte...
            if (!(InterpreterHelper.isSTInteger(objToPut))) {
                context.success = false;
                return objToPut;
            }

            intToPut = (Integer) objToPut;
        }
        if (intToPut < 0 || intToPut > 255) {
            context.success = false;
            return objToPut;
        }

        if (array.format < 8) {
            // bytes...
            ((byte[]) array.bits)[index - 1] = (byte) intToPut;
            return objToPut;
        }
        // methods (format>=12) must simulate Squeak's method indexing
        int offset = array.pointersSize() * 4;
        if (index - 1 - offset < 0) {
            context.success = false;   //writing lits as bytes
            return array;
        }

        ((byte[]) array.bits)[index - 1 - offset] = (byte) intToPut;
        return objToPut;
    }

    // FIXME: is this the same as SqueakObject.instSize() ?
    private int indexableSize(Object obj) {
        if (InterpreterHelper.isSTInteger(obj)) {
            return -1; // -1 means not indexable
        }
        SqueakObject sqObj = (SqueakObject) obj;
        short fmt = sqObj.format;
        if (fmt < 2) {
            return -1; //not indexable
        }
        if (fmt == 3 && vm.isContext(sqObj)) {
            return sqObj.getPointerI(Squeak.Context_stackPointer);
        }
        if (fmt < 6) {
            return sqObj.pointersSize() - sqObj.instSize(); // pointers
        }
        if (fmt < 12) {
            return sqObj.bitsSize(); // words or bytes
        }
        return sqObj.bitsSize() + (4 * sqObj.pointersSize());  // methods
    }

    private SqueakObject primitiveStringReplace() {
        SqueakObject dst = (SqueakObject) vm.stackValue(4);
        int dstPos = stackInteger(3) - 1;
        int count = stackInteger(2) - dstPos;
        //  if (count<=0) {success= false; return dst; } //fail for compat, later succeed
        SqueakObject src = (SqueakObject) vm.stackValue(1);
        int srcPos = stackInteger(0) - 1;
        if (!context.success) {
            return SqueakVM.nilObj; //some integer not right
        }
        short srcFmt = src.format;
        short dstFmt = dst.format;
        if (dstFmt < 8) {
            if (dstFmt != srcFmt) {
                //incompatible formats
                context.success = false;
                return dst;
            } else if ((dstFmt & 0xC) != (srcFmt & 0xC)) {
                //incompatible formats
                context.success = false;
                return dst;
            }
        }
        if (srcFmt < 4) {
            //pointer type objects
            int totalLength = src.pointersSize();
            int srcInstSize = src.instSize();
            srcPos += srcInstSize;
            if ((srcPos < 0) || (srcPos + count) > totalLength) {
                //would go out of bounds
                context.success = false;
                return SqueakVM.nilObj;
            }

            totalLength = dst.pointersSize();
            int dstInstSize = dst.instSize();
            dstPos += dstInstSize;
            if ((dstPos < 0) || (dstPos + count) > totalLength) {
                //would go out of bounds
                context.success = false;
                return SqueakVM.nilObj;
            }

            System.arraycopy(src.pointers, srcPos, dst.pointers, dstPos, count);
            return dst;
        } else {
            //bits type objects
            int totalLength = src.bitsSize();
            if ((srcPos < 0) || (srcPos + count) > totalLength) {
                //would go out of bounds
                context.success = false;
                return SqueakVM.nilObj;
            }
            totalLength = dst.bitsSize();
            if ((dstPos < 0) || (dstPos + count) > totalLength) {
                //would go out of bounds
                context.success = false;
                return SqueakVM.nilObj;
            }
            System.arraycopy(src.bits, srcPos, dst.bits, dstPos, count);
            return dst;
        }
    }

    //Not yet implemented...
    private boolean primitiveNext() {
        // PrimitiveNext should succeed only if the stream's array is in the atCache.
        // Otherwise failure will lead to proper message lookup of at: and
        // subsequent installation in the cache if appropriate."
        SqueakObject stream = stackNonInteger(0);
        if (!context.success) {
            return false;
        }
        Object[] streamBody = stream.pointers;
        if (streamBody == null || streamBody.length < (Squeak.Stream_limit + 1)) {
            return false;
        }
        Object array = streamBody[Squeak.Stream_array];
        if (InterpreterHelper.isSTInteger(array)) {
            return false;
        }
        int index = checkSmallInt(streamBody[Squeak.Stream_position]);
        int limit = checkSmallInt(streamBody[Squeak.Stream_limit]);
        int arraySize = indexableSize(array);
        if (index >= limit) {
            return false;
        }
        //  (index < limit and: [(atCache at: atIx+AtCacheOop) = array])
        //      ifFalse: [^ self primitiveFail].
        //
        //  "OK -- its not at end, and the array is in the cache"
        //  index _ index + 1.
        //  result _ self commonVariable: array at: index cacheIndex: atIx.
        //  "Above may cause GC, so can't use stream, array etc. below it"
        //  successFlag ifTrue:
        //      [stream _ self stackTop.
        //      self storeInteger: StreamIndexIndex ofObject: stream withValue: index.
        //      ^ self pop: 1 thenPush: result].
        return false;
    }

    private SqueakObject primitiveBlockCopy() {
        Object rcvr = vm.stackValue(1);
        if (InterpreterHelper.isSTInteger(rcvr)) {
            context.success = false;
        }

        Object sqArgCount = vm.top();
        if (!(InterpreterHelper.isSTInteger(sqArgCount))) {
            context.success = false;
        }

        SqueakObject homeCtxt = (SqueakObject) rcvr;
        if (!vm.isContext(homeCtxt)) {
            context.success = false;
        }
        if (!context.success) {
            return SqueakVM.nilObj;
        }

        if (InterpreterHelper.isSTInteger(homeCtxt.getPointer(Squeak.Context_method))) {
            // ctxt is itself a block; get the context for its enclosing method
            homeCtxt = homeCtxt.getPointerNI(Squeak.BlockContext_home);
        }
        int blockSize = homeCtxt.pointersSize() - homeCtxt.instSize(); //can use a const for instSize
        SqueakObject newBlock = vm.instantiateClass(((SqueakObject) SqueakVM.specialObjects[Squeak.splOb_ClassBlockContext]), blockSize);
        Integer initialPC = vm.encodeSqueakPC(vm.pc + 2, vm.method); //*** check this...
        newBlock.setPointer(Squeak.BlockContext_initialIP, initialPC);
        newBlock.setPointer(Squeak.Context_instructionPointer, initialPC);// claim not needed; value will set it
        newBlock.setPointer(Squeak.Context_stackPointer, InterpreterHelper.smallFromInt(0));
        newBlock.setPointer(Squeak.BlockContext_argumentCount, sqArgCount);
        newBlock.setPointer(Squeak.BlockContext_home, homeCtxt);
        newBlock.setPointer(Squeak.Context_sender, SqueakVM.nilObj);
        return newBlock;
    }

    private boolean primitiveBlockValue(int argCount) {
        Object rcvr = vm.stackValue(argCount);
        if (!isA(rcvr, Squeak.splOb_ClassBlockContext)) {
            return false;
        }
        SqueakObject block = (SqueakObject) rcvr;
        Object blockArgCount = block.getPointer(Squeak.BlockContext_argumentCount);
        if (!InterpreterHelper.isSTInteger(blockArgCount)) {
            return false;
        }
        if (((Integer) blockArgCount != argCount)) {
            return false;
        }
        if (block.getPointer(Squeak.BlockContext_caller) != SqueakVM.nilObj) {
            return false;
        }
        System.arraycopy((Object) vm.activeContext.pointers, vm.sp - argCount + 1, (Object) block.pointers, Squeak.Context_tempFrameStart, argCount);
        Integer initialIP = block.getPointerI(Squeak.BlockContext_initialIP);
        block.setPointer(Squeak.Context_instructionPointer, initialIP);
        block.setPointer(Squeak.Context_stackPointer, argCount);
        block.setPointer(Squeak.BlockContext_caller, vm.activeContext);
        vm.popN(argCount + 1);
        vm.newActiveContext(block);
        return true;
    }

    private Object primitiveHash() {
        Object rcvr = vm.top();
        if (InterpreterHelper.isSTInteger(rcvr)) {
            context.success = false;
            return SqueakVM.nilObj;
        }

        return (int) ((SqueakObject) rcvr).hash;
    }

    private Object setLowSpaceThreshold() {
        int nBytes = stackInteger(0);
        if (context.success) {
            vm.lowSpaceThreshold = nBytes;
        }
        return vm.stackValue(1);
    }

    // Scheduler Primitives
    private SqueakObject getScheduler() {
        SqueakObject assn = (SqueakObject) SqueakVM.specialObjects[Squeak.splOb_SchedulerAssociation];
        return assn.getPointerNI(Squeak.Assn_value);
    }

    private boolean processResume() {
        SqueakObject process = (SqueakObject) vm.top();
        resume(process);
        return true;
    }

    private boolean processSuspend() {
        SqueakObject activeProc = getScheduler().getPointerNI(Squeak.ProcSched_activeProcess);
        if (vm.top() != activeProc) {
            return false;
        }

        vm.popNandPush(1, SqueakVM.nilObj);
        transferTo(pickTopProcess());
        return true;
    }

    private boolean isA(Object obj, int knownClass) {
        Object itsClass = vm.getClass(obj);
        return itsClass == SqueakVM.specialObjects[knownClass];
    }

    private boolean isKindOf(Object obj, int knownClass) {
        Object classOrSuper = vm.getClass(obj);
        Object theClass = SqueakVM.specialObjects[knownClass];
        while (classOrSuper != SqueakVM.nilObj) {
            if (classOrSuper == theClass) {
                return true;
            }
            classOrSuper = ((SqueakObject) classOrSuper).pointers[Squeak.Class_superclass];
        }
        return false;
    }

    private boolean semaphoreWait() {
        SqueakObject sema = (SqueakObject) vm.top();
        if (!isA(sema, Squeak.splOb_ClassSemaphore)) {
            return false;
        }

        int excessSignals = sema.getPointerI(Squeak.Semaphore_excessSignals);
        if (excessSignals > 0) {
            sema.setPointer(Squeak.Semaphore_excessSignals, InterpreterHelper.smallFromInt(excessSignals - 1));
        } else {
            SqueakObject activeProc = getScheduler().getPointerNI(Squeak.ProcSched_activeProcess);
            linkProcessToList(activeProc, sema);
            transferTo(pickTopProcess());
        }
        return true;
    }

    private boolean semaphoreSignal() {
        SqueakObject sema = (SqueakObject) vm.top();
        if (!isA(sema, Squeak.splOb_ClassSemaphore)) {
            return false;
        }

        synchronousSignal(sema);
        return true;
    }

    void synchronousSignal(SqueakObject sema) {
        if (isEmptyList(sema)) {
            //no process is waiting on this semaphore"
            int excessSignals = sema.getPointerI(Squeak.Semaphore_excessSignals);
            sema.setPointer(Squeak.Semaphore_excessSignals, InterpreterHelper.smallFromInt(excessSignals + 1));
        } else {
            resume(removeFirstLinkOfList(sema));
        }
        return;
    }

    private void resume(SqueakObject newProc) {
        SqueakObject activeProc = getScheduler().getPointerNI(Squeak.ProcSched_activeProcess);
        int activePriority = activeProc.getPointerI(Squeak.Proc_priority);
        int newPriority = newProc.getPointerI(Squeak.Proc_priority);
        if (newPriority > activePriority) {
            putToSleep(activeProc);
            transferTo(newProc);
        } else {
            putToSleep(newProc);
        }
    }

    private void putToSleep(SqueakObject aProcess) {
        //Save the given process on the scheduler process list for its priority.
        int priority = aProcess.getPointerI(Squeak.Proc_priority);
        SqueakObject processLists = getScheduler().getPointerNI(Squeak.ProcSched_processLists);
        SqueakObject processList = processLists.getPointerNI(priority - 1);
        linkProcessToList(aProcess, processList);
    }

    private void transferTo(SqueakObject newProc) {
        //Record a process to be awakened on the next interpreter cycle.
        SqueakObject sched = getScheduler();
        SqueakObject oldProc = sched.getPointerNI(Squeak.ProcSched_activeProcess);
        sched.setPointer(Squeak.ProcSched_activeProcess, newProc);
        oldProc.setPointer(Squeak.Proc_suspendedContext, vm.activeContext);
        //int prio= vm.intFromSmall((Integer)newProc.pointers[Squeak.Proc_priority]);
        //System.err.println("Transfer to priority " + prio + " at byteCount " + vm.byteCount);
        //if (prio==8)
        //    vm.dumpStack();
        vm.newActiveContext(newProc.getPointerNI(Squeak.Proc_suspendedContext));
        //System.err.println("new pc is " + vm.pc + "; method offset= " + ((vm.method.pointers.length+1)*4));
        newProc.setPointer(Squeak.Proc_suspendedContext, SqueakVM.nilObj);
        vm.reclaimableContextCount = 0;
    }

    private SqueakObject pickTopProcess() { // aka wakeHighestPriority
        //Return the highest priority process that is ready to run.
        //Note: It is a fatal VM error if there is no runnable process.
        SqueakObject schedLists = getScheduler().getPointerNI(Squeak.ProcSched_processLists);
        int p = schedLists.pointersSize() - 1;  // index of last indexable field"
        p = p - 1;
        SqueakObject processList = schedLists.getPointerNI(p);
        while (isEmptyList(processList)) {
            p = p - 1;
            if (p < 0) {
                return SqueakVM.nilObj; //self error: 'scheduler could not find a runnable process' ].
            }
            processList = schedLists.getPointerNI(p);
        }
        return removeFirstLinkOfList(processList);
    }

    private void linkProcessToList(SqueakObject proc, SqueakObject aList) {
        // Add the given process to the given linked list and set the backpointer
        // of process to its new list."
        if (isEmptyList(aList)) {
            aList.setPointer(Squeak.LinkedList_firstLink, proc);
        } else {
            SqueakObject lastLink = aList.getPointerNI(Squeak.LinkedList_lastLink);
            lastLink.setPointer(Squeak.Link_nextLink, proc);
        }
        aList.setPointer(Squeak.LinkedList_lastLink, proc);
        proc.setPointer(Squeak.Proc_myList, aList);
    }

    private boolean isEmptyList(SqueakObject aLinkedList) {
        return aLinkedList.getPointerNI(Squeak.LinkedList_firstLink) == SqueakVM.nilObj;
    }

    private SqueakObject removeFirstLinkOfList(SqueakObject aList) {
        //Remove the first process from the given linked list."
        SqueakObject first = aList.getPointerNI(Squeak.LinkedList_firstLink);
        SqueakObject last = aList.getPointerNI(Squeak.LinkedList_lastLink);
        if (first == last) {
            aList.setPointer(Squeak.LinkedList_firstLink, SqueakVM.nilObj);
            aList.setPointer(Squeak.LinkedList_lastLink, SqueakVM.nilObj);
        } else {
            SqueakObject next = first.getPointerNI(Squeak.Link_nextLink);
            aList.setPointer(Squeak.LinkedList_firstLink, next);
        }
        first.setPointer(Squeak.Link_nextLink, SqueakVM.nilObj);
        return first;
    }

    private SqueakObject registerSemaphore(int specialObjSpec) {
        SqueakObject sema = (SqueakObject) vm.top();
        if (isA(sema, Squeak.splOb_ClassSemaphore)) {
            SqueakVM.specialObjects[specialObjSpec] = sema;
        } else {
            SqueakVM.specialObjects[specialObjSpec] = SqueakVM.nilObj;
        }
        return (SqueakObject) vm.stackValue(1);
    }

    private Object primitiveSignalAtMilliseconds() { //Delay signal:atMs:
        int msTime = stackInteger(0);
        Object sema = stackNonInteger(1);
        Object rcvr = stackNonInteger(2);
        if (!context.success) {
            return SqueakVM.nilObj;
        }

        //System.err.println("Signal at " + msTime);
        //vm.dumpStack();
        if (isA(sema, Squeak.splOb_ClassSemaphore)) {
            SqueakVM.specialObjects[Squeak.splOb_TheTimerSemaphore] = sema;
            vm.nextWakeupTick = msTime;
        } else {
            SqueakVM.specialObjects[Squeak.splOb_TheTimerSemaphore] = SqueakVM.nilObj;
            vm.nextWakeupTick = 0;
        }
        return rcvr;
    }

    //Other Primitives

    private Integer millisecondClockValue() {
        //Return the value of the millisecond clock as an integer.
        //Note that the millisecond clock wraps around periodically.
        //The range is limited to SmallInteger maxVal / 2 to allow
        //delays of up to that length without overflowing a SmallInteger."
        return InterpreterHelper.smallFromInt(((int) (System.currentTimeMillis() & (long) (SqueakVM.maxSmallInt >> 1))));
    }

    private boolean beDisplay(int argCount) {
        SqueakObject displayObj = (SqueakObject) vm.top();
        SqueakVM.FormCache disp = vm.newFormCache(displayObj);
        if (disp.squeakForm == null) {
            return false;
        }
        if (!Screen.isSupportedDepth(disp.depth)) {
            SqueakLogger.log_W("Unsupported display depth: " + disp.depth);
            return false;
        }
        SqueakVM.specialObjects[Squeak.splOb_TheDisplay] = displayObj;
        displayBitmap = disp.bits;
        displayWidth = disp.width;
        displayHeight = disp.height;
        displayPitch = disp.pitch;
        displayDepth = disp.depth;
        vm.popN(argCount);

        SqueakLogger.log_D("beDisplay: " + displayObj.toString());
        SqueakLogger.log_D(SqueakLogger.LOG_BLOCK_HEADER);
        SqueakLogger.logBlockContent(SqueakLogger.D, "Display size: " + disp.width + "@" + disp.height);
        SqueakLogger.logBlockContent(SqueakLogger.D, "Display depth: " + disp.depth);
        SqueakLogger.logBlockContent(SqueakLogger.D, "Display pixPerWord: " + disp.pixPerWord);
        SqueakLogger.logBlockContent(SqueakLogger.D, "Display pitch: " + disp.pitch);
        SqueakLogger.logBlockContent(SqueakLogger.D, "Display bits length: " + disp.bits.length);
        SqueakLogger.log_D(SqueakLogger.LOG_BLOCK_FOOTER);

        boolean remap = false;
        if (theDisplay != null) {
            remap = true;
        }

        if (remap) {
            Dimension requestedExtent = new Dimension(disp.width, disp.height);
            SqueakLogger.log_D("Squeak: changing screen size to " + disp.width + "@" + disp.height);
            Dimension currentExtent = theDisplay.getExtent();
            if (currentExtent.width != requestedExtent.width
                    || currentExtent.height != requestedExtent.height) {
                SqueakLogger.log_E("Screen size mismatch! Rechanging screen size to " + disp.width + "@" + disp.height);
                theDisplay.setExtent(requestedExtent);
            }
        } else {
            // bind Screen
            theDisplay = new Screen("DodoTalk", disp.width, disp.height, disp.depth, vm);
        }


        displayBitmapFromOrg = new int[displayBitmap.length];
        copyBitmapIntToInt(displayBitmap, displayBitmapFromOrg,
                new Rectangle(0, 0, disp.width, disp.height), disp.pitch, disp.depth);


        theDisplay.setBitsV2(displayBitmapFromOrg, disp.depth);
        theDisplay.setUpdatesDeferred(vm.isDeferDisplayUpdates());
        if (!remap) {
            theDisplay.open();
        }
        return true;
    }

    private void copyImageFromOld(int[] old, int[] after) {
        if (old.length <= after.length) {
            for (int i = 0; i < old.length; i++) {
                after[i] = old[i];
            }
        } else {
            for (int i = 0; i < after.length; i++) {
                after[i] = old[i];
            }
        }
    }

    private boolean beCursor(int argCount) {
        // For now we ignore the white outline form (maskObj)
        if (theDisplay == null) {
            return true;
        }
        SqueakObject cursorObj, maskObj;
        if (argCount == 0) {
            cursorObj = stackNonInteger(0);
            maskObj = SqueakVM.nilObj;
        } else {
            cursorObj = stackNonInteger(1);
            maskObj = stackNonInteger(0);
        }
        SqueakVM.FormCache cursorForm = vm.newFormCache(cursorObj);
        if (!context.success || cursorForm.squeakForm == null) {
            return false;
        }
        //Following code for offset is not yet used...
        SqueakObject offsetObj = checkNonSmallInt(cursorObj.getPointer(4));
        if (!isA(offsetObj, Squeak.splOb_ClassPoint)) {
            return false;
        }
        int offsetX = checkSmallInt(offsetObj.pointers[0]);
        int offsetY = checkSmallInt(offsetObj.pointers[1]);
        if (!context.success) {
            return false;
        }
        //Current cursor code in Screen expects cursor and mask to be packed in cursorBytes
        //For now we make them be equal copies of incoming 16x16 cursor
        int cursorBitsSize = cursorForm.bits.length;
        byte[] cursorBytes = new byte[8 * cursorBitsSize];
        copyBitmapToByteArray(cursorForm.bits, cursorBytes,
                new Rectangle(0, 0, cursorForm.width, cursorForm.height),
                cursorForm.pitch, cursorForm.depth);
        for (int i = 0; i < (cursorBitsSize * 4); i++) {
            cursorBytes[i + (cursorBitsSize * 4)] = cursorBytes[i];
        }
        theDisplay.setCursor(cursorBytes, BWMask);
        return true;
    }

    private boolean primitiveYield(int numArgs) {
        // Halts execution until an input/timer notification arrives. The wait
        // is bounded and the latch consumes the notification, so a signal that
        // arrives just before the wait is not lost.
        long millis = 100;
        if (numArgs > 1) {
            return false;
        }
        if (numArgs > 0) {
            int micros = stackInteger(0);
            if (!context.success) {
                return false;
            }
            vm.pop();
            if (micros > 0) {
                millis = Math.max(1, micros / 1000);
            }
        }
        vm.awaitInputEvent(millis);
        // Ask for a check at the next send/backward jump. Doing the full
        // interrupt handling here could switch the active context in the
        // middle of a primitive.
        vm.requestInterruptCheck();
        return true;
    }

    /**
     * Primitive 234: Bitmap decompress: bm fromByteArray: ba at: index.
     * <p>
     * The Smalltalk fallback is a pure-Smalltalk loop over every run code. It
     * is very slow for large compressed forms and can stall the UI for many
     * seconds (during which no input is polled). Decode the same run-coded
     * format in Java instead.
     */
    private boolean primitiveBitmapDecompress() {
        SqueakObject rcvr = stackNonInteger(3);
        SqueakObject bitmap = stackNonInteger(2);
        SqueakObject byteArray = stackNonInteger(1);
        int index = stackInteger(0);
        if (!context.success
                || bitmap.bits == null || !(bitmap.bits instanceof int[])
                || byteArray.bits == null || !(byteArray.bits instanceof byte[])) {
            context.success = false;
            return false;
        }

        int[] words = (int[]) bitmap.bits;
        byte[] bytes = (byte[]) byteArray.bits;
        if (!decompressBitmap(bytes, index, words)) {
            context.success = false;
            return false;
        }

        vm.popNandPush(4, rcvr);
        return true;
    }

    /**
     * Decodes the run-coded bitmap format used by Bitmap class>>compressToByteArray.
     * Exposed for direct regression tests; {@code index} is one-based.
     */
    static boolean decompressBitmap(byte[] bytes, int index, int[] words) {
        if (bytes == null || words == null || index < 1) {
            return false;
        }
        int i = index - 1;
        int k = 0;
        try {
            while (i < bytes.length) {
                int anInt = bytes[i++] & 0xFF;
                if (anInt > 223) {
                    if (anInt <= 254) {
                        anInt = (anInt - 224) * 256 + (bytes[i++] & 0xFF);
                    } else {
                        anInt = 0;
                        for (int j = 0; j < 4; j++) {
                            anInt = (anInt << 8) | (bytes[i++] & 0xFF);
                        }
                    }
                }

                // The 2.2 C VM treats the run length as unsigned.
                long run = (anInt & 0xFFFFFFFFL) >>> 2;
                int code = anInt & 3;
                if (run > words.length - k) {
                    return false;
                }
                int n = (int) run;

                switch (code) {
                    case 0:
                        // Matches the 2.2 C VM: the "skip" code is a no-op.
                        break;
                    case 1: {
                        int b = bytes[i++] & 0xFF;
                        int data = b | (b << 8) | (b << 16) | (b << 24);
                        for (int j = 0; j < n; j++) {
                            words[k++] = data;
                        }
                        break;
                    }
                    case 2: {
                        int data = ((bytes[i] & 0xFF) << 24)
                                | ((bytes[i + 1] & 0xFF) << 16)
                                | ((bytes[i + 2] & 0xFF) << 8)
                                | (bytes[i + 3] & 0xFF);
                        i += 4;
                        for (int j = 0; j < n; j++) {
                            words[k++] = data;
                        }
                        break;
                    }
                    case 3:
                        for (int j = 0; j < n; j++) {
                            int data = ((bytes[i] & 0xFF) << 24)
                                    | ((bytes[i + 1] & 0xFF) << 16)
                                    | ((bytes[i + 2] & 0xFF) << 8)
                                    | (bytes[i + 3] & 0xFF);
                            i += 4;
                            words[k++] = data;
                        }
                        break;
                    default:
                        return false;
                }
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            return false;
        }
        return true;
    }

    private boolean primitiveCopyBits(SqueakObject rcvr, int argCount) {
        // no rcvr class check, to allow unknown subclasses (e.g. under Turtle)
        if (!bitbltTable.loadBitBlt(rcvr, argCount, false, (SqueakObject) SqueakVM.specialObjects[Squeak.splOb_TheDisplay])) {
            SqueakLogger.log_D("primitive 96: " + bitbltTable.lastFailure());
            return false;
        }

        Rectangle affectedArea = bitbltTable.copyBits();
        if (affectedArea != null && theDisplay != null) {
            copyBitmapIntToInt(displayBitmap, displayBitmapFromOrg, affectedArea,
                    bitbltTable.dest.pitch, bitbltTable.dest.depth);
            theDisplay.redisplay(false, affectedArea);
        }
        if (bitbltTable.combinationRule == 22 || bitbltTable.combinationRule == 32) {
            vm.popNandPush(2, InterpreterHelper.smallFromInt(bitbltTable.bitCount));
        }
        return true;
    }

    /**
     * Primitive 103: CharacterScanner
     * scanCharactersFrom:to:in:rightX:stopAtX:.
     * <p>
     * The receiver is a CharacterScanner (a BitBlt subclass). The scanner state
     * is loaded and driven in {@link BitBlt}; this method only validates the
     * Smalltalk stack, updates the display buffer when requested, and replaces
     * the receiver plus six arguments with the returned stop code.
     */
    private boolean primitiveScanCharacters(int argCount) {
        if (argCount != 6) {
            context.success = false;
            return false;
        }

        SqueakObject scanner = stackNonInteger(6);
        int start = stackInteger(5);
        int stop = stackInteger(4);
        SqueakObject string = stackNonInteger(3);
        int rightX = stackInteger(2);
        SqueakObject stopArray = stackNonInteger(1);
        Object displayFlagObject = vm.stackValue(0);

        boolean displayFlag;
        if (displayFlagObject == SqueakVM.trueObj) {
            displayFlag = true;
        } else if (displayFlagObject == SqueakVM.falseObj) {
            displayFlag = false;
        } else {
            context.success = false;
            return false;
        }
        if (!context.success) {
            return false;
        }

        if (!bitbltTable.loadScanner(scanner, start, stop, string, rightX,
                stopArray, displayFlag)) {
            SqueakLogger.log_D("primitive 103: " + bitbltTable.lastFailure());
            context.success = false;
            return false;
        }

        Object stopCode = bitbltTable.scanCharacters();
        if (!vm.isSuccess() || !context.success) {
            context.success = false;
            SqueakLogger.log_D("primitive 103: " + bitbltTable.lastFailure());
            return false;
        }

        if (displayFlag && theDisplay != null
                && displayBitmap != null && displayBitmapFromOrg != null) {
            Rectangle affected = bitbltTable.getScanAffectedArea();
            if (affected != null) {
                copyBitmapIntToInt(displayBitmap, displayBitmapFromOrg, affected,
                        bitbltTable.dest.pitch, bitbltTable.dest.depth);
                theDisplay.redisplay(false, affected);
            }
        }

        vm.popNandPush(7, stopCode);
        return true;
    }

    // FIXME (copyBitmapToByteArray)
    private void copyBitmapToByteArray(int[] words, byte[] bytes, Rectangle rect, int raster, int depth) {
        //Copy our 32-bit words into a byte array  until we find out
        // how to make AWT happy with int buffers
        if (depth == 1) {
            //System.out.println("copyBitmapToByteArray 1bit mode");
            copyBitmapMode1BitToByte(words, bytes, rect, raster, depth);
        } else if (depth == 8) {
            //System.out.println("copyBitmapToByteArray 8bit mode");
            copyBitmapMode8BitToByte(words, bytes, rect, raster, depth);
        }
    }

    /**
     * 32 pixel/integer => 8 pixel/byte
     */
    private void copyBitmapMode1BitToByte(int[] words, byte[] bitmapData, Rectangle rect, int raster, int depth) {
        int word;
        for (int i = 0; i < words.length; i++) {
            word = (words[i]); // actual 32 pixel
            for (int j = 0; j < 4; j++) {
                int pixelIndex = i * 4 + j;
                // 8 pixel per byte
                bitmapData[pixelIndex] = (byte) ((word >>> ((3 - j) * 8)) & 255);
            }
        }
    }

    /**
     * 4 pixel/integer => 1 pixel/byte
     */
    private void copyBitmapMode8BitToByte(int[] words, byte[] bitmapData, Rectangle rect, int raster, int depth) {
        int word;
        for (int i = 0; i < words.length; i++) {
            word = (words[i]); // actual 4 pixel
            for (int j = 0; j < 4; j++) {
                int pixelIndex = i * 4 + j;
                // 1 pixel per byte
                bitmapData[pixelIndex] = (byte) ((word >>> ((3 - j) * 8)) & 255);
            }
        }
    }


    /**
     * Copy the affected region of a Squeak display form into the AWT display
     * buffer. Both arrays have the same word layout (pitch words per row), so
     * only the words touched by {@code rect} are copied.
     */
    static void copyBitmapIntToInt(int[] words, int[] bitmapData, Rectangle rect,
                                   int raster, int depth) {
        if (words == null || bitmapData == null) {
            throw new IllegalArgumentException("display buffers must not be null");
        }
        if (words.length != bitmapData.length) {
            throw new IllegalArgumentException("display buffers differ in size: "
                    + words.length + " vs " + bitmapData.length);
        }
        if (raster <= 0) {
            throw new IllegalArgumentException("invalid display pitch: " + raster);
        }
        if (depth <= 0 || depth > 32 || (32 % depth) != 0) {
            throw new IllegalArgumentException("invalid display depth: " + depth);
        }
        if (words.length % raster != 0) {
            throw new IllegalArgumentException("display buffer length " + words.length
                    + " is not a multiple of pitch " + raster);
        }

        int height = words.length / raster;
        int pixelsPerWord = 32 / depth;
        int pixelWidth = raster * pixelsPerWord;
        Rectangle area = rect == null ? new Rectangle(0, 0, pixelWidth, height) : rect;

        int x0 = Math.max(0, area.x);
        int y0 = Math.max(0, area.y);
        int x1 = Math.min(pixelWidth, area.x + area.width);
        int y1 = Math.min(height, area.y + area.height);
        if (x1 <= x0 || y1 <= y0) {
            return;
        }

        int firstWord = x0 / pixelsPerWord;
        int lastWord = (x1 - 1) / pixelsPerWord;
        int wordsPerRow = lastWord - firstWord + 1;
        for (int y = y0; y < y1; y++) {
            int offset = y * raster + firstWord;
            System.arraycopy(words, offset, bitmapData, offset, wordsPerRow);
        }
    }

    private SqueakObject primitiveMousePoint() {
        Point lastMouse = theDisplay == null ? new Point(0, 0) : theDisplay.peekMousePoint();
        SqueakObject pointClass = (SqueakObject) SqueakVM.specialObjects[Squeak.splOb_ClassPoint];
        SqueakObject newPoint = vm.instantiateClass(pointClass, 0);
        newPoint.setPointer(Squeak.Point_x, InterpreterHelper.smallFromInt(lastMouse.x));
        newPoint.setPointer(Squeak.Point_y, InterpreterHelper.smallFromInt(lastMouse.y));
        return newPoint;
    }

    private Integer primitiveMouseButtons() {
        int buttons = theDisplay == null ? 0 : theDisplay.consumeMouseButtons();
        return InterpreterHelper.smallFromInt(buttons);
    }

    private Object primitiveKbdNext() {
        if (theDisplay == null || !theDisplay.keyboardHasNext()) {
            return SqueakVM.nilObj;
        }
        return InterpreterHelper.smallFromInt(theDisplay.keyboardNext());
    }

    private Object primitiveKbdPeek() {
        if (theDisplay == null) {
            return SqueakVM.nilObj;
        }
        int peeked = theDisplay.keyboardPeek();
        return peeked == 0 ? (Object) SqueakVM.nilObj : InterpreterHelper.smallFromInt(peeked);
    }

    private SqueakObject primitiveArrayBecome(boolean doBothWays) {
        // Should flush method cache
        SqueakObject rcvr = stackNonInteger(1);
        SqueakObject arg = stackNonInteger(0);
        if (!context.success) {
            return rcvr;
        }
        context.success = SqueakVM.objectMemory.bulkBecome(rcvr.pointers, arg.pointers, doBothWays);
        return rcvr;
    }

    private SqueakObject primitiveSomeObject() {
        return SqueakVM.objectMemory.nextInstance(0, null);
    }

    private SqueakObject primitiveSomeInstance(SqueakObject sqClass) {
        return SqueakVM.objectMemory.nextInstance(0, sqClass);
    }

    private Object primitiveNextObject(SqueakObject priorObject) {
        SqueakObject nextObject = SqueakVM.objectMemory.nextInstance(SqueakVM.objectMemory.otIndexOfObject(priorObject) + 1, null);
        if (nextObject == SqueakVM.nilObj) {
            return InterpreterHelper.smallFromInt(0);
        }
        return nextObject;
    }

    private SqueakObject primitiveNextInstance(SqueakObject priorInstance) {
        SqueakObject sqClass = (SqueakObject) priorInstance.sqClass;
        return SqueakVM.objectMemory.nextInstance(SqueakVM.objectMemory.otIndexOfObject(priorInstance) + 1, sqClass);
    }

    //  region more-primitive-for-squeak

    /**
     * Primitive 233. DodoTalk does not remap the display buffer when the
     * window manager switches to full screen, so claiming success would leave
     * the image and the Java window in different states. Explicitly fail and
     * let the image use its non-full-screen path instead.
     */
    private boolean primitiveSetFullScreen() {
        Object argOop = vm.top();
        if (argOop != trueObj && argOop != falseObj) {
            return false;
        }
        if (!fullScreenWarningLogged) {
            fullScreenWarningLogged = true;
            SqueakLogger.log_W("primitiveSetFullScreen is not supported; returning failure");
        }
        lastFailureKind = RuntimeStats.PrimitiveFailure.UNIMPLEMENTED;
        return false;
    }

    private boolean primitiveShowDisplayRect(int argCount) {
        if (argCount != 4) {
            return false;
        }
        int bottom = stackInteger(0);
        int top = stackInteger(1);
        int right = stackInteger(2);
        int left = stackInteger(3);
        if (!context.success) {
            return false;
        }
        if (displayBitmap == null || displayBitmapFromOrg == null || theDisplay == null) {
            return false;
        }

        left = Math.max(0, left);
        top = Math.max(0, top);
        right = Math.min(displayWidth, right);
        bottom = Math.min(displayHeight, bottom);
        if (left > right || top > bottom) {
            return false;
        }
        Rectangle area = new Rectangle(left, top, right - left, bottom - top);
        copyBitmapIntToInt(displayBitmap, displayBitmapFromOrg, area, displayPitch, displayDepth);
        // This primitive explicitly asks for the screen update, not a
        // deferrable BitBlt update.
        theDisplay.redisplay(true, area);
        vm.popN(4);
        return true;
    }

    private boolean primitiveForceDisplayUpdate(int argCount) {
        if (argCount != 0) {
            return false;
        }
        if (theDisplay != null) {
            theDisplay.forceDisplayUpdate();
        }
        return true;
    }

    private boolean primitiveBeep() {
        try {
            Toolkit.getDefaultToolkit().beep();
        } catch (RuntimeException e) {
            // A missing audio device must not turn into a primitive failure.
            SqueakLogger.log_D("primitiveBeep could not play a system beep: " + e.getMessage());
        }
        return true;
    }

    private boolean primitiveStringCompareWithCollated(int argCount) {
        if (argCount != 3) {
            return false;
        }
        SqueakObject order = stackNonInteger(0);
        SqueakObject string2 = stackNonInteger(1);
        SqueakObject string1 = stackNonInteger(2);
        if (!context.success
                || !(string1.bits instanceof byte[])
                || !(string2.bits instanceof byte[])
                || !(order.bits instanceof byte[])) {
            context.success = false;
            return false;
        }
        byte[] first = (byte[]) string1.bits;
        byte[] second = (byte[]) string2.bits;
        byte[] collation = (byte[]) order.bits;
        if (collation.length < 256) {
            context.success = false;
            return false;
        }

        int length = Math.min(first.length, second.length);
        for (int i = 0; i < length; i++) {
            int c1 = collation[first[i] & 0xFF] & 0xFF;
            int c2 = collation[second[i] & 0xFF] & 0xFF;
            if (c1 != c2) {
                vm.popNandPush(4, InterpreterHelper.smallFromInt(c1 < c2 ? 1 : 3));
                return true;
            }
        }
        int result = first.length == second.length ? 2 : (first.length < second.length ? 1 : 3);
        vm.popNandPush(4, InterpreterHelper.smallFromInt(result));
        return true;
    }

    private boolean primitiveScreenSize() {
        int width = 640;
        int height = 480;
        if (theDisplay != null) {
            Dimension extent = theDisplay.getExtent();
            width = extent.width;
            height = extent.height;
        }
        SqueakLogger.log_D("primitiveScreenSize width: " + width + " height: " + height);
        return popNandPushIfOK(1, makePointWithXandY(InterpreterHelper.smallFromInt(width), InterpreterHelper.smallFromInt(height))); // actualScreenSize
    }

    private void primitiveDebug() {
        SqueakLogger.log_D("primitiveDebug");
    }

    // endregion more-primitive-for-squeak

    private boolean relinquishProcessor() {
        int periodInMicroseconds = stackInteger(0); //NOTE: argument is *ignored*
        vm.pop();
        //        Thread.currentThread().yield();
        //        try {vm.wait(50L); } // 50 millisecond pause
        //            catch (InterruptedException e) {}
        return true;
    }

    Object primSeconds() {
        long currentTimeMillis = System.currentTimeMillis();
        return squeakSeconds(currentTimeMillis);
    }

    // -- Some support methods -----------------------------------------------------------

    PrimitiveFailedException primitiveFailed() {
        return PrimitiveFailed;
    }

    /**
     * A documented Squeak 2.2 {@code primitiveFail} that is not a missing
     * implementation (for example primitive 19 or 120).
     */
    private boolean primitiveIntentionalFail(int index) {
        lastFailureKind = RuntimeStats.PrimitiveFailure.INTENTIONAL;
        return false;
    }

    /**
     * Just return false and classify the primitive as unimplemented.
     * skip IDE warning in switch case
     */
    private boolean primitiveFailNoLog(int index) {
        lastFailureKind = RuntimeStats.PrimitiveFailure.UNIMPLEMENTED;
        return false;
    }

    /**
     * Marks a primitive as a deliberate successful no-op. These stubs are
     * tracked in {@link #NO_OP_STUB_PRIMITIVES} so they can be replaced when
     * the real subsystem is implemented.
     */
    private boolean noOpPrimitiveStub(int index, String name) {
        if (!NO_OP_STUB_PRIMITIVES.contains(index)) {
            SqueakLogger.log_W("Unregistered no-op primitive stub: " + index + " (" + name + ")");
        }
        SqueakLogger.log_D("[primitive-stub] " + index + " " + name + " is a no-op");
        return true;
    }

    /**
     * Test/audit helper for the deliberately stubbed primitives.
     */
    static boolean isNoOpStub(int index) {
        return NO_OP_STUB_PRIMITIVES.contains(index);
    }

    /**
     * FIXME: surely something better can be devised?
     * Idea: make argCount a field, then this method
     * needs no argument
     */
    Object stackReceiver(int argCount) {
        return vm.stackValue(argCount);
    }

    private boolean popNandPushIfOK(int nToPop, Object returnValue) {
        if (!context.success || returnValue == null) {
            return false;
        }
        vm.popNandPush(nToPop, returnValue);
        return true;
    }

    private boolean pop2andDoBoolIfOK(boolean bool) {
        vm.success = context.success;
        return vm.pushBoolAndPeek(bool);
    }


    private boolean popNandPushIntIfOK(int nToPop, int returnValue) {
        return popNandPushIfOK(nToPop, InterpreterHelper.smallFromInt(returnValue));
    }

    boolean popNandPushFloatIfOK(int nToPop, double returnValue) {
        if (!context.success) {
            return false;
        }
        return popNandPushIfOK(nToPop, makeFloat(returnValue));
    }

    private Object primitiveTruncate(double floatVal) {
        // NaN and infinities fail both comparisons. Values outside the
        // SmallInt range must fail instead of being saturated by (int).
        if (!(SqueakVM.minSmallInt <= floatVal && floatVal <= SqueakVM.maxSmallInt)) {
            context.success = false;
            return null;
        }
        return InterpreterHelper.smallFromInt((int) floatVal);
    }

    private double primitiveFractionalPart(double floatVal) {
        if (-9007199254740991.0 /* -((1 << 53) - 1) */ <= floatVal && floatVal <= 9007199254740991.0 /* (1 << 53) - 1 */) {
            return floatVal - Math.floor(floatVal);
        } else {
            context.success = false;
            return 0d;
        }
    }

    private int primitiveExponent(double floatVal) {
        if (!context.success) {
            return 0;
        }
        return Math.getExponent(floatVal);
    }

    private double primitiveTimesTwoPower(double mantissa, int exponent) {
        if (!context.success) {
            return 0d;
        }
        return Math.scalb(mantissa, exponent);
    }

    private boolean primitiveSetInterruptKey(int argCount) {
        if (argCount != 1) {
            return false;
        }
        int keycode = stackInteger(0);
        if (!context.success) {
            return false;
        }
        vm.setInterruptKeycode(keycode);
        // Leave the receiver on the stack, matching the reference VM's
        // "pop the argument, return the receiver" primitive convention.
        vm.popN(1);
        return true;
    }

    private boolean primitiveDeferDisplayUpdates(int argCount) {
        if (argCount != 1) {
            return false;
        }
        boolean result = stackBool(0);
        if (!context.success) {
            return false;
        }
        vm.setDeferDisplayUpdates(result);
        if (theDisplay != null) {
            theDisplay.setUpdatesDeferred(result);
        }
        // Pop the flag, leave the receiver on the stack.
        vm.popN(1);
        return true;
    }

    /*
    !Interpreter methodsFor: 'object access primitives'!
    primitiveObjectPointsTo
    | rcvr thang lastField |

    thang _ self popStack.
    rcvr _ self popStack.
    (self isIntegerObject: rcvr)
        ifTrue: [^ self pushBool: false].
    lastField _ self lastPointerOf: rcvr.
    BaseHeaderSize to: lastField by: 4 do:
        [:i | (self longAt: rcvr + i) = thang
            ifTrue: [^ self pushBool: true]].
    self pushBool: false.! !
     */
    private boolean primitiveObjectPointsTo() {
        Object thang = vm.pop();
        Object rcvr = vm.pop();
        if (InterpreterHelper.isSTInteger(rcvr)) {
            vm.push(SqueakVM.falseObj);
            return false;
        }

        if (rcvr instanceof SqueakObject) {
            Object[] objarray = ((SqueakObject) rcvr).pointers;
            if (objarray != null) {
                for (Object obj : objarray) {
                    if (obj == thang) {
                        vm.push(trueObj);
                        return true;
                    }
                }
            }
        }

        vm.push(SqueakVM.falseObj);
        return false;
    }

    /*
    !Interpreter methodsFor: 'other primitives'!
    primitiveClipboardText
    "When called with a single string argument, post the string to the clipboard. When called with zero arguments,
    return a string containing the current clipboard contents."

    | s sz |
    argumentCount = 1 ifTrue: [
        s _ self stackTop.
        self assertClassOf: s is: (self splObj: ClassString).
        successFlag ifTrue: [
            sz _ self stSizeOf: s.
            self clipboardWrite: sz From: (s + BaseHeaderSize) At: 0.
            self pop: 1.  "pop s, leave rcvr on stack"
        ].
    ] ifFalse: [
        sz _ self clipboardSize.
        s _ self instantiateClass: (self splObj: ClassString)
        indexableSize: sz.
        self clipboardRead: sz Into: (s + BaseHeaderSize) At: 0.
        self pop: 1.  "rcvr"
        self push: s.
    ].
    ! !
     */
    private boolean primitiveClipboardText(int argCount) {
        if (argCount == 1) {  // write to clipboard
            Object s = vm.stackValue(0);
            boolean isString = InterpreterHelper.assertClassOfIs(s, SqueakVM.specialObjects[Squeak.splOb_ClassString]);
            if (isString) {
                vm.clipboardManager.clipboardWrite( (SqueakObject) s);
                vm.pop();
                context.success = true;
                return true;
            } else {
                context.success = false;
                return false;
            }
        } else if (argCount == 0) {  // read from clipboard
            int size = vm.clipboardManager.clipboardSize();
            SqueakObject s = vm.clipboardManager.clipboardRead();
            vm.pop();
            vm.push(s);
            return true;
        }
        return false;
    }

    /**
     * Primitive执行上下文，方便维护
     * 现阶段只包装一下success执行标志位
     */
    private static class PrimitiveExecuteContext {
        /**
         * 表示是否执行成功，方便逻辑链路上进行异常判断处理
         */
        boolean success = true;

        public void reset() {
            success = true;
        }
    }
}
