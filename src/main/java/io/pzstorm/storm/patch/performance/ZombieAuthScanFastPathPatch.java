package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Routes {@code NetworkZombieManager.updateAuth(IsoZombie)} through the snapshot-backed {@code
 * StormZombieAuthScan} fast path while a {@code NetworkZombiePacker.updateAuth()} pass is active
 * (see {@code NetworkZombiePackerAuthPassPatch} for the pass bracket).
 *
 * <p>Vanilla's ownership scan is the only server tick cost that grows with the product of player
 * count and zombie count — per zombie it walks every connection and every player slot, probes the
 * delayed-disconnect map per connection, and reaches the owner through repeated ECS component
 * probes. Live profiling on ATF production (2026-08-24, 112 players, 7,000 zombies) attributed 6.7%
 * of the main thread to it. The fast path flattens the (connection, player) rows into primitive
 * arrays once per pass and fetches the zombie's {@code NetworkZombieComponent} once — see {@code
 * StormZombieAuthScan} for the outcome-identity argument.
 *
 * <p>Kill switch: the {@code Storm.ZombieAuthFastPath} sandbox option ({@code false} restores the
 * vanilla scan; live-appliable); auto-reverts to vanilla permanently if the fast path ever throws.
 * Server-only by registration gate.
 *
 * <p>Re-validate on game update: the fast path mirrors the vanilla body of {@code
 * updateAuth(IsoZombie)} — the 2s {@code lastChangeOwner} gate, the rotate-ownership option branch
 * (routed to vanilla), the grapple/target early-outs, the golden-ratio (1.618034) owner hysteresis,
 * the reanimated-corpse sweep, and the final {@code RelevantTo(..., (range - 2) * 10)} check
 * (NetworkZombieManager.java:59 in 42.20.3). Any change to that body must be re-mirrored.
 */
public class ZombieAuthScanFastPathPatch extends StormClassTransformer {

    private static final String TARGET = "zombie.popman.NetworkZombieManager";
    private static final String PKG = "io.pzstorm.storm.advice.zombieauthscan.";

    public ZombieAuthScanFastPathPatch() {
        super(TARGET);
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        TypeDescription target = typePool.describe(TARGET).resolve();
        if (target.getDeclaredMethods()
                .filter(ElementMatchers.named("updateAuth").and(ElementMatchers.takesArguments(1)))
                .isEmpty()) {
            throw new IllegalStateException(
                    "ZombieAuthScanFastPathPatch: NetworkZombieManager no longer declares"
                            + " updateAuth(IsoZombie) — the name-string hook would silently no-op"
                            + " and reintroduce the O(connections x players) per-zombie ownership"
                            + " scan. Re-verify the patch against the current game source.");
        }
        return builder.visit(
                Advice.to(
                                typePool.describe(PKG + "NetworkZombieManagerAuthScanAdvice")
                                        .resolve(),
                                locator)
                        .on(
                                ElementMatchers.named("updateAuth")
                                        .and(ElementMatchers.takesArguments(1))));
    }
}
