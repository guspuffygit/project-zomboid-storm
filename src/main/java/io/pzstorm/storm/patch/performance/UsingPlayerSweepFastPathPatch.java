package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Replaces the server-side body of {@code UsingPlayerUpdateSystem.update()} with the
 * registry-backed sweep in {@code UsingPlayerRegistry}. Live profiling (79 players) attributed ~6%
 * of main-thread CPU to this method — a per-tick null-check of every entity in the iso-object
 * bucket (123k+), while the number with a non-null {@code usingPlayer} at any moment is roughly the
 * number of players with a crafting/entity UI open (~0–10).
 *
 * <p>Server-only by registration gate ({@code StormEnv.isStormServer()}) — {@code
 * UsingPlayerUpdateSystem} is loaded by clients too. Must stay registered <em>before</em> {@code
 * UsingPlayerUpdatePatch} so the stopwatch advice wraps the skip and {@code
 * pz_using_player_update_call_duration_seconds} keeps timing both paths.
 *
 * <p>Kill switch: the {@code Storm.UsingPlayerSweepFastPath} sandbox option ({@code false} restores
 * the vanilla full-bucket scan; live-appliable via admin sandbox push — registry maintenance is
 * unconditional, so no entity is missed while the option is off). The sweep also permanently
 * reverts to vanilla if it ever throws.
 */
public class UsingPlayerSweepFastPathPatch extends StormClassTransformer {

    private static final String TARGET = "zombie.entity.UsingPlayerUpdateSystem";
    private static final String PKG = "io.pzstorm.storm.advice.usingplayerregistry.";

    public UsingPlayerSweepFastPathPatch() {
        super(TARGET);
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        TypeDescription target = typePool.describe(TARGET).resolve();
        if (target.getDeclaredMethods()
                .filter(ElementMatchers.named("update").and(ElementMatchers.takesArguments(0)))
                .isEmpty()) {
            throw new IllegalStateException(
                    "UsingPlayerSweepFastPathPatch: UsingPlayerUpdateSystem no longer declares"
                            + " update() — the name-string hook would silently no-op and"
                            + " reintroduce the full iso-bucket scan. Re-verify the patch against"
                            + " the current game source.");
        }
        return builder.visit(
                Advice.to(
                                typePool.describe(PKG + "UsingPlayerSweepFastPathAdvice").resolve(),
                                locator)
                        .on(
                                ElementMatchers.named("update")
                                        .and(ElementMatchers.takesArguments(0))));
    }
}
