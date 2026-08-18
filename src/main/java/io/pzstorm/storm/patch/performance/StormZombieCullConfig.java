package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.metrics.StormPerformanceSandboxMetrics;
import zombie.SandboxOptions;

/**
 * Read-only view of vanilla's {@code ZombieConfig.ZombiesCountBeforeDelete} sandbox option — the
 * number of zombies streamed to a single connection before the server culls the surplus.
 *
 * <p>Vanilla owns the cull decision: the budget is the per-connection surplus of the zombie list
 * streamed to each client, the option range is {@code 0..5000} with {@code 0} meaning "never cull".
 * This class reports the live value to the Prometheus gauge and startup analytics.
 */
public final class StormZombieCullConfig {

    /** Vanilla's compiled-in default for {@code ZombieConfig.ZombiesCountBeforeDelete}. */
    public static final int VANILLA_DEFAULT = 300;

    private StormZombieCullConfig() {}

    /**
     * Per-connection cull threshold vanilla is running with, falling back to vanilla's default
     * while the sandbox options are still loading.
     */
    public static int getThreshold() {
        SandboxOptions.IntegerSandboxOption option = vanillaOption();
        return option == null ? VANILLA_DEFAULT : option.getValue();
    }

    /** Republishes the live vanilla value to the Prometheus gauge. */
    public static void refreshMetric() {
        StormPerformanceSandboxMetrics.setZombieCullThreshold(getThreshold());
    }

    private static SandboxOptions.IntegerSandboxOption vanillaOption() {
        SandboxOptions sandbox = SandboxOptions.instance;
        return sandbox == null ? null : sandbox.zombieConfig.zombiesCountBeforeDeletion;
    }
}
