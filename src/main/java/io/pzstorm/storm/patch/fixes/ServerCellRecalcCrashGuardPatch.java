package io.pzstorm.storm.patch.fixes;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Attaches an exit advice to {@code zombie.network.ServerMap$ServerCell.RecalcAll2()} that swallows
 * (and loudly logs) any non-{@link VirtualMachineError} throwable. The actual fix logic lives in
 * {@link ServerCellRecalcCrashGuard}.
 *
 * <h2>The bug this patches</h2>
 *
 * <p>{@code Load2()} runs {@code RecalcAll2()} <i>before</i> its own bookkeeping ({@code
 * loaded2.remove(i)} / {@code return true}), so a single throw leaves the cell in the static {@code
 * loaded2} list and in {@code ServerMap.toLoad} forever. {@code ServerMap.preupdate()} — the first
 * statement of {@code GameServer.main}'s frame-step {@code try} — then re-throws every tick,
 * skipping the whole frame body: the world freezes at full tick rate while network/chat/HTTP
 * threads stay alive, and the 25-per-process exception log budget hides the loop almost
 * immediately. See {@link ServerCellRecalcCrashGuard} for the full write-up and the 2026-08-29 live
 * incident that motivated it.
 *
 * <h2>Why the {@code RecalcAll2} boundary and not {@code Load2}</h2>
 *
 * <p>Swallowing at the {@code RecalcAll2} method boundary lets the vanilla {@code Load2} success
 * path do the eviction itself ({@code loaded2.remove}, {@code loadVehicles}, {@code return true} →
 * driver removes the cell from {@code toLoad}) — no list surgery, no duplicated cleanup logic that
 * would need re-validation on every game update. Guarding inside {@code Load2} would require
 * rewriting the mid-loop control flow rather than a method-boundary advice.
 *
 * <h2>Registration</h2>
 *
 * <p>Gated server-only in {@code StormClassTransformers}, registered after {@code
 * ServerCellRecalcAll2Patch} (the timing advice) so the guard wraps it and the step timing still
 * records on a swallowed throw.
 */
public class ServerCellRecalcCrashGuardPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.servercellrecalcguard.";

    public ServerCellRecalcCrashGuardPatch() {
        super("zombie.network.ServerMap$ServerCell");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(
                                typePool.describe(PKG + "ServerCellRecalcCrashGuardAdvice")
                                        .resolve(),
                                locator)
                        .on(
                                ElementMatchers.named("RecalcAll2")
                                        .and(ElementMatchers.takesArguments(0))));
    }
}
