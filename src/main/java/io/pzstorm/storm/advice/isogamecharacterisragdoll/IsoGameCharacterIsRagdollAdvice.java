package io.pzstorm.storm.advice.isogamecharacterisragdoll;

import net.bytebuddy.asm.Advice;
import zombie.core.skinnedmodel.animation.AnimationPlayer;

/**
 * Advice for {@code IsoGameCharacter.isRagdoll()}.
 *
 * <p>Vanilla body is {@code return this.hasAnimationPlayer() &&
 * this.getAnimationPlayer().isRagdolling();} — cheap in intent, expensive in practice. {@code
 * getAnimationPlayer()} looks up the current body {@link zombie.core.skinnedmodel.model.Model} via
 * {@code ModelManager.instance.getBodyModel(this)}, compares against the cached player's model, and
 * on mismatch releases the player to a pool and allocates a fresh one. That entire model-swap dance
 * runs for every {@code isRagdoll} call — of which there are thousands per frame across zombies +
 * players + AI state checks.
 *
 * <p>Live sampling attributed 1.17% of MainThread leaf self-time to {@code isRagdoll}, essentially
 * all of it inside {@code getAnimationPlayer}. This advice reads the {@code animPlayer} field
 * directly and calls {@code isRagdolling()} on it, bypassing the model-swap side effect entirely.
 *
 * <p>The model-swap is preserved because every other caller of {@code getAnimationPlayer} (render,
 * update, animation dispatch) still triggers it — {@code isRagdoll} is a query, not a driver of the
 * swap, so removing its side effect changes nothing observable. If the cached player is stale
 * (model changed but no other caller has visited yet), its {@code isRagdolling} flag is the answer
 * for that character until the next real caller runs.
 *
 * <p>Encoding: enter returns {@code 1} if false, {@code 2} if true — both non-zero, so {@link
 * Advice.OnNonDefaultValue} skips the vanilla body. Exit maps {@code 2} → true, {@code 1} → false.
 * We never fall through: this method's answer is fully derivable from the field.
 */
public class IsoGameCharacterIsRagdollAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static int onEnter(@Advice.FieldValue("animPlayer") AnimationPlayer p) {
        if (p == null) {
            return 1;
        }
        return p.isRagdolling() ? 2 : 1;
    }

    @Advice.OnMethodExit
    public static void onExit(
            @Advice.Enter int code, @Advice.Return(readOnly = false) boolean ret) {
        ret = code == 2;
    }
}
