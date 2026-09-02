package io.pzstorm.storm.advice.isogamecharacterupdateemitter;

import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

/**
 * Advice for {@code IsoGameCharacter.updateEmitter()}: skips the body on the dedicated server when
 * {@code this} is an animal.
 *
 * <p>The body is {@code getFMODParameters().update()} followed by a positional {@code
 * emitter.tick()}. On the server the emitter is a {@code DummyCharacterSoundEmitter} ({@code
 * IsoGameCharacter} constructor, {@code !Core.soundDisabled && !GameServer.server}), so every
 * parameter value computed here is discarded. The parameters are not free to compute: {@code
 * ParameterFootstepMaterial2} calls {@code IsoGridSquare.getPuddlesInGround()}, whose cache is
 * bypassed on the server ({@code GameServer.server || ...}), so it recomputes for every animal
 * every tick — 85% of all {@code getPuddlesInGround} samples on ATF prod came from this path (scan
 * #7, 2026-08-30, 1.75% of main; still 1.7% of {@code IsoAnimal.update} at scan #8).
 *
 * <p>{@code IsoPlayer.updateInternal1} already guards its own call with {@code if
 * (!GameServer.server) updateEmitter()}; {@code IsoAnimal.update} calls it twice unguarded (before
 * {@code doDeferredMovement} and again inside {@code updateInternal}). Skipping at the callee
 * covers both sites. Scoped to animals via the {@code animal} flag set by the {@code IsoPlayer}
 * constructor {@code IsoAnimal} chains through; zombies and players are untouched.
 */
public class IsoGameCharacterUpdateEmitterAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter(@Advice.FieldValue("animal") boolean animal) {
        return animal && GameServer.server;
    }
}
