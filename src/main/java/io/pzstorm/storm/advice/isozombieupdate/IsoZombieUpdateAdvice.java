package io.pzstorm.storm.advice.isozombieupdate;

import io.pzstorm.storm.patch.fixes.IsoZombieMapInvariant;
import net.bytebuddy.asm.Advice;

/**
 * Inlined after the body of {@code zombie.characters.IsoZombie.update()}. Hands {@code this} off to
 * {@link IsoZombieMapInvariant#ensureMapEntry(Object)} for invariant enforcement. See {@link
 * io.pzstorm.storm.patch.fixes.IsoZombieUpdateFixPatch} for the rationale.
 *
 * <p>{@code @Advice.This} is typed {@code Object} (not {@code IsoZombie}) so the inlined call site
 * in the patched class does not encode a checkcast against {@code IsoZombie}. A typed parameter
 * would let javac elide the cast and the JVM verifier would resolve {@code IsoZombie} at patch
 * registration — before the transformer is in place to apply itself. See the {@code
 * feedback_elided_cast_load} memory.
 *
 * <p>Default exit semantics: this advice only fires on normal return. If {@code update()} throws,
 * the map is left as-is — the next tick's exit will reconcile.
 */
public class IsoZombieUpdateAdvice {

    @Advice.OnMethodExit
    public static void onExit(@Advice.This Object self) {
        IsoZombieMapInvariant.ensureMapEntry(self);
    }
}
