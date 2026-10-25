package org.dodotalk.uilts;

import org.dodotalk.SqueakConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Opt-in runtime diagnostics for the VM.
 * <p>
 * The interpreter used to expose failed primitives only as debug log lines.
 * That is not enough to decide whether a primitive is missing entirely or
 * whether an implemented primitive rejected its arguments. This collector
 * keeps lightweight counters for the interesting runtime paths and prints a
 * summary when the JVM exits (or on demand from a test/diagnostic tool).
 * <p>
 * Disabled by default; enable with {@code -Dsqueak.stats=true} (Gradle:
 * {@code -PsqueakStats=true}). All recording methods are no-ops when disabled,
 * so callers can leave the instrumentation in hot paths.
 */
public final class RuntimeStats {

    public static final int MAX_PRIMITIVE_INDEX = 256;
    public static final String DEFAULT_LOG_DIRECTORY = "logs";
    public static final String DEFAULT_REPORT_FILE = "runtime-stats.log";

    /** Why a primitive reported failure. */
    public enum PrimitiveFailure {
        /** No implementation branch, TODO, or an unassigned primitive. */
        UNIMPLEMENTED,
        /** The implementation ran but rejected its receiver or arguments. */
        FAILED,
        /** A Java exception escaped the primitive. */
        EXCEPTION,
        /** A documented Squeak 2.2 {@code primitiveFail} (for example 0/19/20-39/120/123). */
        INTENTIONAL
    }

    private final boolean enabled;
    private final long startedAtMillis = System.currentTimeMillis();

    private final AtomicLongArray primitiveCalls = new AtomicLongArray(MAX_PRIMITIVE_INDEX);
    private final AtomicLongArray primitiveFailures = new AtomicLongArray(MAX_PRIMITIVE_INDEX);
    private final AtomicLongArray unimplementedByIndex = new AtomicLongArray(MAX_PRIMITIVE_INDEX);
    private final AtomicLongArray failedByIndex = new AtomicLongArray(MAX_PRIMITIVE_INDEX);
    private final AtomicLongArray exceptionByIndex = new AtomicLongArray(MAX_PRIMITIVE_INDEX);
    private final AtomicLongArray intentionalByIndex = new AtomicLongArray(MAX_PRIMITIVE_INDEX);

    private final AtomicLong primitiveCallsTotal = new AtomicLong();
    private final AtomicLong primitiveFailuresTotal = new AtomicLong();
    private final AtomicLong unimplementedTotal = new AtomicLong();
    private final AtomicLong failedTotal = new AtomicLong();
    private final AtomicLong exceptionTotal = new AtomicLong();
    private final AtomicLong intentionalTotal = new AtomicLong();

    private final AtomicLong timerSamples = new AtomicLong();
    private final AtomicLong timerDelayTotalMillis = new AtomicLong();
    private final AtomicLong timerDelayMaxMillis = new AtomicLong();

    private final AtomicLong inputSamples = new AtomicLong();
    private final AtomicLong mouseQueueTotal = new AtomicLong();
    private final AtomicLong mouseQueueMax = new AtomicLong();
    private final AtomicLong keyboardQueueTotal = new AtomicLong();
    private final AtomicLong keyboardQueueMax = new AtomicLong();

    private final AtomicLong heartbeats = new AtomicLong();
    private final AtomicLong dirtyHeartbeats = new AtomicLong();
    private final AtomicLong paints = new AtomicLong();
    private final AtomicLong paintNanosTotal = new AtomicLong();
    private final AtomicLong paintNanosMax = new AtomicLong();

    private volatile boolean shutdownHookInstalled;

    /**
     * Creates a collector with the requested state. Package-visible tests use
     * this constructor; production code uses {@link #fromSystemProperties()}.
     */
    public RuntimeStats(boolean enabled) {
        this.enabled = enabled;
    }

    public static RuntimeStats fromSystemProperties() {
        return new RuntimeStats(SqueakConfig.ENABLE_RUNTIME_STATS);
    }

    public boolean isEnabled() {
        return enabled;
    }

    // region recording

    public void recordPrimitiveCall(int index) {
        if (!enabled) {
            return;
        }
        primitiveCallsTotal.incrementAndGet();
        if (isValidIndex(index)) {
            primitiveCalls.incrementAndGet(index);
        }
    }

    public void recordPrimitiveFailure(int index, PrimitiveFailure kind) {
        if (!enabled) {
            return;
        }
        PrimitiveFailure actual = kind == null ? PrimitiveFailure.FAILED : kind;
        primitiveFailuresTotal.incrementAndGet();
        if (isValidIndex(index)) {
            primitiveFailures.incrementAndGet(index);
            failureArray(actual).incrementAndGet(index);
        }
        switch (actual) {
            case UNIMPLEMENTED:
                unimplementedTotal.incrementAndGet();
                break;
            case EXCEPTION:
                exceptionTotal.incrementAndGet();
                break;
            case INTENTIONAL:
                intentionalTotal.incrementAndGet();
                break;
            case FAILED:
            default:
                failedTotal.incrementAndGet();
                break;
        }
    }

    /**
     * Records how late a timer wakeup was. Only meaningful samples (0..10s)
     * should be passed; the caller filters out clock wrap artifacts.
     */
    public void recordTimerDelayMillis(long delayMillis) {
        if (!enabled) {
            return;
        }
        long delay = Math.max(0, delayMillis);
        timerSamples.incrementAndGet();
        timerDelayTotalMillis.addAndGet(delay);
        updateMax(timerDelayMaxMillis, delay);
    }

    /**
     * Samples the input queues. Call at a low frequency (for example once per
     * second) because this is a gauge, not an event.
     */
    public void recordInputQueues(int mouseDepth, int keyboardDepth) {
        if (!enabled) {
            return;
        }
        int mouse = Math.max(0, mouseDepth);
        int keyboard = Math.max(0, keyboardDepth);
        inputSamples.incrementAndGet();
        mouseQueueTotal.addAndGet(mouse);
        keyboardQueueTotal.addAndGet(keyboard);
        updateMax(mouseQueueMax, mouse);
        updateMax(keyboardQueueMax, keyboard);
    }

    public void recordHeartbeat(boolean dirty) {
        if (!enabled) {
            return;
        }
        heartbeats.incrementAndGet();
        if (dirty) {
            dirtyHeartbeats.incrementAndGet();
        }
    }

    /**
     * Records the time spent inside Swing painting.
     */
    public void recordPaint(long nanos) {
        if (!enabled || nanos < 0) {
            return;
        }
        paints.incrementAndGet();
        paintNanosTotal.addAndGet(nanos);
        updateMax(paintNanosMax, nanos);
    }

    // endregion

    // region snapshot accessors

    public long getPrimitiveCallsTotal() {
        return primitiveCallsTotal.get();
    }

    public long getPrimitiveFailuresTotal() {
        return primitiveFailuresTotal.get();
    }

    public long getUnimplementedTotal() {
        return unimplementedTotal.get();
    }

    public long getFailedTotal() {
        return failedTotal.get();
    }

    public long getExceptionTotal() {
        return exceptionTotal.get();
    }

    public long getIntentionalTotal() {
        return intentionalTotal.get();
    }

    public long getPrimitiveCallCount(int index) {
        return isValidIndex(index) ? primitiveCalls.get(index) : 0;
    }

    public long getPrimitiveFailureCount(int index) {
        return isValidIndex(index) ? primitiveFailures.get(index) : 0;
    }

    public long getTimerSamples() {
        return timerSamples.get();
    }

    public long getTimerDelayMaxMillis() {
        return timerDelayMaxMillis.get();
    }

    public long getInputSamples() {
        return inputSamples.get();
    }

    public long getMouseQueueMax() {
        return mouseQueueMax.get();
    }

    public long getKeyboardQueueMax() {
        return keyboardQueueMax.get();
    }

    public long getHeartbeats() {
        return heartbeats.get();
    }

    public long getDirtyHeartbeats() {
        return dirtyHeartbeats.get();
    }

    public long getPaints() {
        return paints.get();
    }

    public long getPaintMaxNanos() {
        return paintNanosMax.get();
    }

    public long getPaintAverageNanos() {
        long count = paints.get();
        return count == 0 ? 0 : paintNanosTotal.get() / count;
    }

    // endregion

    // region reporting

    /**
     * Human-readable summary. Contains no process/thread identifiers so it can
     * be asserted in tests.
     */
    public String summary() {
        if (!enabled) {
            return "runtime statistics: disabled";
        }

        StringBuilder out = new StringBuilder(512);
        long uptimeSeconds = Math.max(0, (System.currentTimeMillis() - startedAtMillis) / 1000);
        out.append("==== DodoTalk runtime statistics ====").append('\n');
        out.append(String.format(Locale.ROOT, "uptime: %ds%n", uptimeSeconds));
        out.append(String.format(Locale.ROOT,
                "primitive calls: %d, failures: %d (unimplemented=%d, failed=%d, exception=%d, intentional=%d)%n",
                primitiveCallsTotal.get(), primitiveFailuresTotal.get(),
                unimplementedTotal.get(), failedTotal.get(),
                exceptionTotal.get(), intentionalTotal.get()));

        List<Integer> failing = new ArrayList<>();
        for (int i = 0; i < MAX_PRIMITIVE_INDEX; i++) {
            // Documented primitiveFail entries (for example the guard
            // primitive 19) are expected and would otherwise drown out the
            // actionable gaps in the top-N list.
            if (actionableFailures(i) > 0) {
                failing.add(i);
            }
        }
        failing.sort((a, b) -> {
            int byCount = Long.compare(actionableFailures(b), actionableFailures(a));
            return byCount != 0 ? byCount : Integer.compare(a, b);
        });

        if (failing.isEmpty()) {
            out.append("top failing primitives: none").append('\n');
        } else {
            out.append("top failing primitives (excluding documented primitiveFail):").append('\n');
            int limit = Math.min(10, failing.size());
            for (int i = 0; i < limit; i++) {
                int index = failing.get(i);
                out.append(String.format(Locale.ROOT,
                        "  #%d %s: %d failures (%d calls)%n",
                        index, dominantFailure(index),
                        actionableFailures(index), primitiveCalls.get(index)));
            }
        }

        List<Integer> called = new ArrayList<>();
        for (int i = 0; i < MAX_PRIMITIVE_INDEX; i++) {
            if (primitiveCalls.get(i) > 0) {
                called.add(i);
            }
        }
        called.sort((a, b) -> {
            int byCount = Long.compare(primitiveCalls.get(b), primitiveCalls.get(a));
            return byCount != 0 ? byCount : Integer.compare(a, b);
        });
        if (!called.isEmpty()) {
            out.append("top primitive calls:").append('\n');
            int limit = Math.min(10, called.size());
            for (int i = 0; i < limit; i++) {
                int index = called.get(i);
                out.append(String.format(Locale.ROOT, "  #%d: %d calls (%d failures)%n",
                        index, primitiveCalls.get(index), primitiveFailures.get(index)));
            }
        }

        out.append(String.format(Locale.ROOT,
                "timer: samples=%d, avg=%dms, max=%dms%n",
                timerSamples.get(), average(timerDelayTotalMillis.get(), timerSamples.get()),
                timerDelayMaxMillis.get()));
        out.append(String.format(Locale.ROOT,
                "input queues: samples=%d, mouse avg=%d max=%d, keyboard avg=%d max=%d%n",
                inputSamples.get(),
                average(mouseQueueTotal.get(), inputSamples.get()), mouseQueueMax.get(),
                average(keyboardQueueTotal.get(), inputSamples.get()), keyboardQueueMax.get()));
        out.append(String.format(Locale.ROOT,
                "display: heartbeats=%d, dirty=%d, paints=%d, avgPaint=%.2fms, maxPaint=%.2fms%n",
                heartbeats.get(), dirtyHeartbeats.get(), paints.get(),
                getPaintAverageNanos() / 1_000_000.0, paintNanosMax.get() / 1_000_000.0));

        return out.toString();
    }

    /** Prints the summary through the project logger and keeps a copy in logs/. */
    public void dump() {
        if (!enabled) {
            return;
        }
        String report = summary();
        for (String line : report.split("\\R")) {
            SqueakLogger.log_I(line);
        }
        writeReport(Path.of(DEFAULT_LOG_DIRECTORY, DEFAULT_REPORT_FILE), report);
    }

    /**
     * Writes the report to a file, creating the parent directory if needed.
     * Package-private for regression tests.
     */
    void writeReport(Path target, String report) {
        if (!enabled || target == null) {
            return;
        }
        try {
            Path parent = target.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(target, report, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (IOException e) {
            SqueakLogger.log_W("Cannot write runtime statistics to " + target, e);
        }
    }

    /**
     * Registers a shutdown hook that prints the summary when the JVM exits.
     * Normally installed once by {@code Starter.boot()}.
     */
    public synchronized void installShutdownHook() {
        if (!enabled || shutdownHookInstalled) {
            return;
        }
        shutdownHookInstalled = true;
        try {
            Thread hook = new Thread(this::dump, "dodotalk-runtime-stats");
            hook.setDaemon(false);
            Runtime.getRuntime().addShutdownHook(hook);
        } catch (IllegalStateException e) {
            // The JVM is already shutting down; there is nothing useful to do.
            SqueakLogger.log_W("Cannot install runtime statistics shutdown hook", e);
        } catch (SecurityException e) {
            SqueakLogger.log_W("Not allowed to install runtime statistics shutdown hook", e);
        }
    }

    // endregion

    private long actionableFailures(int index) {
        return primitiveFailures.get(index) - intentionalByIndex.get(index);
    }

    private PrimitiveFailure dominantFailure(int index) {
        long best = -1;
        PrimitiveFailure kind = PrimitiveFailure.UNIMPLEMENTED;
        if (unimplementedByIndex.get(index) > best) {
            best = unimplementedByIndex.get(index);
            kind = PrimitiveFailure.UNIMPLEMENTED;
        }
        if (failedByIndex.get(index) > best) {
            best = failedByIndex.get(index);
            kind = PrimitiveFailure.FAILED;
        }
        if (exceptionByIndex.get(index) > best) {
            kind = PrimitiveFailure.EXCEPTION;
        }
        return kind;
    }

    private AtomicLongArray failureArray(PrimitiveFailure kind) {
        switch (kind) {
            case UNIMPLEMENTED:
                return unimplementedByIndex;
            case EXCEPTION:
                return exceptionByIndex;
            case INTENTIONAL:
                return intentionalByIndex;
            case FAILED:
            default:
                return failedByIndex;
        }
    }

    private static boolean isValidIndex(int index) {
        return index >= 0 && index < MAX_PRIMITIVE_INDEX;
    }

    private static long average(long total, long samples) {
        return samples == 0 ? 0 : total / samples;
    }

    private static void updateMax(AtomicLong max, long value) {
        long current;
        while (value > (current = max.get())) {
            if (max.compareAndSet(current, value)) {
                return;
            }
        }
    }
}
