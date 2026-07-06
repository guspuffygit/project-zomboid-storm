package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.patch.networking.ServerLockFpsConfig;

/**
 * Server-side replacement math for {@code RandInterface.AdjustForFramerate(int)}.
 *
 * <p>Vanilla hardcodes the server branch to {@code chance * 0.33333334F} — i.e. 10 TPS over the
 * 30-fps baseline the chance values are authored for. {@code AdjustForFramerate} feeds {@code
 * Rand.Next(chance) == 0}-style rolls (fire spread, wound/infection, footstep noise, drunk
 * stumbles, random ambients), so at {@code Storm.ServerFps > 10} every such event would fire {@code
 * fps/10}× too often per real second. Scaling by the live lock fps ({@code fps / 30}) keeps the
 * per-second event rate constant at any tick rate, and is bit-identical to vanilla at the default
 * 10 ({@code 10f / 30f == 0.33333334F}).
 */
public final class StormRandFramerate {

    private StormRandFramerate() {}

    /** Mirrors vanilla's cast-truncating {@code (int)(chance * factor)}. */
    public static int adjustForServerFramerate(int chance) {
        return (int) (chance * (ServerLockFpsConfig.getCurrentLockFps() / 30.0F));
    }
}
