package io.pzstorm.storm.patch.debugging;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.core.StormClassTransformer;
import java.io.IOException;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/** Patches {@link java.lang.ProcessBuilder} */
public class ProcessBuilderPatch extends StormClassTransformer {

    public ProcessBuilderPatch() {
        super("java.lang.ProcessBuilder");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(ProcessStartAdvice.class)
                        .on(ElementMatchers.named("start").and(ElementMatchers.takesArguments(0))));
    }

    public static class ProcessStartAdvice {
        @Advice.OnMethodExit(suppress = Throwable.class)
        public static void onExit(
                @Advice.This ProcessBuilder builder,
                @Advice.Return Process process,
                @Advice.Thrown Throwable thrown) {

            if (thrown != null || process == null) {
                LOGGER.error("Process failed to start.", thrown);
                return;
            }

            LOGGER.debug(
                    "Process started successfully! Command: {}",
                    String.join(" ", builder.command()));

            Thread.ofVirtual()
                    .start(
                            () -> {
                                try (java.io.BufferedReader reader =
                                        new java.io.BufferedReader(
                                                new java.io.InputStreamReader(
                                                        process.getErrorStream()))) {
                                    String line;
                                    while ((line = reader.readLine()) != null) {
                                        LOGGER.error("[SERVER] {}", line);
                                    }
                                } catch (IOException e) {
                                    LOGGER.error("Failed to read server error stream", e);
                                }
                            });
        }
    }
}
