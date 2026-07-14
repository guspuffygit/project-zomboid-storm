package io.pzstorm.storm.advice.isogamecharacterragdollmirrors;

import net.bytebuddy.asm.Advice;
import zombie.core.skinnedmodel.animation.AnimationPlayer;

/**
 * Advice for {@code IsoGameCharacter.isFullyRagdolling()}.
 *
 * <p>Mirror of the {@code isRagdoll} bypass. Vanilla body is {@code hasAnimationPlayer() &&
 * getAnimationPlayer().isFullyRagdolling()} — the {@code getAnimationPlayer()} call carries the
 * same expensive {@link zombie.core.skinnedmodel.ModelManager} lookup + pool-release side effect
 * documented on {@link
 * io.pzstorm.storm.advice.isogamecharacterisragdoll.IsoGameCharacterIsRagdollAdvice}. This query is
 * called from animation state checks and remote-player logic; removing the model-swap side effect
 * is invisible because every render/update path still triggers the swap independently.
 */
public class IsFullyRagdollingAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static int onEnter(@Advice.FieldValue("animPlayer") AnimationPlayer p) {
        if (p == null) {
            return 1;
        }
        return p.isFullyRagdolling() ? 2 : 1;
    }

    @Advice.OnMethodExit
    public static void onExit(
            @Advice.Enter int code, @Advice.Return(readOnly = false) boolean ret) {
        ret = code == 2;
    }
}
