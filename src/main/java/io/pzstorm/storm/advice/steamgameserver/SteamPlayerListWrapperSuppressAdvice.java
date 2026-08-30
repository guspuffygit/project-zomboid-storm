package io.pzstorm.storm.advice.steamgameserver;

import io.pzstorm.storm.connection.SteamPlayerListReconciler;
import net.bytebuddy.asm.Advice;

/**
 * Skips the vanilla {@code SteamGameServer.AddPlayer(IsoPlayer)} / {@code RemovePlayer(IsoPlayer)}
 * / {@code UpdatePlayer(IsoPlayer)} wrapper bodies (spawn, disconnect, role-visibility toggles,
 * zombie-kill score pushes) while {@link SteamPlayerListReconciler} owns the Steam user list.
 * Exactly one writer may exist: the reconciler keys entries by Storm-allocated table slots rather
 * than PZ player ids, so any vanilla write against a real player id — including {@code
 * UpdatePlayer}, whose native updates whatever <em>active</em> entry sits at the given id — would
 * hit the wrong user's entry. The reconciler re-derives everything those call sites express (spawn,
 * disconnect, {@code HideFromSteamUserList}, score changes) on the next tick's sweep.
 */
public class SteamPlayerListWrapperSuppressAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter() {
        return SteamPlayerListReconciler.suppressVanillaWrites();
    }
}
