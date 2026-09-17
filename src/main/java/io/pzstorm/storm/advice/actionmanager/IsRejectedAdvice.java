package io.pzstorm.storm.advice.actionmanager;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import net.bytebuddy.asm.Advice;

/** Advice for {@code ActionManager.isRejected(byte)}. Same shape as {@link IsDoneAdvice}. */
public class IsRejectedAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class, suppress = Throwable.class)
    public static int onEnter(@Advice.Argument(0) byte id) {
        try {
            return ActionStateQuery.isRejected(id) ? 2 : 1;
        } catch (Throwable t) {
            LOGGER.error("ActionStateQuery.isRejected failed, falling back to vanilla", t);
            return 0;
        }
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
            @Advice.Enter int outcome, @Advice.Return(readOnly = false) boolean result) {
        if (outcome != 0) {
            result = outcome == 2;
        }
    }
}
