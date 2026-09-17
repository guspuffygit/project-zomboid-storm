package io.pzstorm.storm.advice.nta;

import io.pzstorm.storm.patch.fixes.NetTimedActionParseFix;
import net.bytebuddy.asm.Advice;
import zombie.core.NetTimedAction;

/**
 * Advice for {@code NetTimedAction.parse(ByteBufferReader, IConnection)}. A {@code
 * RuntimeException} out of the body is handed to {@link NetTimedActionParseFix#onParseFailed}; when
 * that returns {@code true} the exception is swallowed and the packet continues to {@code
 * processServer} with a null action, which rejects it.
 */
public class ParseAdvice {

    @Advice.OnMethodExit(onThrowable = RuntimeException.class, suppress = Throwable.class)
    public static void onExit(
            @Advice.This NetTimedAction self, @Advice.Thrown(readOnly = false) Throwable thrown) {
        if (thrown != null && NetTimedActionParseFix.onParseFailed(self, thrown)) {
            thrown = null;
        }
    }
}
