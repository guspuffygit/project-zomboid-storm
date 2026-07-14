package io.pzstorm.storm.advice.isogamecharacterragdollmirrors;

import net.bytebuddy.asm.Advice;
import zombie.characters.IsoGameCharacter;
import zombie.core.skinnedmodel.animation.AnimationPlayer;

/**
 * Advice for {@code IsoGameCharacter.isRagdollSimulationActive()}.
 *
 * <p>Vanilla body is {@code hasAnimationPlayer() &&
 * getAnimationPlayer().isRagdollSimulationActive() && canRagdoll()}. Bypasses the {@code
 * getAnimationPlayer()} model-swap side effect (see {@link
 * io.pzstorm.storm.advice.isogamecharacterisragdoll.IsoGameCharacterIsRagdollAdvice}) and preserves
 * the {@code canRagdoll()} gate by invoking it on {@code this} directly.
 */
public class IsRagdollSimulationActiveAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static int onEnter(
            @Advice.This IsoGameCharacter self,
            @Advice.FieldValue("animPlayer") AnimationPlayer p) {
        if (p == null || !p.isRagdollSimulationActive() || !self.canRagdoll()) {
            return 1;
        }
        return 2;
    }

    @Advice.OnMethodExit
    public static void onExit(
            @Advice.Enter int code, @Advice.Return(readOnly = false) boolean ret) {
        ret = code == 2;
    }
}
