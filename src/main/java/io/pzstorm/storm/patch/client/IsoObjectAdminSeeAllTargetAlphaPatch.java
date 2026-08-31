package io.pzstorm.storm.patch.client;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Client-only. Keeps remote players visible to admins with "can see all" enabled by dropping the
 * {@code targetAlpha = 0} writes that {@code IsoPlayer.updateLOS} issues for occluded remotes
 * within 20 tiles. Vanilla {@code IsoPlayer.render} already forces targetAlpha to 1 for such
 * admins, so the two writers fight and the outcome depends on the remote's position in the cell
 * object list relative to the local player: players after it render at alpha 0 with a floating name
 * tag.
 *
 * <p>Why a client bytecode patch: the zero write sits inside compiled {@code updateLOS} with no Lua
 * or event surface between the occlusion test and the write, and the vanilla bypass ({@code
 * isSeeEveryone}) is a Core.debug-only option that also x-rays zombies. Advising the one 2-arg
 * {@code IsoObject.setTargetAlpha} overload (no subclass overrides it) is the least invasive seam.
 * Fail-soft: the advice suppresses its own exceptions and the gate answers {@code false} for
 * anything that is not a remote {@code IsoPlayer} seen by a non-"None" access-level local player
 * with {@code canSeeAll()}. Re-validate on game updates: assumes the {@code (int, float)} overload
 * remains the single write path (checked against 42.20.4).
 */
public class IsoObjectAdminSeeAllTargetAlphaPatch extends StormClassTransformer {

    private static final String ADVICE =
            "io.pzstorm.storm.advice.client.adminseeall.AdminSeeAllTargetAlphaAdvice";

    public IsoObjectAdminSeeAllTargetAlphaPatch() {
        super("zombie.iso.IsoObject");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(ADVICE).resolve(), locator)
                        .on(
                                ElementMatchers.named("setTargetAlpha")
                                        .and(
                                                ElementMatchers.takesArguments(
                                                        int.class, float.class))));
    }
}
