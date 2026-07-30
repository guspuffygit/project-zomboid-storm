package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.metrics.StormPerformanceSandboxMetrics;
import zombie.SandboxOptions;

/**
 * Read-only view of vanilla's {@code ZombieConfig.ZombiesCountBeforeDelete} sandbox option — the
 * number of zombies streamed to a single connection before the server culls the surplus.
 *
 * <p>Up to PZ 42.19.1 Storm owned this value through two bytecode patches on {@code
 * ZombieCountOptimiser} and a {@code Storm.ZombieCullThreshold} sandbox option, because vanilla
 * culling was unusable: the budget was measured against the <em>whole map's</em> live zombie count,
 * the option was capped at 500 with a minimum of 10 (so culling could not be turned off), and
 * {@code incrementZombie} never decremented its own budget, mass-deleting roughly 10% of the
 * population per frame on any overshoot.
 *
 * <p>42.20.0 rewrote that class and fixed all three: the budget is the per-connection surplus of
 * the zombie list actually streamed to each client, the option range became {@code 0..5000} with
 * {@code 0} meaning "never cull", and the decrement is present. Both patches and the Storm option
 * were removed — operators set the vanilla option directly. This class only reports the live value
 * to the Prometheus gauge and startup analytics.
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
