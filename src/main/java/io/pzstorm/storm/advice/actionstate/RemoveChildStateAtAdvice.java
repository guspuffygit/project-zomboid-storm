package io.pzstorm.storm.advice.actionstate;

import io.pzstorm.storm.patch.fixes.ActionStateDebugLog;
import net.bytebuddy.asm.Advice;
import zombie.characters.action.ActionStateContainer;

/** Advice for {@code ActionStateContainer.removeChildStateAt(int)}. */
public class RemoveChildStateAtAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
            @Advice.This ActionStateContainer self, @Advice.Argument(0) int subStateIdx) {
        ActionStateDebugLog.logRemoveChildStateAt(self, subStateIdx);
    }
}
