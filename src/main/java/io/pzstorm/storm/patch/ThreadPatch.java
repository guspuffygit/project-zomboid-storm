package io.pzstorm.storm.patch;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Patches {@link java.lang.Thread#setDefaultUncaughtExceptionHandler} to prevent any code from
 * replacing the uncaught exception handler that Storm sets in {@code StormLauncher}.
 *
 * <p>The advice skips every call to {@code setDefaultUncaughtExceptionHandler} by returning from
 * {@code OnMethodEnter} with {@code skipOn = Advice.OnNonDefaultValue.class}. This ensures Storm's
 * handler (set early in startup) is never overwritten by game code.
 *
 * <p>Note: The advice code intentionally avoids references to Storm classes (e.g. StormLogger)
 * because it gets inlined into {@code java.lang.Thread}, which is loaded by the bootstrap
 * classloader. The bootstrap classloader cannot see application classes, so any such references
 * would cause {@link NoClassDefFoundError} (silently suppressed by {@code suppress =
 * Throwable.class}).
 */
public class ThreadPatch extends StormClassTransformer {

    public ThreadPatch() {
        super("java.lang.Thread");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(SetDefaultHandlerAdvice.class)
                        .on(
                                ElementMatchers.named("setDefaultUncaughtExceptionHandler")
                                        .and(ElementMatchers.takesArguments(1))));
    }

    public static class SetDefaultHandlerAdvice {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static boolean onEnter(@Advice.Argument(0) Thread.UncaughtExceptionHandler eh) {
            System.err.println("[Storm] Blocked setDefaultUncaughtExceptionHandler call");
            return true;
        }
    }
}
