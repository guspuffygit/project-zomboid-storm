package io.pzstorm.storm.patch.networking;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.core.StormClassTransformer;
import java.util.List;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.MemberSubstitution;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/** Patches {@link zombie.network.CoopMaster} to launch the server through Storm's bootstrap. */
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
     * Intercepts the {@code new ProcessBuilder(command)} call inside {@code
     * CoopMaster.launchServer} and adds a {@code -javaagent} flag pointing to Storm's bootstrap JAR
     * so that the coop server process is launched through Storm's bootstrap chain, just like the
     * normal dedicated server launch.
     */
    public static class ProcessBuilderInterceptor {

        private static final String LOCAL_BOOTSTRAP_PATH =
                "storm/Contents/mods/storm/bootstrap/storm-bootstrap.jar";

        public static ProcessBuilder interceptProcessBuilder(List<String> command) {
            int classIndex = command.indexOf("zombie.network.GameServer");
            if (classIndex != -1) {
                // Insert -javaagent and system properties before the main class.
                // Each add(classIndex, ...) inserts at the same position, pushing
                // previous insertions right, so we insert in reverse order.
                command.add(classIndex, "-Dstorm.server=true");
                command.add(classIndex, "-DSTORM_LOG_SOURCE=SERVER");
                if ("local".equals(System.getProperty("stormType"))) {
                    command.add(classIndex, "-DstormType=local");
                    command.add(classIndex, "-DLOG_LEVEL=DEBUG");
                }
                String bootstrapJar = resolveBootstrapJar();
                command.add(classIndex, "-javaagent:" + bootstrapJar);
            }

            LOGGER.debug("Modified coop server args: {}", String.join(" ", command));

            return new ProcessBuilder(command);
        }

        private static String resolveBootstrapJar() {
            if ("local".equals(System.getProperty("stormType"))) {
                return java.nio.file.Paths.get(
                                System.getProperty("user.home"),
                                "Zomboid",
                                "Workshop",
                                LOCAL_BOOTSTRAP_PATH)
                        .toString();
            }
            // When running from Steam Workshop, the bootstrap jar is next to the
            // Storm engine libs. Derive from the StormBootstrapper's own code source.
            try {
                java.net.URI jarUri =
                        Class.forName(
                                        "io.pzstorm.storm.StormBootstrapper",
                                        false,
                                        ClassLoader.getSystemClassLoader())
                                .getProtectionDomain()
                                .getCodeSource()
                                .getLocation()
                                .toURI();
                return java.nio.file.Paths.get(jarUri).toString();
            } catch (Exception e) {
                throw new RuntimeException(
                        "Failed to resolve storm-bootstrap.jar for coop server launch", e);
            }
        }
    }

    /** Logs messages received from the coop server process. */
    public static class StoreMessageAdvice {

        @Advice.OnMethodEnter
        public static void onEnter(@Advice.Argument(0) String message) {
            LOGGER.debug("COOP SERVER: {}", message);
        }
    }
}
