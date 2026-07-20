package io.pzstorm.storm.advice.eventtrigger;

import java.util.ArrayList;
import net.bytebuddy.asm.Advice;
import se.krka.kahlua.integration.LuaCaller;
import se.krka.kahlua.vm.LuaClosure;
import zombie.GameProfiler;
import zombie.Lua.Event;
import zombie.Lua.LuaManager;
import zombie.core.logger.ExceptionLogger;
import zombie.debug.DebugOptions;

/**
 * Advice for {@code zombie.Lua.Event.trigger(KahluaTable, LuaCaller, Object[])}.
 *
 * <p>The vanilla callback loop pays two per-callback taxes on every trigger, profiler on or off:
 *
 * <ul>
 *   <li>{@code profiler.profile("Lua - " + this.name)} — the label is concatenated (a fresh String
 *       allocation plus a {@code ThreadLocal.get}) before {@code profile()} can short-circuit to
 *       {@code null}. With per-frame events ({@code OnRenderTick}, {@code OnTick}) and per-object
 *       events ({@code OnZombieUpdate} fires once per zombie per update) this is a steady garbage
 *       stream. Live sampling while driving attributed ~3-4% of MainThread to trigger dispatch
 *       machinery.
 *   <li>{@code this.callbacks.contains(closure)} — an O(n) {@code ArrayList} scan after <em>every
 *       </em> callback to detect self-removal during iteration, making each trigger O(n²) in
 *       callback count.
 * </ul>
 *
 * <p>The advice replaces the body with an equivalent loop only when both observability paths are
 * off ({@code GameProfiler.isRunning()} false and the {@code slowLuaEvents} debug check disabled) —
 * otherwise it falls through to the vanilla body untouched. The fast loop drops the label concat
 * entirely and only runs the {@code contains} scan when the callback list's size changed during the
 * callback, which is the only situation vanilla's scan can detect anyway, with one documented
 * exception: a callback that removes itself AND adds a different callback in the same invocation
 * (net size unchanged) will not trigger vanilla's index adjustment, so the callback that shifted
 * into its slot is skipped for that one trigger. No known mod does this; the pattern self-corrects
 * on the next trigger.
 *
 * <p>Threading: {@code callbacks} is only mutated on MainThread and {@code trigger} only runs on
 * MainThread (off-thread triggers are queued by {@code LuaEventManager}), same as vanilla.
 */
public class EventTriggerFastPathAdvice {

    /**
     * Returns 0 to run the vanilla body (profiling or slow-event checks active), 1 when the fast
     * loop ran (return true), 2 when the callback list was empty (return false).
     */
    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static int onEnter(
            @Advice.This Event self,
            @Advice.Argument(1) LuaCaller caller,
            @Advice.Argument(2) Object[] params) {
        if (GameProfiler.isRunning() || DebugOptions.instance.checks.slowLuaEvents.getValue()) {
            return 0;
        }
        ArrayList<LuaClosure> callbacks = self.callbacks;
        if (callbacks.isEmpty()) {
            return 2;
        }
        for (int n = 0; n < callbacks.size(); n++) {
            LuaClosure closure = callbacks.get(n);
            int sizeBefore = callbacks.size();
            try {
                caller.protectedCallVoid(LuaManager.thread, closure, params);
            } catch (Exception e) {
                ExceptionLogger.logException(e);
            }
            if (callbacks.size() != sizeBefore && !callbacks.contains(closure)) {
                n--;
            }
        }
        return 1;
    }

    @Advice.OnMethodExit
    public static void onExit(
            @Advice.Enter int mode, @Advice.Return(readOnly = false) boolean result) {
        if (mode == 1) {
            result = true;
        } else if (mode == 2) {
            result = false;
        }
    }
}
