package io.pzstorm.storm.patch.mod;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.core.StormClassTransformer;
import java.util.List;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.MemberSubstitution;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/** Patches {@link zombie.network.CoopMaster} */
public class CoopMasterPatch extends StormClassTransformer {

    public CoopMasterPatch() {
        super("zombie.network.CoopMaster");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {

        try {
            return builder.visit(
                            Advice.to(StoreMessageAdvice.class)
                                    .on(
                                            ElementMatchers.named("storeMessage")
                                                    .and(
                                                            ElementMatchers.takesArguments(
                                                                    String.class))))
                    .visit(
                            MemberSubstitution.relaxed()
                                    .constructor(
                                            ElementMatchers.isDeclaredBy(ProcessBuilder.class)
                                                    .and(
                                                            ElementMatchers.takesArguments(
                                                                    List.class)))
                                    .replaceWith(
                                            ProcessBuilderInterceptor.class.getDeclaredMethod(
                                                    "interceptProcessBuilder", List.class))
                                    .on(
                                            ElementMatchers.named("launchServer")
                                                    .and(
                                                            ElementMatchers.takesArguments(
                                                                    String.class,
                                                                    String.class,
                                                                    int.class,
                                                                    boolean.class))));
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("Failed to setup MemberSubstitution for CoopMaster", e);
        }
    }

    /**
     * Interceptor class that receives the arguments meant for the ProcessBuilder {@link
     * io.pzstorm.storm.StormBootstrapper}
     */
    public static class ProcessBuilderInterceptor {

        public static ProcessBuilder interceptProcessBuilder(List<String> command) {
            //            int classIndex = command.indexOf("zombie.network.GameServer");
            //            if (classIndex != -1) {
            //                command.set(classIndex, "io.pzstorm.storm.StormBootstrapper");
            //                command.add(classIndex, "-Dstorm.server=true");
            //                command.add(classIndex, "-DSTORM_LOG_SOURCE=SERVER");
            //                if ("local".equals(System.getProperty("stormType"))) {
            //                    command.add(classIndex, "-DstormType=local");
            //                    command.add(classIndex, "-DLOG_LEVEL=DEBUG");
            //                }
            //            }

            LOGGER.debug("Modified server args: {}", String.join(" ", command));

            return new ProcessBuilder(command);
        }
    }

    /** Advice class to intercept CoopMaster.storeMessage(String) */
    public static class StoreMessageAdvice {

        @Advice.OnMethodEnter
        public static void onEnter(@Advice.Argument(0) String message) {
            LOGGER.debug("SERVER: {}", message);
        }
    }
}
