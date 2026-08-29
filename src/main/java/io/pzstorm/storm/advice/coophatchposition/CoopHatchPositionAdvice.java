package io.pzstorm.storm.advice.coophatchposition;

import io.pzstorm.storm.patch.fixes.CoopHatchPositionFix;
import net.bytebuddy.asm.Advice;

/**
 * Inlined before the body of {@code zombie.iso.objects.IsoHutch.addAnimalInside(IsoAnimal,
 * boolean)}. Hands the hutch and the incoming animal to {@link
 * CoopHatchPositionFix#ensurePosition(Object, Object)} so an origin-positioned animal (a chick
 * hatched by the broken {@code Food.checkEggHatch(IsoHutch)} hutch branch) is re-homed to the
 * hutch's coordinates before it is stored. See {@link
 * io.pzstorm.storm.patch.fixes.CoopHatchPositionFixPatch} for the rationale.
 *
 * <p>{@code @Advice.This} and {@code @Advice.Argument} are typed {@code Object} (not {@code
 * IsoHutch}/{@code IsoAnimal}) so the inlined call site in the patched class does not encode
 * checkcasts against game classes. A typed parameter would let javac elide the cast and the JVM
 * verifier would resolve the classes at patch registration — before the transformer is in place to
 * apply itself. See the {@code feedback_elided_cast_load} memory.
 */
public class CoopHatchPositionAdvice {

    @Advice.OnMethodEnter
    public static void onEnter(@Advice.This Object hutch, @Advice.Argument(0) Object animal) {
        CoopHatchPositionFix.ensurePosition(hutch, animal);
    }
}
