package io.pzstorm.storm.advice.actionstate;

import io.pzstorm.storm.patch.fixes.ActionStateDebugLog;
import net.bytebuddy.asm.Advice;
import zombie.characters.action.ActionState;
import zombie.characters.action.ActionStateContainer;

/** Advice for {@code ActionStateContainer.setCurrentState(ActionState, ActionTransition)}. */
public class SetCurrentStateAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
            @Advice.This ActionStateContainer self, @Advice.Argument(0) ActionState nextState) {
        ActionStateDebugLog.logSetCurrentState(self, nextState);
    }
}
