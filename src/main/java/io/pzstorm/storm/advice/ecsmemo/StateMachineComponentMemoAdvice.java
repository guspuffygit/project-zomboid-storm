package io.pzstorm.storm.advice.ecsmemo;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import zombie.characters.ecs.ECSComponent;

/**
 * Advice for {@code IsoGameCharacter.getStateMachineComponent()}: serves the component from the
 * {@code stormStateMachine} field added by {@code IsoGameCharacterStateMachineMemoPatch} instead of
 * going through {@code getECSComponent} → {@code tryGetECSComponent} → memo/{@code HashMap}.
 *
 * <p>The method is {@code final} and its component is registered once, in {@code
 * registerECSComponents}, so the field is filled on the first call and read directly afterwards. A
 * hit is validated exactly like the general ECS memo: {@code component.getECSOwnerEntity() == this}
 * holds iff the component is still registered on this entity ({@code removeECSComponent} nulls the
 * owner, {@code setECSComponent} re-stamps it), so a stale field can never be returned — it falls
 * through to the vanilla lookup, which refreshes it. The getter chain is the single hottest ECS
 * lookup (~9% of player update through {@code getActionContext}, {@code getAdvancedAnimator},
 * {@code getStateMachine}, {@code getStateMachineParams}; scan #10, 2026-09-02).
 *
 * <p>The field is read through {@link Advice.FieldValue} (the advice is inlined into {@code
 * IsoGameCharacter}); it is plain, not volatile — a racy cross-thread read sees {@code null} or the
 * published reference, both safe.
 */
public class StateMachineComponentMemoAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static int onEnter(
            @Advice.This Object self,
            @Advice.FieldValue("stormStateMachine") Object cached,
            @Advice.Local("stormHit") Object hit) {
        if (cached == null) {
            return 0;
        }
        if (((ECSComponent) cached).getECSOwnerEntity() != self) {
            return 0;
        }
        hit = cached;
        return 1;
    }

    @Advice.OnMethodExit
    public static void onExit(
            @Advice.Enter int code,
            @Advice.Local("stormHit") Object hit,
            @Advice.FieldValue(value = "stormStateMachine", readOnly = false) Object cached,
            @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object ret) {
        if (code == 1) {
            ret = hit;
            return;
        }
        if (ret != null) {
            cached = ret;
        }
    }
}
