package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Maintains {@code UsingPlayerRegistry} — the identity set of {@code GameEntity} instances whose
 * {@code usingPlayer} is currently non-null — by advising both writers on {@code
 * zombie.entity.GameEntity}: the {@code setUsingPlayer(IsoPlayer)} setter (add on non-null, remove
 * on null) and the {@code receiveUpdateUsingPlayer} packet handler, whose server branch assigns the
 * field directly without going through the setter (and is the primary registration path on a
 * dedicated server — client UIs invoke the setter on the client JVM only).
 *
 * <p>Maintenance is unconditional on the server (runtime {@code GameServer.server} guard only, no
 * sandbox gate), so the registry is complete from boot and {@code Storm.UsingPlayerSweepFastPath}
 * can be flipped live. Server-only by registration gate ({@code StormEnv.isStormServer()}) — {@code
 * GameEntity} is loaded on client JVMs too (HARD RULE).
 */
public class GameEntityUsingPlayerTrackingPatch extends StormClassTransformer {

    private static final String TARGET = "zombie.entity.GameEntity";
    private static final String PKG = "io.pzstorm.storm.advice.usingplayerregistry.";

    public GameEntityUsingPlayerTrackingPatch() {
        super(TARGET);
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        TypeDescription target = typePool.describe(TARGET).resolve();
        requireDeclared(
                !target.getDeclaredMethods()
                        .filter(
                                ElementMatchers.named("setUsingPlayer")
                                        .and(ElementMatchers.takesArguments(1)))
                        .isEmpty(),
                "method setUsingPlayer(IsoPlayer) (registry maintenance target)");
        requireDeclared(
                !target.getDeclaredMethods()
                        .filter(ElementMatchers.named("receiveUpdateUsingPlayer"))
                        .isEmpty(),
                "method receiveUpdateUsingPlayer (direct-field-write sync target)");
        return builder.visit(
                        Advice.to(
                                        typePool.describe(PKG + "GameEntitySetUsingPlayerAdvice")
                                                .resolve(),
                                        locator)
                                .on(
                                        ElementMatchers.named("setUsingPlayer")
                                                .and(ElementMatchers.takesArguments(1))))
                .visit(
                        Advice.to(
                                        typePool.describe(
                                                        PKG
                                                                + "GameEntityReceiveUpdateUsingPlayerAdvice")
                                                .resolve(),
                                        locator)
                                .on(ElementMatchers.named("receiveUpdateUsingPlayer")));
    }

    private static void requireDeclared(boolean present, String member) {
        if (!present) {
            throw new IllegalStateException(
                    "GameEntityUsingPlayerTrackingPatch: GameEntity no longer declares "
                            + member
                            + " — the name-string hook would silently no-op, leaving the"
                            + " UsingPlayerRegistry incomplete and the optimized sweep unable to"
                            + " clear stale usingPlayer references. Re-verify the patch against"
                            + " the current game source.");
        }
    }
}
