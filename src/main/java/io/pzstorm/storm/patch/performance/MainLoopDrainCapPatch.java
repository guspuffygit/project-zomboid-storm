package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Caps the wall-clock time {@code GameServer.mainLoopDealWithNetData} can consume per outer-loop
 * spin. The advice is layered on top of {@link GameServerNetDataPatch}'s existing timing advice;
 * each transformer pass wraps the previous one, so on the server JVM the call chain becomes:
 *
 * <pre>
 *   MainLoopDrainCapAdvice.onEnter (skipOn fires here on overflow)
 *     -> GameServerNetDataAdvice.onEnter
 *       -> original mainLoopDealWithNetData body
 *     -> GameServerNetDataAdvice.onExit (records elapsed)
 *   MainLoopDrainCapAdvice.onExit (updates lastCallEndNanos)
 * </pre>
 *
 * <p>Registration must be gated on {@code StormEnv.isStormServer()} in {@code
 * StormClassTransformers} (HARD RULE: no Storm patches on the client JVM).
 */
public class MainLoopDrainCapPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.netdatadraincap.";

    public MainLoopDrainCapPatch() {
        super("zombie.network.GameServer");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(PKG + "MainLoopDrainCapAdvice").resolve(), locator)
                        .on(ElementMatchers.named("mainLoopDealWithNetData")));
    }
}
