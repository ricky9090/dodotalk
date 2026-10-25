package org.dodotalk.uilts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the opt-in runtime diagnostics (plan task 1.18).
 */
class RuntimeStatsTest {

    @Test
    @DisplayName("primitive failures are counted by index and classified")
    void classifiesPrimitiveFailures() {
        RuntimeStats stats = new RuntimeStats(true);

        stats.recordPrimitiveCall(65);
        stats.recordPrimitiveFailure(65, RuntimeStats.PrimitiveFailure.UNIMPLEMENTED);
        stats.recordPrimitiveCall(104);
        stats.recordPrimitiveFailure(104, RuntimeStats.PrimitiveFailure.UNIMPLEMENTED);
        stats.recordPrimitiveCall(133);
        stats.recordPrimitiveFailure(133, RuntimeStats.PrimitiveFailure.FAILED);
        stats.recordPrimitiveFailure(19, RuntimeStats.PrimitiveFailure.INTENTIONAL);

        assertEquals(3, stats.getPrimitiveCallsTotal());
        assertEquals(4, stats.getPrimitiveFailuresTotal());
        assertEquals(2, stats.getUnimplementedTotal());
        assertEquals(1, stats.getFailedTotal());
        assertEquals(0, stats.getExceptionTotal());
        assertEquals(1, stats.getIntentionalTotal());
        assertEquals(1, stats.getPrimitiveCallCount(65));
        assertEquals(1, stats.getPrimitiveFailureCount(65));

        String summary = stats.summary();
        assertTrue(summary.contains("#65"), "summary must list failing primitives: " + summary);
        assertTrue(summary.contains("unimplemented=2"), summary);
        assertTrue(summary.contains("failed=1"), summary);
        assertTrue(summary.contains("intentional=1"), summary);
    }

    @Test
    @DisplayName("timer, input and paint gauges are summarized")
    void summarizesRuntimeGauges() {
        RuntimeStats stats = new RuntimeStats(true);

        stats.recordTimerDelayMillis(7);
        stats.recordTimerDelayMillis(3);
        stats.recordInputQueues(2, 3);
        stats.recordInputQueues(1, 5);
        stats.recordHeartbeat(true);
        stats.recordHeartbeat(false);
        stats.recordPaint(1_000_000L);
        stats.recordPaint(3_000_000L);

        assertEquals(2, stats.getTimerSamples());
        assertEquals(7, stats.getTimerDelayMaxMillis());
        assertEquals(2, stats.getInputSamples());
        assertEquals(2, stats.getMouseQueueMax());
        assertEquals(5, stats.getKeyboardQueueMax());
        assertEquals(2, stats.getHeartbeats());
        assertEquals(1, stats.getDirtyHeartbeats());
        assertEquals(2, stats.getPaints());
        assertEquals(3_000_000L, stats.getPaintMaxNanos());
        assertEquals(2_000_000L, stats.getPaintAverageNanos());

        String summary = stats.summary();
        assertTrue(summary.contains("timer: samples=2"), summary);
        assertTrue(summary.contains("mouse avg=1 max=2"), summary);
        assertTrue(summary.contains("keyboard avg=4 max=5"), summary);
        assertTrue(summary.contains("heartbeats=2, dirty=1"), summary);
    }

    @Test
    @DisplayName("the summary can be written to a report file")
    void writesReportFile() throws Exception {
        RuntimeStats stats = new RuntimeStats(true);
        stats.recordPrimitiveFailure(65, RuntimeStats.PrimitiveFailure.UNIMPLEMENTED);

        Path directory = Files.createTempDirectory("jsqueak-stats-");
        Path target = directory.resolve("logs/runtime-stats.log");
        stats.writeReport(target, stats.summary());

        assertTrue(Files.isRegularFile(target), "report file was not created");
        String report = Files.readString(target);
        assertTrue(report.contains("==== DodoTalk runtime statistics ===="), report);
        assertTrue(report.contains("#65"), report);
    }

    @Test
    @DisplayName("a disabled collector records nothing")
    void disabledCollectorIsInert() {
        RuntimeStats stats = new RuntimeStats(false);

        stats.recordPrimitiveCall(65);
        stats.recordPrimitiveFailure(65, RuntimeStats.PrimitiveFailure.UNIMPLEMENTED);
        stats.recordTimerDelayMillis(50);
        stats.recordInputQueues(10, 10);
        stats.recordHeartbeat(true);
        stats.recordPaint(1_000_000L);

        assertFalse(stats.isEnabled());
        assertEquals(0, stats.getPrimitiveCallsTotal());
        assertEquals(0, stats.getPrimitiveFailuresTotal());
        assertEquals(0, stats.getTimerSamples());
        assertEquals(0, stats.getInputSamples());
        assertEquals(0, stats.getHeartbeats());
        assertEquals(0, stats.getPaints());
        assertEquals("runtime statistics: disabled", stats.summary());
    }
}
