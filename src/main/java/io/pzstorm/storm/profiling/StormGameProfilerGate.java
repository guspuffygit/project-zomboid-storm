package io.pzstorm.storm.profiling;

/**
 * Process-wide count of {@code GameProfiler} instances (one per thread, {@code ThreadLocal}) whose
 * {@code isRunning} flag is currently true. That flag is written only by {@code startFrame} /
 * {@code endFrame}, both advised to report the before/after value here, so {@code running == 0}
 * means "no thread's profiler is on" and every {@code AbstractPerformanceProfileProbe.start()} /
 * {@code GameProfiler.isRunning()} call can answer without the {@code
 * ArrayList<String>.contains(Thread.currentThread().getName())} thread check and the {@code
 * ThreadLocal} read vanilla performs first. The probes wrap most update stages (1.8% of the ATF
 * server main thread with the profiler off, scan #10, 2026-09-02).
 *
 * <p>{@code running} is volatile and updated under the class lock; a thread's own transition is
 * ordered before its next probe call, and a probe only ever consults its own thread's profiler, so
 * the gate cannot report "off" for a thread whose profiler is on.
 */
public final class StormGameProfilerGate {

    public static volatile int running;

    private StormGameProfilerGate() {}

    public static synchronized void onTransition(boolean before, boolean after) {
        if (before != after) {
            running += after ? 1 : -1;
        }
    }
}
