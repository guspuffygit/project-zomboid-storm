package io.pzstorm.storm.patch.fixes;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Restores the {@code Map.MapAllKnown} sandbox option in multiplayer, broken by 42.20.3.
 *
 * <p>42.20.3 moved a joining player's visited map data from {@code PlayerVisitedPacket} to {@code
 * RequestDataPacket.RequestID.PlayerVisited}. The old packet's {@code processClient} re-applied
 * {@code setKnownInCells} over the whole map after overwriting the client's {@code visited} array;
 * the new {@code WorldMapVisited.receiveRequestData} path dropped that step, so the server's
 * per-user bytes wipe the client's all-known pass and the map shows only where the player has been.
 *
 * <p>{@link io.pzstorm.storm.map.StormMapAllKnownSend} sets {@code BIT_KNOWN} on the copy the
 * server sends while the option is on — server-side, so vanilla clients are fixed too — and never
 * mutates the stored per-user data.
 *
 * <p>Fail-loud: the hook is name-string based. {@link #dynamicType} throws if {@code
 * sendRequestData} is no longer declared, so a vanilla rename surfaces at boot instead of silently
 * restoring the bug.
 *
 * <p>Registration-gated to the dedicated server ({@code StormEnv.isStormServer()}).
 */
public class WorldMapVisitedServerAllKnownPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.worldmapvisitedsend.";

    public WorldMapVisitedServerAllKnownPatch() {
        super("zombie.worldMap.WorldMapVisitedServer");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        TypeDescription target = typePool.describe(className).resolve();
        if (target.getDeclaredMethods()
                .filter(
                        ElementMatchers.named("sendRequestData")
                                .and(ElementMatchers.takesArguments(2)))
                .isEmpty()) {
            throw new IllegalStateException(
                    "WorldMapVisitedServerAllKnownPatch: WorldMapVisitedServer no longer declares"
                            + " sendRequestData(IConnection, ByteBufferWriter) — the Map.MapAllKnown"
                            + " sandbox option would silently stop working in multiplayer."
                            + " Re-verify the patch against the current game source.");
        }
        return builder.visit(
                Advice.to(typePool.describe(PKG + "WorldMapVisitedSendAdvice").resolve(), locator)
                        .on(
                                ElementMatchers.named("sendRequestData")
                                        .and(ElementMatchers.takesArguments(2))));
    }
}
