package io.pzstorm.storm.advice.steamgameserver;

import io.pzstorm.storm.connection.SteamPlayerListReconciler;
import net.bytebuddy.asm.Advice;

/**
 * Skips the vanilla {@code SteamGameServer.AddPlayer(IsoPlayer)} / {@code RemovePlayer(IsoPlayer)}
 * wrapper bodies (spawn, disconnect, role-visibility toggles) while {@link
 * SteamPlayerListReconciler} owns the Steam user list. The native list's behavior on duplicate ids
 * is unknowable from Java, so exactly one writer may exist; the reconciler re-derives everything
 * those call sites express (spawn, disconnect, {@code HideFromSteamUserList}) on the next tick's
 * sweep. {@code UpdatePlayer(IsoPlayer)} is deliberately not matched — score updates against
 * already-registered ids are safe from either writer.
 */
public class SteamPlayerListWrapperSuppressAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter() {
        return SteamPlayerListReconciler.suppressVanillaWrites();
    }
}
