package io.pzstorm.storm.advice.thumpableupdate;

import net.bytebuddy.asm.Advice;
import zombie.iso.objects.IsoThumpable;
import zombie.network.GameServer;

/**
 * Skips the body of {@code IsoThumpable.update()} on a dedicated server for objects with no
 * fuel-driven life ({@code getLifeLeft() <= -1}, the constructor default). On the server the whole
 * body is observably dead for them: the light-source section is inside {@code if
 * (!GameServer.server)} and the only remaining branch is gated on {@code getLifeLeft() > -1.0F} —
 * yet vanilla still pays {@code getObjectIndex()} (a linear {@code PZArrayList.indexOf} over the
 * square's objects through a comparator lambda) before concluding there is nothing to do. See
 * {@code IsoThumpableUpdateSkipPatch} for the numbers and the re-validation contract.
 */
public class IsoThumpableUpdateAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter(@Advice.This Object self) {
        return GameServer.server && ((IsoThumpable) self).getLifeLeft() <= -1.0F;
    }
}
