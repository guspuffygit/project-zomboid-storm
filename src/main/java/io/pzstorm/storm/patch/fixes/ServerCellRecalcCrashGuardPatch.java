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
 * <h2>The failure this patches</h2>
 *
 * <p>A throw out of {@code RecalcAll2()} makes {@code ServerMap.preupdate()} drop the whole cell,
 * and a cell with a player in it reloads and throws again on the next relevance pass. See {@link
 * ServerCellRecalcCrashGuard} for the full write-up and the 2026-08-29 live incident.
 *
 * <h2>Why the {@code RecalcAll2} boundary and not {@code Load2}</h2>
 *
 * <p>Swallowing at the {@code RecalcAll2} method boundary lets the rest of {@code Load2} ({@code
 * loadVehicles}) run unchanged, with no duplicated cleanup logic that would need re-validation on
 * every game update.
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
