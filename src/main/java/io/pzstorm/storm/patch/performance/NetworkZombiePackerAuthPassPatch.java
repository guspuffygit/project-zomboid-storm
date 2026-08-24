package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Brackets the private per-tick {@code NetworkZombiePacker.updateAuth()} pass with {@code
 * StormZombieAuthScan.beginPass()}/{@code endPass()} — building (and afterwards releasing) the
 * connection/player snapshot that {@code ZombieAuthScanFastPathPatch} consumes per zombie. The
 * vanilla loop body itself is untouched.
 *
 * <p>Re-validate on game update: {@code NetworkZombiePacker.postupdate()} must still funnel the
 * whole zombie list through the private zero-argument {@code updateAuth()} (its only caller,
 * NetworkZombiePacker.java:153 in 42.20.3); if that loop moves, the snapshot bracket moves with it
 * or the fast path silently never activates (fail-open to vanilla).
 */
public class NetworkZombiePackerAuthPassPatch extends StormClassTransformer {

    private static final String TARGET = "zombie.popman.NetworkZombiePacker";
    private static final String PKG = "io.pzstorm.storm.advice.zombieauthscan.";

    public NetworkZombiePackerAuthPassPatch() {
        super(TARGET);
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        TypeDescription target = typePool.describe(TARGET).resolve();
        if (target.getDeclaredMethods()
                .filter(ElementMatchers.named("updateAuth").and(ElementMatchers.takesArguments(0)))
                .isEmpty()) {
            throw new IllegalStateException(
                    "NetworkZombiePackerAuthPassPatch: NetworkZombiePacker no longer declares the"
                            + " zero-argument updateAuth() pass — the name-string hook would"
                            + " silently no-op and the StormZombieAuthScan fast path would never"
                            + " activate. Re-verify the patch against the current game source.");
        }
        return builder.visit(
                Advice.to(
                                typePool.describe(PKG + "NetworkZombiePackerAuthPassAdvice")
                                        .resolve(),
                                locator)
                        .on(
                                ElementMatchers.named("updateAuth")
                                        .and(ElementMatchers.takesArguments(0))));
    }
}
