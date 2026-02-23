package io.pzstorm.storm.advice;

import io.pzstorm.storm.event.core.StormEventDispatcher;
import io.pzstorm.storm.event.zomboid.OnTriggerLuaEvent;
import net.bytebuddy.asm.Advice;
import zombie.Lua.Event;
import zombie.Lua.LuaEventManager;

public class TriggerEventAdvice {

    @Advice.OnMethodEnter
    public static void onTrigger(
            @Advice.Argument(0) String name, @Advice.AllArguments Object[] allArgs) {
        Event event = LuaEventManager.AddEvent(name);

        Object[] eventArgs;
        if (allArgs.length > 1) {
            eventArgs = new Object[allArgs.length - 1];
            System.arraycopy(allArgs, 1, eventArgs, 0, eventArgs.length);
        } else {
            eventArgs = new Object[0];
        }

        StormEventDispatcher.dispatchEvent(new OnTriggerLuaEvent(event, eventArgs));
    }
}
