package org.dodotalk.input;

import org.dodotalk.core.SqueakVM;
import org.dodotalk.uilts.SqueakLogger;

import java.util.Objects;

/**
 * Heartbeat that wakes the VM for input/timer polling.
 * <p>
 * The previous implementation exposed a transient boolean for about 200
 * nanoseconds and then cleared it. The VM had to observe exactly that window,
 * otherwise it kept waiting. This thread now publishes a monotonic signal
 * through {@link SqueakVM#signalInputEvent()}; the signal stays pending until
 * the VM consumes it.
 * <p>
 * Mouse and keyboard listeners also signal directly, so actual input is
 * delivered immediately; this heartbeat covers timers and polling while the
 * user is idle.
 */
public class InputNotifyThread extends Thread {

    /** Matches the previous 30 FPS heartbeat without the fragile transient flag. */
    public static final long DEFAULT_PERIOD_MILLIS = 33;

    private final Runnable signal;
    private final long periodMillis;
    private volatile boolean running = true;

    public InputNotifyThread(SqueakVM vm) {
        this(Objects.requireNonNull(vm, "vm")::signalInputEvent, DEFAULT_PERIOD_MILLIS);
    }

    /**
     * Test-friendly constructor: the notifier is just a signal callback.
     */
    public InputNotifyThread(Runnable signal, long periodMillis) {
        this.signal = Objects.requireNonNull(signal, "signal");
        this.periodMillis = Math.max(1, periodMillis);
        setName("dodotalk-input-notify");
        setDaemon(true);
    }

    @Override
    public void run() {
        while (running) {
            try {
                signal.run();
                Thread.sleep(periodMillis);
            } catch (InterruptedException e) {
                if (!running) {
                    break;
                }
                // A spurious interrupt must not kill the heartbeat.
            } catch (RuntimeException e) {
                SqueakLogger.log_E("Input notification failed", e);
            }
        }
        SqueakLogger.log_D("Quit InputNotifyThread");
    }

    public void quit() {
        running = false;
        interrupt();
    }

    public boolean isRunning() {
        return running;
    }
}
