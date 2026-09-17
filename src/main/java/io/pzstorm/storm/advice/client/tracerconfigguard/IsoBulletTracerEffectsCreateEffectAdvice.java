package io.pzstorm.storm.advice.client.tracerconfigguard;

import net.bytebuddy.asm.Advice;

/**
 * Advice for {@code IsoBulletTracerEffects.createEffect(IsoGameCharacter)} that makes sure the
 * shooter's ammo type has a config entry before the vanilla body dereferences one.
 *
 * <p>The whole check lives in {@link BulletTracerConfigGuard}; a tracer is created once per shot,
 * so the static call costs nothing measurable. The advice skips the vanilla body when the guard
 * could not name an ammo type (the method then returns null, which every caller handles), and runs
 * it when the guard found or seeded the entry. Both parameters are typed {@code Object} so the
 * inlined bytecode never references a type that is still being defined. {@code suppress} resolves
 * any advice failure to "run vanilla".
 */
public class IsoBulletTracerEffectsCreateEffectAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class, suppress = Throwable.class)
    public static boolean onEnter(@Advice.This Object self, @Advice.Argument(0) Object character) {
        return BulletTracerConfigGuard.onCreateEffect(self, character);
    }
}
