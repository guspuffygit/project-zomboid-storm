package io.pzstorm.storm.patch.fixes;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Fixes the {@code Map.MapAllKnown} sandbox option not surviving a server restart / re-login.
 *
 * <p>Vanilla {@code WorldMapVisitedServer.loadUser} never applies {@code MapAllKnown} to the
 * per-player byte array it loads from {@code <gameSaveWorld>/map_visited_server/<user>.zip}, so on
 * any login that finds an existing zip the server ships back a {@code PlayerVisitedPacket} that
 * overwrites the client's all-known initialization with the walked-only state from disk.
 *
 * <p>This patch hooks {@code loadUser(IConnection)} and, on exit, applies the all-known bits to the
 * dictionary entry and (re)sends a {@code PlayerVisitedPacket}, so the client array ends up
 * carrying both the actually-walked tiles and the full reveal. The mutated array is what later
 * {@code unloadUser} persists, so the fix is sticky across subsequent restarts.
 */
public class WorldMapAllKnownFixPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.worldmapallknown.";

    public WorldMapAllKnownFixPatch() {
        super("zombie.worldMap.WorldMapVisitedServer");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(PKG + "LoadUserMapAllKnownAdvice").resolve(), locator)
                        .on(
                                ElementMatchers.named("loadUser")
                                        .and(ElementMatchers.takesArguments(1))));
    }
}
