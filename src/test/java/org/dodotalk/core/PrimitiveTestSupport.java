package org.dodotalk.core;

import org.dodotalk.uilts.RuntimeStats;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

final class PrimitiveTestSupport {

    private static final File IMAGE_NAME =
            new File(System.getProperty("java.io.tmpdir"), "dodotalk-synthetic-primitive.image");

    private PrimitiveTestSupport() {
    }

    static Fixture newFixture() {
        return new Fixture(SyntheticImageBuilder.build(SyntheticImageBuilder.Variant.ARITH));
    }

    static CallResult callViaPrimitiveHeader(int primitive, int argCount,
                                             Object receiver, Object... args) {
        if (argCount != args.length) {
            throw new IllegalArgumentException("argCount does not match arguments");
        }
        byte[] fallback = {118, 124};
        SqueakVM vm = newVM(SyntheticImageBuilder.buildPrimitiveFixture(
                primitive, argCount, fallback));
        SqueakPrimitiveHandler handler = SqueakVM.primHandler;
        RuntimeStats stats = vm.getRuntimeStats();
        SqueakObject method = (SqueakObject) vm.method.methodGetLiteral(0);
        SqueakObject initialContext = vm.activeContext;

        vm.push(receiver);
        for (Object arg : args) {
            vm.push(arg);
        }
        int spBefore = vm.sp;
        long callsBefore = stats.getPrimitiveCallsTotal();
        long failuresBefore = stats.getPrimitiveFailuresTotal();

        vm.executeNewMethod(receiver, method, argCount, primitive);
        boolean success = vm.activeContext == initialContext;
        if (!success) {
            try {
                vm.run();
            } catch (Exception expected) {
            }
        }

        return new CallResult(success, vm.top(), spBefore, vm.sp,
                handler.getLastFailureKind(),
                callsBefore, stats.getPrimitiveCallsTotal(),
                failuresBefore, stats.getPrimitiveFailuresTotal());
    }

    static CallResult callSpecialBytecodeFallback() {
        SqueakVM vm = newVM(SyntheticImageBuilder.build(SyntheticImageBuilder.Variant.FALLBACK));
        RuntimeStats stats = vm.getRuntimeStats();
        int spBefore = vm.sp;
        long callsBefore = stats.getPrimitiveCallsTotal();
        long failuresBefore = stats.getPrimitiveFailuresTotal();
        try {
            vm.run();
        } catch (Exception expected) {
        }
        return new CallResult(true, vm.top(), spBefore, vm.sp, null,
                callsBefore, stats.getPrimitiveCallsTotal(),
                failuresBefore, stats.getPrimitiveFailuresTotal());
    }

    private static SqueakVM newVM(byte[] bytes) {
        try {
            SqueakImage image = new SqueakImage(new ByteArrayInputStream(bytes), IMAGE_NAME);
            return new SqueakVM(image, new RuntimeStats(true));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    static final class Fixture {
        final SqueakVM vm;
        final SqueakPrimitiveHandler handler;
        final RuntimeStats stats;
        final int initialSp;

        private Fixture(byte[] bytes) {
            this.vm = newVM(bytes);
            this.handler = SqueakVM.primHandler;
            this.stats = vm.getRuntimeStats();
            this.initialSp = vm.sp;
        }

        CallResult call(int primitive, int argCount, Object receiver, Object... args) {
            if (argCount != args.length) {
                throw new IllegalArgumentException("argCount does not match arguments");
            }
            vm.push(receiver);
            for (Object arg : args) {
                vm.push(arg);
            }
            int spBefore = vm.sp;
            long callsBefore = stats.getPrimitiveCallsTotal();
            long failuresBefore = stats.getPrimitiveFailuresTotal();
            boolean success = handler.doPrimitive(primitive, argCount);
            Object result = success ? vm.top() : null;
            return new CallResult(success, result, spBefore, vm.sp,
                    handler.getLastFailureKind(),
                    callsBefore, stats.getPrimitiveCallsTotal(),
                    failuresBefore, stats.getPrimitiveFailuresTotal());
        }

        void resetStack() {
            vm.sp = initialSp;
        }

        Object newSmallInt(int value) {
            Integer small = InterpreterHelper.smallFromInt(value);
            if (small == null) {
                throw new IllegalArgumentException("not a SmallInt: " + value);
            }
            return small;
        }

        SqueakObject newArray(int size) {
            return vm.instantiateClass(Squeak.splOb_ClassArray, size);
        }

        SqueakObject newByteArray(int size) {
            return vm.instantiateClass(Squeak.splOb_ClassByteArray, size);
        }

        SqueakObject newString(String value) {
            byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
            SqueakObject string = vm.instantiateClass(Squeak.splOb_ClassString, bytes.length);
            string.bits = bytes;
            return string;
        }

        SqueakObject newCharacter(char value) {
            SqueakObject character = vm.instantiateClass(Squeak.splOb_ClassCharacter, 0);
            character.setPointer(0, newSmallInt(value));
            return character;
        }

        SqueakObject newFloat(double value) {
            SqueakObject floatObject = vm.instantiateClass(Squeak.splOb_ClassFloat, -1);
            floatObject.setFloatBits(value);
            return floatObject;
        }

        SqueakObject newStream(SqueakObject backing, int position, int limit) {
            SqueakObject stream = vm.instantiateClass(Squeak.splOb_ClassArray, 3);
            stream.setPointer(Squeak.Stream_array, backing);
            stream.setPointer(Squeak.Stream_position, newSmallInt(position));
            stream.setPointer(Squeak.Stream_limit, newSmallInt(limit));
            return stream;
        }
    }

    static final class CallResult {
        final boolean success;
        final Object result;
        final int spBefore;
        final int spAfter;
        final RuntimeStats.PrimitiveFailure failureKind;
        final long callsBefore;
        final long callsAfter;
        final long failuresBefore;
        final long failuresAfter;

        private CallResult(boolean success, Object result, int spBefore, int spAfter,
                           RuntimeStats.PrimitiveFailure failureKind,
                           long callsBefore, long callsAfter,
                           long failuresBefore, long failuresAfter) {
            this.success = success;
            this.result = result;
            this.spBefore = spBefore;
            this.spAfter = spAfter;
            this.failureKind = failureKind;
            this.callsBefore = callsBefore;
            this.callsAfter = callsAfter;
            this.failuresBefore = failuresBefore;
            this.failuresAfter = failuresAfter;
        }

        long callDelta() {
            return callsAfter - callsBefore;
        }

        long failureDelta() {
            return failuresAfter - failuresBefore;
        }
    }
}
