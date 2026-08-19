package io.pzstorm.storm.advice.isoanimalregistry;

import io.pzstorm.storm.patch.fixes.IsoAnimalMapInvariant;
import net.bytebuddy.asm.Advice;

/**
 * Inlined after the body of {@code zombie.characters.animals.IsoAnimal.update()}. Hands {@code
 * this} off to {@link IsoAnimalMapInvariant#ensureMapEntry(Object)} for invariant enforcement. See
 * {@link io.pzstorm.storm.patch.fixes.IsoAnimalRegistryFixPatch} for the rationale.
 *
 * <p>{@code @Advice.This} is typed {@code Object} (not {@code IsoAnimal}) so the inlined call site
 * in the patched class does not encode a checkcast against {@code IsoAnimal}. A typed parameter
 * would let javac elide the cast and the JVM verifier would resolve {@code IsoAnimal} at patch
 * registration — before the transformer is in place to apply itself. See the {@code
 * feedback_elided_cast_load} memory.
 *
 * <p>Default exit semantics: this advice only fires on normal return. If {@code update()} throws,
 * the map is left as-is — the next tick's exit will reconcile.
 */
public class IsoAnimalRegistryAdvice {

    @Advice.OnMethodExit
    public static void onExit(@Advice.This Object self) {
        IsoAnimalMapInvariant.ensureMapEntry(self);
    }
}
