package io.pzstorm.storm.advice.client.ballisticsnullguard;

import net.bytebuddy.asm.Advice;

/**
 * Advice for {@code CombatManager.isHittableBallisticsTarget(BallisticsController, float, Vector3)}
 * that skips the body when the controller is {@code null}, so the method returns {@code false} (the
 * skipped return type's default) instead of throwing.
 *
 * <p>The only caller is the 4-arg overload, which ORs this result with {@code
 * isPointWithinDistance(ballisticsStartPosition, ballisticsEndPosition, ...)} — the same fallback
 * vanilla already uses for every target the reticle test rejects — so the frame degrades to the
 * cone test and the world keeps simulating. Hot path: one null test per candidate target per frame
 * while aiming; the helper class only loads on the null branch. {@code suppress} makes any advice
 * failure resolve to {@code false} — vanilla body runs exactly as today.
 */
public class CombatManagerBallisticsNullGuardAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class, suppress = Throwable.class)
    public static boolean onEnter(@Advice.Argument(0) Object ballisticsController) {
        if (ballisticsController != null) {
            return false;
        }
        BallisticsNullGuard.onNullController();
        return true;
    }
}
