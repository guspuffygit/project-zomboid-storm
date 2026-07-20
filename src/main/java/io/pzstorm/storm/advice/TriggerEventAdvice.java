package io.pzstorm.storm.advice;

import io.pzstorm.storm.debugging.TriggeredEvents;
import io.pzstorm.storm.event.core.StormEventDispatcher;
import io.pzstorm.storm.event.zomboid.OnTriggerLuaEvent;
import net.bytebuddy.asm.Advice;
import zombie.Lua.Event;
import zombie.Lua.LuaEventManager;

/**
 * Bridges {@code LuaEventManager.triggerEvent} into Storm's typed event system. Runs on every Lua
 * event trigger — per-frame ({@code OnRenderTick}, {@code OnTick}) and per-object ({@code
 * OnZombieUpdate} fires once per zombie per update) events included — so the no-consumer case must
 * stay allocation-free: when no registered handler can observe the bridge's output ({@link
 * StormEventDispatcher#isLuaEventBridgeNeeded()}), the advice records the event name for the {@code
 * /printdebug} listing and bails before the event lookup, argument copy, event allocation and
 * reflective dispatch.
 *
 * <p>Two side effects of the bridge are intentionally absent while the gate is closed, both
 * restored automatically on the next trigger after a relevant handler registers: {@code
 * AddEvent(name)} no longer auto-creates events missing from {@code EventMap} (vanilla {@code
 * checkEvent} semantics apply — unknown events are skipped), and {@code LuaEvent.registerCallback}
 * no longer seeds empty events with a dummy closure (so vanilla's empty-callback early return runs
 * instead of invoking an empty chunk).
 */
public class TriggerEventAdvice {

    @Advice.OnMethodEnter
    public static void onTrigger(
            @Advice.Argument(0) String name, @Advice.AllArguments Object[] allArgs) {
        TriggeredEvents.add(name);
        if (!StormEventDispatcher.isLuaEventBridgeNeeded()) {
            return;
        }
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
