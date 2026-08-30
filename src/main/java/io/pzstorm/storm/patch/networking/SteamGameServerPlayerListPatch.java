package io.pzstorm.storm.patch.networking;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Makes the Steam-advertised player count (in-game server browser, A2S, and every other query
 * consumer) match the population the "server full" gate enforces, instead of only spawned
 * characters.
 *
 * <p>This transformer is the suppression half: it gates the vanilla {@code AddPlayer(IsoPlayer)} /
 * {@code RemovePlayer(IsoPlayer)} / {@code UpdatePlayer(IsoPlayer)} wrapper bodies behind {@code
 * SteamPlayerListReconciler.suppressVanillaWrites()}, so the per-tick reconciler (called from
 * {@code ServerTickAdvice}) is the Steam user list's single writer. {@code UpdatePlayer} must be
 * suppressed too: the reconciler keys entries by Storm-allocated table slots, not PZ player ids, so
 * a vanilla score push against a real player id would land on whichever user Storm registered at
 * that slot number. The private {@code (short, ...)} natives are excluded by the {@code isNative()}
 * filter — those are exactly what the reconciler drives. See {@link
 * io.pzstorm.storm.connection.SteamPlayerListReconciler} for the full design, the MaxPlayers clamp
 * rationale, and the failure/rollback story.
 */
public class SteamGameServerPlayerListPatch extends StormClassTransformer {

    private static final String ADVICE =
            "io.pzstorm.storm.advice.steamgameserver.SteamPlayerListWrapperSuppressAdvice";

    public SteamGameServerPlayerListPatch() {
        super("zombie.core.znet.SteamGameServer");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(ADVICE).resolve(), locator)
                        .on(
                                ElementMatchers.namedOneOf(
                                                "AddPlayer", "RemovePlayer", "UpdatePlayer")
                                        .and(ElementMatchers.not(ElementMatchers.isNative()))));
    }
}
