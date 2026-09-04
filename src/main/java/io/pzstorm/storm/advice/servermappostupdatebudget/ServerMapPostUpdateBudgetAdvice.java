package io.pzstorm.storm.advice.servermappostupdatebudget;

import io.pzstorm.storm.map.StormCellUnloadBudget;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

/**
 * Routes {@code ServerMap.postupdate()} through {@link StormCellUnloadBudget#runBudgeted}: a
 * non-zero verdict means the budgeted body ran (at most {@code Storm.CellUnloadBudgetPerTick} cell
 * unloads) and the vanilla body is skipped; zero (client JVM guard, budget 0, cell warming owns the
 * body, or the failure latch) leaves the vanilla body to run untouched.
 *
 * <p>Registered <em>before</em> the stopwatch-only {@code ServerMapPostUpdatePatch} in {@code
 * StormClassTransformers}, so the timing advice wraps this one and {@code
 * pz_server_map_post_update_call_duration_seconds} keeps measuring the full call on both paths.
 * {@code ServerMapPostUpdateWarmPatch} is registered later still and therefore sits outermost: with
 * cell warming active ({@code Storm.KeepCellsWarm} on, or draining after a live disable) the warm
 * advice body-replaces postupdate before this advice runs, and the budget helper's {@code
 * StormCellWarmer.isActive()} gate keeps the two body replacements mutually exclusive regardless of
 * ordering.
 */
public class ServerMapPostUpdateBudgetAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static int onEnter(@Advice.This Object thisObj) {
        if (!GameServer.server) {
            return 0;
        }
        return StormCellUnloadBudget.runBudgeted(thisObj) ? 1 : 0;
    }
}
