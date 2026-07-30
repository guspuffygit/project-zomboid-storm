package io.pzstorm.storm.patch.performance;

import java.util.Map;
import java.util.WeakHashMap;
import zombie.GameTime;
import zombie.iso.objects.IsoGenerator;

/**
 * Tracks, per generator, the last in-game hour at which the full {@code
 * setSurroundingElectricity()} scan ran, so {@code SkipServerScanAdvice} can re-arm the vanilla
 * scan at most once per in-game hour.
 *
 * <p>Why an hourly refresh exists at all: the advice skips the scan that recomputes {@code
 * totalPowerUsing} (the fuel-drain multiplier), so between activations the value drifts as items in
 * range change state. 42.20.0 made that drift matter more — {@code IsoLightSwitch.addToWorld()} now
 * registers switches as generator loads and they toggle at dusk/dawn with no generator activation
 * change. One full scan per activated generator per in-game hour bounds the drift at negligible
 * cost.
 *
 * <p>Keys are compared by identity ({@code IsoObject} does not override {@code equals}/{@code
 * hashCode}) and held weakly so removed generators drop out on GC. Called from advice inlined into
 * {@code IsoGenerator.update()}, so both methods must be {@code public}; the map itself is only
 * touched from inside this class and stays private.
 */
public final class IsoGeneratorScanRefresh {

    private static final Map<IsoGenerator, Integer> LAST_SCAN_HOUR = new WeakHashMap<>();

    private IsoGeneratorScanRefresh() {}

    /**
     * Returns {@code true} when the in-game hour has rolled over since this generator's last
     * recorded scan and the generator is activated with a valid square — i.e. the advice should
     * re-arm {@code updateSurrounding} so the vanilla tail block runs the full scan once. Stamps
     * the current hour on first sight and on every rollover, so a deactivated generator at rollover
     * simply waits for the next hour.
     */
    public static boolean shouldRefreshHourly(IsoGenerator generator) {
        int hour = (int) GameTime.getInstance().getWorldAgeHours();
        Integer last;
        synchronized (LAST_SCAN_HOUR) {
            last = LAST_SCAN_HOUR.get(generator);
            if (last != null && last == hour) {
                return false;
            }
            LAST_SCAN_HOUR.put(generator, hour);
        }
        if (last == null) {
            return false;
        }
        return generator.isActivated() && generator.getSquare() != null;
    }

    /**
     * Records that the vanilla tail scan runs this tick — called on the advice's cold-start
     * fall-through (where {@code totalPowerUsing <= 0} routes to the original method) so the hourly
     * re-arm does not fire redundantly right after.
     */
    public static void markScanned(IsoGenerator generator) {
        int hour = (int) GameTime.getInstance().getWorldAgeHours();
        synchronized (LAST_SCAN_HOUR) {
            LAST_SCAN_HOUR.put(generator, hour);
        }
    }
}
