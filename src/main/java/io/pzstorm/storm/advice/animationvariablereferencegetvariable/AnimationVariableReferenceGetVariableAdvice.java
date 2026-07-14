package io.pzstorm.storm.advice.animationvariablereferencegetvariable;

import net.bytebuddy.asm.Advice;
import zombie.core.skinnedmodel.advancedanimation.AnimationVariableHandle;
import zombie.core.skinnedmodel.advancedanimation.AnimationVariableSlotBool;
import zombie.core.skinnedmodel.advancedanimation.IAnimationVariableSlot;
import zombie.core.skinnedmodel.advancedanimation.IAnimationVariableSource;

/**
 * Advice for {@code AnimationVariableReference.getVariable(IAnimationVariableSource)}.
 *
 * <p>Vanilla's per-call cost breakdown (live-client sampling attributed 1.59% of MainThread leaf
 * self-time to this method): (1) {@code this.getName().isBlank()} — {@link String#isBlank()} walks
 * the whole variable name every call, ~10-20 codepoints of {@code Character.isWhitespace} per
 * lookup across thousands of animation-variable resolutions per frame; (2) redundant field loads on
 * subsequent lookups; (3) a virtual call to {@code getAnimationVariableSource}.
 *
 * <p>Names never change after {@code parse()} completes at construction, so the {@code isBlank}
 * verdict is a permanent per-instance property. This advice caches "confirmed not blank" in a
 * {@code boolean storm$notBlank} field defined on the class by the patch, and computes the entire
 * lookup inline on the fast path — allocating the handle once, resolving the source, and calling
 * {@code src.getVariable(handle)} directly.
 *
 * <p>The sentinel {@link #NULL_SLOT} distinguishes "advice computed a real null result" (skip
 * vanilla, return null) from "advice hit an edge case, fall through to vanilla" (return null from
 * enter). The two edge cases we fall through on:
 *
 * <ul>
 *   <li>{@code name.isBlank()} — rare, avoids poisoning the cache
 *   <li>{@code subVariableSourceName != null} and lookup returns null — vanilla emits a {@code
 *       warnOnce}; we let it, at the cost of one duplicate call in that rare case
 * </ul>
 *
 * <p>All other paths skip the vanilla body entirely: the enter value flows into exit as
 * {@code @Advice.Enter}, exit un-sentinels it into the actual return.
 */
public class AnimationVariableReferenceGetVariableAdvice {

    public static final IAnimationVariableSlot NULL_SLOT =
            new AnimationVariableSlotBool("storm$null", null);

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static IAnimationVariableSlot onEnter(
            @Advice.Argument(0) IAnimationVariableSource varSource,
            @Advice.FieldValue("name") String name,
            @Advice.FieldValue("subVariableSourceName") String subVariableSourceName,
            @Advice.FieldValue(value = "variableHandle", readOnly = false)
                    AnimationVariableHandle handle,
            @Advice.FieldValue(value = "storm$notBlank", readOnly = false) boolean notBlank) {
        if (!notBlank) {
            if (name == null || name.isBlank()) {
                return null;
            }
            notBlank = true;
        }
        if (handle == null) {
            handle = AnimationVariableHandle.alloc(name);
        }
        IAnimationVariableSource src;
        if (subVariableSourceName != null) {
            src = varSource.getSubVariableSource(subVariableSourceName);
            if (src == null) {
                return null;
            }
        } else {
            src = varSource;
        }
        IAnimationVariableSlot slot = src.getVariable(handle);
        return slot == null ? NULL_SLOT : slot;
    }

    @Advice.OnMethodExit
    public static void onExit(
            @Advice.Enter IAnimationVariableSlot enterVal,
            @Advice.Return(readOnly = false) IAnimationVariableSlot ret) {
        if (enterVal != null) {
            ret = enterVal == NULL_SLOT ? null : enterVal;
        }
    }
}
