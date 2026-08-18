package io.pzstorm.storm.advice.entityindex;

import io.pzstorm.storm.entity.StormEntityIndex;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

/**
 * Enter-skip hook on {@code zombie.entity.util.Array.removeValue(T, boolean)}: for a tracked array
 * (the global entity array or a bucket's), {@link StormEntityIndex#onRemoveValue} performs the
 * removal via index lookup + swap-with-last {@code removeIndex} (with an identity self-check first)
 * and the vanilla linear scan is skipped; the exit advice then materializes {@code removeValue}'s
 * boolean result from the verdict. Untracked arrays — the overwhelming majority of calls — fall
 * through to the vanilla body after one field read (the injected index slot is null).
 */
public class EntityArrayRemoveValueAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static int onEnter(
            @Advice.This Object array,
            @Advice.Argument(0) Object value,
            @Advice.Argument(1) boolean identity) {
        if (!GameServer.server) {
            return 0;
        }
        return StormEntityIndex.onRemoveValue(array, value, identity);
    }

    @Advice.OnMethodExit
    public static void onExit(
            @Advice.Enter int verdict, @Advice.Return(readOnly = false) boolean result) {
        if (verdict != 0) {
            result = verdict == 1;
        }
    }
}
