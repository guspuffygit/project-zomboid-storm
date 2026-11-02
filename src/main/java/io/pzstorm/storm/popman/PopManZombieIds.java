package io.pzstorm.storm.popman;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Native {@code popman::ZombieIDs}: one process-wide counter of persistent zombie ids, compared
 * unsigned. Nothing resets it between worlds, so a second world loaded in the same process starts
 * at the first world's high-water mark.
 */
public final class PopManZombieIds {

    private static final AtomicInteger COUNTER = new AtomicInteger();

    private PopManZombieIds() {}

    /** Zero means "no id" to the game, so it is never issued; a wrap past it lands on 1. */
    public static int next() {
        int old = COUNTER.getAndIncrement();
        if (old != 0) {
            return old;
        }
        COUNTER.set(2);
        return 1;
    }

    /** The next id {@link #next} will issue. */
    public static int peek() {
        return COUNTER.get();
    }

    public static void raiseTo(int floor) {
        int current = COUNTER.get();
        while (Integer.compareUnsigned(current, floor) < 0
                && !COUNTER.compareAndSet(current, floor)) {
            current = COUNTER.get();
        }
    }

    /** Tests only; the native counter has no reset. */
    static void resetForTest(int value) {
        COUNTER.set(value);
    }
}
