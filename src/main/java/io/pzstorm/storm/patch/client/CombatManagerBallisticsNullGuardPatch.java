package io.pzstorm.storm.patch.client;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Client-only. Guards the null {@code BallisticsController} dereference in {@code
 * CombatManager.isHittableBallisticsTarget}:
 *
 * <pre>NullPointerException: Cannot invoke
 *   "zombie.core.physics.BallisticsController.getIsoAimingPosition()"
 *   because "ballisticsController" is null
 *   at CombatManager.isHittableBallisticsTarget ... calculateHitInfoList ... updateReticle
 *   at CombatManager.update at GameWindow.logic</pre>
 *
 * <p>The throw latches rather than recovering. {@code GameWindow.logic} calls {@code
 * CombatManager.update()} <em>before</em> {@code states.update()}, and the controller is only
 * (re)allocated by {@code IsoGameCharacter.updateBallistics()} inside the character update that
 * {@code states.update()} drives. Once the reticle test throws, {@code states.update()} is skipped
 * for that frame, the controller is never rebuilt, and the same NPE repeats on every subsequent
 * frame while the main loop keeps running; {@code IsoWorld.frameNo} stops advancing.
 *
 * <p>The 3-arg {@code isHittableBallisticsTarget} dereferences the controller unguarded while its
 * sibling {@code calculateBallistics} null-checks the identical field. Storm skips the 3-arg body
 * when the controller is null so it returns {@code false}; the 4-arg caller then falls through to
 * its existing {@code isPointWithinDistance} cone test, and the character update on the same frame
 * reallocates the controller. The producer of the null is not pinned down (the release path is
 * {@code removeFromWorld}; the allocation gate is {@code isAimedFirearm()} while the reticle gate
 * is {@code isRanged()}); the guard is correct for every producer.
 *
 * <p>Why a client bytecode patch: the throw is inside the client's per-frame combat update, a
 * private Java method with no Lua event and no server-observable state, so none of the cheaper
 * tiers reach it. Fail-soft: the advice is a null test on the argument the vanilla body is about to
 * dereference, {@code suppress = Throwable.class} resolves any advice failure to "run vanilla", and
 * a missing target method fails the transform loudly at weave time (logged, class left vanilla)
 * rather than silently no-opping.
 */
public class CombatManagerBallisticsNullGuardPatch extends StormClassTransformer {

    private static final String TARGET = "zombie.CombatManager";
    private static final String METHOD = "isHittableBallisticsTarget";
    private static final String ADVICE =
            "io.pzstorm.storm.advice.client.ballisticsnullguard"
                    + ".CombatManagerBallisticsNullGuardAdvice";

    public CombatManagerBallisticsNullGuardPatch() {
        super(TARGET);
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        TypeDescription target = typePool.describe(TARGET).resolve();
        if (target.getDeclaredMethods()
                .filter(ElementMatchers.named(METHOD).and(ElementMatchers.takesArguments(3)))
                .isEmpty()) {
            throw new IllegalStateException(
                    "CombatManagerBallisticsNullGuardPatch: CombatManager no longer declares the"
                            + " 3-arg isHittableBallisticsTarget overload — the hook would silently"
                            + " no-op and reintroduce the null-controller NPE latch. Re-verify"
                            + " the patch against the current game source.");
        }
        return builder.visit(
                Advice.to(typePool.describe(ADVICE).resolve(), locator)
                        .on(ElementMatchers.named(METHOD).and(ElementMatchers.takesArguments(3))));
    }
}
