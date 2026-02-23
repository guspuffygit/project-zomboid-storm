package io.pzstorm.storm.patch.debugging;

import io.pzstorm.storm.core.StormClassTransformer;
import io.pzstorm.storm.logging.ZomboidLogger;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;
import zombie.debug.LogSeverity;

public class DebugLogPatch extends StormClassTransformer {

    public DebugLogPatch() {
        super("zombie.debug.DebugLog");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                        Advice.to(EchoToLogFilesAdvice.class)
                                .on(
                                        ElementMatchers.named("echoToLogFiles")
                                                .and(
                                                        ElementMatchers.takesArgument(
                                                                0,
                                                                ElementMatchers.named(
                                                                        "zombie.debug.LogSeverity")))
                                                .and(
                                                        ElementMatchers.takesArgument(
                                                                1, String.class))))
                .visit(
                        Advice.to(EchoExceptionLineAdvice.class)
                                .on(
                                        ElementMatchers.named("echoExceptionLineToLogFiles")
                                                .and(
                                                        ElementMatchers.takesArgument(
                                                                0,
                                                                ElementMatchers.named(
                                                                        "zombie.debug.LogSeverity")))
                                                .and(ElementMatchers.takesArgument(1, String.class))
                                                .and(
                                                        ElementMatchers.takesArgument(
                                                                2, String.class))));
    }

    public static class EchoToLogFilesAdvice {
        @Advice.OnMethodEnter
        public static void onEchoToLogFiles(
                @Advice.Argument(0) LogSeverity logSeverity, @Advice.Argument(1) String outString) {
            ZomboidLogger.log(logSeverity, outString);
        }
    }

    public static class EchoExceptionLineAdvice {
        @Advice.OnMethodEnter
        public static void onEchoExceptionLine(
                @Advice.Argument(0) LogSeverity logSeverity, @Advice.Argument(2) String outString) {
            ZomboidLogger.log(logSeverity, outString);
        }
    }
}
