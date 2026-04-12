package io.pzstorm.storm.advice.actionstate;

import io.pzstorm.storm.patch.fixes.ActionStateDebugLog;
import net.bytebuddy.asm.Advice;
import zombie.characters.action.ActionContext;
import zombie.characters.action.ActionState;
import zombie.characters.action.ActionStateContainer;

/**
 * Advice for {@code ActionStateContainer.tryInsertChildState(ActionContext, ActionState)}. Uses
 * actual game types — the typePool/locator pattern avoids the {@link ClassCircularityError} that
 * previously required {@code Object}.
 */
public class TryInsertChildStateAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static boolean onEnter(
            @Advice.This ActionStateContainer self,
            @Advice.Argument(0) ActionContext actionContext,
            @Advice.Argument(1) ActionState nextState) {
        boolean didSet = ActionStateDebugLog.enterContext(actionContext);
        ActionStateDebugLog.logTryInsertEnter(self, actionContext, nextState);
        return didSet;
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
            @Advice.This ActionStateContainer self,
            @Advice.Argument(0) ActionContext actionContext,
            @Advice.Argument(1) ActionState nextState,
            @Advice.Return boolean result,
            @Advice.Enter boolean didSet) {
        ActionStateDebugLog.logTryInsertExit(self, actionContext, nextState, result);
        ActionStateDebugLog.exitContext(didSet);
    }
}
