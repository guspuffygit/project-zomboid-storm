package io.pzstorm.storm.advice.actioncondition;

import io.pzstorm.storm.animation.StormCharacterVariableParse;
import io.pzstorm.storm.entity.StormVariableLookup;
import net.bytebuddy.asm.Advice;
import zombie.core.skinnedmodel.advancedanimation.AnimationVariableReference;
import zombie.core.skinnedmodel.advancedanimation.AnimationVariableType;
import zombie.core.skinnedmodel.advancedanimation.IAnimationVariableSlot;
import zombie.core.skinnedmodel.advancedanimation.IAnimationVariableSource;

/**
 * Advice for the private static {@code CharacterVariableCondition.resolveValue(Object,
 * IAnimationVariableSource)}: for a {@code CharacterVariableLookup} operand, reads the variable
 * slot's typed value instead of {@code getValueString()} followed by the vanilla string parser.
 *
 * <p>Exactness argument per slot type: Boolean slots stringify to {@code "true"}/{@code "false"}
 * and parse back to the same boxed Boolean, so {@code getValueBool()} is identical. Int slots
 * stringify with {@code Integer.toString} and parse back to the same value when non-negative; a
 * negative value hits the vanilla parser's sign-dropping quirk, so it takes the string path. Float
 * / String / enum / void slots keep the string path through {@link StormCharacterVariableParse}, a
 * verbatim port of {@code parseValue(value, false)} (the float parser is not {@code
 * Float.parseFloat}, so a typed float read would not round the same way). Non-lookup operands
 * (already parsed at load time) fall through to vanilla, which returns them as-is.
 *
 * <p>Measured share: 5% of player update was {@code CharacterVariableCondition.passes} (scan #10,
 * 2026-09-02); most of that is the slot lookup itself, which this keeps — the expected gain is the
 * stringify + re-parse for the Boolean-dominated {@code isTrue}/{@code isFalse} conditions.
 *
 * <p>Fail-soft: if the accessor patch did not apply, no operand is a {@link StormVariableLookup}
 * and vanilla runs untouched.
 */
public class CharacterVariableResolveAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static int onEnter(
            @Advice.Argument(0) Object value,
            @Advice.Argument(1) IAnimationVariableSource owner,
            @Advice.Local("stormResolved") Object resolved) {
        if (!(value instanceof StormVariableLookup)) {
            return 0;
        }
        AnimationVariableReference reference =
                (AnimationVariableReference)
                        ((StormVariableLookup) value).getStormVariableReference();
        IAnimationVariableSlot slot = reference.getVariable(owner);
        if (slot == null) {
            resolved = null;
            return 1;
        }
        AnimationVariableType type = slot.getType();
        if (type == AnimationVariableType.Boolean) {
            resolved = slot.getValueBool() ? Boolean.TRUE : Boolean.FALSE;
            return 1;
        }
        if (type == AnimationVariableType.Int) {
            int intValue = slot.getValueInt();
            if (intValue >= 0) {
                resolved = Integer.valueOf(intValue);
                return 1;
            }
        }
        String text = slot.getValueString();
        resolved = text == null ? null : StormCharacterVariableParse.parseValue(text);
        return 1;
    }

    @Advice.OnMethodExit
    public static void onExit(
            @Advice.Enter int code,
            @Advice.Local("stormResolved") Object resolved,
            @Advice.Return(readOnly = false) Object ret) {
        if (code == 1) {
            ret = resolved;
        }
    }
}
