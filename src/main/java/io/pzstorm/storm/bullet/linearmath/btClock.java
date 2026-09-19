// Port of LinearMath/btQuickprof.h / .cpp, class btClock (Bullet 2.82), Linux gettimeofday branch.
package io.pzstorm.storm.bullet.linearmath;

/**
 * Wall-clock timer used only by the profiler. Linux build uses {@code gettimeofday}; this port uses
 * {@link System#nanoTime()} (the values never feed back into simulation state).
 *
 * <pre>
 * C++                                Java
 * ---------------------------------  ---------------------------------
 * btClock c(other); c = other;       new btClock(other); c.set(other)
 * unsigned long getTimeMilliseconds  long getTimeMilliseconds()
 * unsigned long getTimeMicroseconds  long getTimeMicroseconds()
 * </pre>
 */
public class btClock {
    private long mStartTime;

    public btClock() {
        reset();
    }

    public btClock(btClock other) {
        mStartTime = other.mStartTime;
    }

    /** {@code operator=} */
    public btClock set(btClock other) {
        mStartTime = other.mStartTime;
        return this;
    }

    /** Resets the initial reference time. */
    public void reset() {
        mStartTime = System.nanoTime();
    }

    /** Time in ms since the last call to reset or since the btClock was created. */
    public long getTimeMilliseconds() {
        return (System.nanoTime() - mStartTime) / 1_000_000L;
    }

    /** Time in us since the last call to reset or since the btClock was created. */
    public long getTimeMicroseconds() {
        return (System.nanoTime() - mStartTime) / 1_000L;
    }
}
