package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Swaps {@code ServerMap.releventNow} for {@link io.pzstorm.storm.util.StormFastContainsList} at
 * construction time: {@code shouldBeLoaded} probes {@code releventNow.contains(cell)} for every
 * server cell on every update pass (ATF profile 2026-08-26, 135 players: part of the ~2.4% of main
 * spent in {@code ArrayList} linear scans). Element type {@code ServerMap.ServerCell} overrides
 * neither {@code equals} nor {@code hashCode}, so mirror semantics match the vanilla scan.
 *
 * <p>The field is public and non-final and is never reassigned by vanilla code, so a single
 * constructor-exit swap covers the singleton's lifetime.
 *
 * <p>Server-only by registration gate ({@code StormEnv.isStormServer()}).
 */
public class ServerMapReleventNowFastContainsPatch extends StormClassTransformer {

    private static final String TARGET = "zombie.network.ServerMap";
    private static final String PKG = "io.pzstorm.storm.advice.fastcontains.";

    public ServerMapReleventNowFastContainsPatch() {
        super(TARGET);
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        TypeDescription target = typePool.describe(TARGET).resolve();
        if (target.getDeclaredFields().filter(ElementMatchers.named("releventNow")).isEmpty()) {
            throw new IllegalStateException(
                    "ServerMapReleventNowFastContainsPatch: ServerMap no longer declares"
                            + " releventNow — the constructor swap would silently leave the"
                            + " vanilla list in place. Re-verify against the current game"
                            + " source.");
        }
        return builder.visit(
                Advice.to(
                                typePool.describe(PKG + "ServerMapReleventNowSwapAdvice").resolve(),
                                locator)
                        .on(ElementMatchers.isConstructor()));
    }
}
