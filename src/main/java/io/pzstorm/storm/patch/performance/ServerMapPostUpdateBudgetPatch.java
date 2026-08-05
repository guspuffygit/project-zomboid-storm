package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Replaces the server-side body of {@code ServerMap.postupdate()} with the budgeted cell-unload
 * pass in {@code StormCellUnloadBudget}. Vanilla unloads every stale cell in a single tick; each
 * unload fans out into 64 {@code IsoChunk.removeFromWorld()} calls whose per-entity removals scan
 * the ~123k-element global entity array linearly, so a burst of stale cells (players logging off or
 * moving together) turns into a multi-hundred-ms tick spike (~11% of the main thread during a burst
 * window in live profiling at 79 players).
 *
 * <p>Server-only by registration gate ({@code StormEnv.isStormServer()}). Must stay registered
 * <em>before</em> {@code ServerMapPostUpdatePatch} so the stopwatch advice wraps the skip and keeps
 * timing both paths, and before {@code ServerMapPostUpdateWarmPatch} (which stays outermost and
 * owns the body when cell warming is enabled).
 *
 * <p>Kill switch: the {@code Storm.CellUnloadBudgetPerTick} sandbox option ({@code 0} restores the
 * vanilla unload-everything-now loop; live-appliable via admin sandbox push). The budgeted body
 * also permanently reverts to vanilla if it ever throws.
 */
public class ServerMapPostUpdateBudgetPatch extends StormClassTransformer {

    private static final String TARGET = "zombie.network.ServerMap";
    private static final String PKG = "io.pzstorm.storm.advice.servermappostupdatebudget.";

    public ServerMapPostUpdateBudgetPatch() {
        super(TARGET);
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        TypeDescription target = typePool.describe(TARGET).resolve();
        if (target.getDeclaredMethods()
                .filter(ElementMatchers.named("postupdate").and(ElementMatchers.takesArguments(0)))
                .isEmpty()) {
            throw new IllegalStateException(
                    "ServerMapPostUpdateBudgetPatch: ServerMap no longer declares postupdate() —"
                            + " the name-string hook would silently no-op and reintroduce the"
                            + " single-tick cell-unload burst. Re-verify the patch against the"
                            + " current game source.");
        }
        return builder.visit(
                Advice.to(
                                typePool.describe(PKG + "ServerMapPostUpdateBudgetAdvice")
                                        .resolve(),
                                locator)
                        .on(
                                ElementMatchers.named("postupdate")
                                        .and(ElementMatchers.takesArguments(0))));
    }
}
