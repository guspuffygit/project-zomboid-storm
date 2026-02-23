package io.pzstorm.storm.advice;

import io.pzstorm.storm.event.core.StormEventDispatcher;
import io.pzstorm.storm.event.zomboid.OnTriggerLuaEvent;
import net.bytebuddy.asm.Advice;
import zombie.Lua.Event;

public class TriggerLuaEventAdvice {

    @Advice.OnMethodEnter
    public static void onTrigger(@Advice.This Event event, @Advice.Argument(2) Object[] params) {

        // SAFETY: The 'params' array is pooled/recycled by LuaEventManager.
        // We MUST clone it, otherwise the data will be wiped (nulled)
        // by the time the event listeners receive it.
        Object[] safeArgs;
        if (params != null && params.length > 0) {
            safeArgs = params.clone();
        } else {
            safeArgs = new Object[0];
        }

        StormEventDispatcher.dispatchEvent(new OnTriggerLuaEvent(event, safeArgs));
    }
}
