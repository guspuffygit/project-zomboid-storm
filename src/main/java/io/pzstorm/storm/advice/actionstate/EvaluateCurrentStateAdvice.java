package io.pzstorm.storm.advice.actionstate;

import io.pzstorm.storm.patch.fixes.ActionStateDebugLog;
import net.bytebuddy.asm.Advice;
import zombie.characters.action.ActionContext;

/**
 * Advice for {@code ActionStateContainer.evaluateCurrentState(ActionContext)}. Sets up the Steam ID
 * filtering context so that nested calls to {@code setCurrentState} and {@code removeChildStateAt}
 * are only logged for allowed players.
 */
public class EvaluateCurrentStateAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static boolean onEnter(@Advice.Argument(0) ActionContext actionContext) {
        return ActionStateDebugLog.enterContext(actionContext);
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.Enter boolean didSet) {
        ActionStateDebugLog.exitContext(didSet);
    }
}
