package io.pzstorm.storm.patch.fixes;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Keeps a malformed vanilla log format string from throwing out of the logger and into game code.
 * See {@code io.pzstorm.storm.advice.debuglogformat.DebugLogStreamFormatAdvice}.
 *
 * <p>Registered for both JVMs: the known bad call site ({@code
 * GameEntityManager.checkEntityIDChange}) runs on chunk unload on the client as well as the server,
 * and nothing in Lua can catch a Java {@code String.format} throw inside the engine's logger.
 *
 * <p>Re-validate on game update: {@code DebugLogStream} must still funnel every formatted log line
 * through a private static {@code getFormattedOutputStr(Object, Object[])} (DebugLogStream.java:114
 * in 42.20.4).
 */
public class DebugLogStreamFormatPatch extends StormClassTransformer {

    private static final String TARGET = "zombie.debug.DebugLogStream";
    private static final String METHOD = "getFormattedOutputStr";
    private static final String ADVICE =
            "io.pzstorm.storm.advice.debuglogformat.DebugLogStreamFormatAdvice";

    public DebugLogStreamFormatPatch() {
        super(TARGET);
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        if (typePool.describe(TARGET).resolve().getDeclaredMethods().filter(matcher()).isEmpty()) {
            throw new IllegalStateException(
                    "DebugLogStreamFormatPatch: DebugLogStream no longer declares "
                            + METHOD
                            + "(Object, Object[]) — a bad vanilla format string would throw out"
                            + " of the logger again. Re-verify against the current game source.");
        }
        return builder.visit(Advice.to(typePool.describe(ADVICE).resolve(), locator).on(matcher()));
    }

    private static net.bytebuddy.matcher.ElementMatcher.Junction<
                    net.bytebuddy.description.method.MethodDescription>
            matcher() {
        return ElementMatchers.named(METHOD)
                .and(ElementMatchers.takesArguments(Object.class, Object[].class));
    }
}
