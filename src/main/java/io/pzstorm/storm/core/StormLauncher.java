package io.pzstorm.storm.core;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.logging.StormLogger;
import io.pzstorm.storm.util.StormEnv;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.implementation.FixedValue;
import net.bytebuddy.matcher.ElementMatchers;
import org.jetbrains.annotations.Nullable;

public class StormLauncher {

    public static final String CLIENT_ENTRY_POINT_CLASS = "zombie.gameStates.MainScreenState";
    public static final String SERVER_ENTRY_POINT_CLASS = "zombie.network.GameServer";

    /**
     * Name of the method that is the entry point to Project Zomboid execution. This will be invoked
     * through reflection from {@link #main(String[])} to launch the game
     */
    private static final String ZOMBOID_ENTRY_POINT = "main";

    /** Calls {@link io.pzstorm.storm.event.core.StormEventHandler} * */
    public static void main(String[] args) {
        try {
            LOGGER.info("Storm version: {}", StormVersion.getVersion());
            LOGGER.info("Preparing to launch Project Zomboid...");

            verifyByteBuddy();

            StormClassLoader classLoader = StormBootstrap.CLASS_LOADER;

            Class.forName("io.pzstorm.storm.core.StormClassTransformers", true, classLoader);
            Class.forName("io.pzstorm.storm.logging.ZomboidLogger", true, classLoader);

            // Set Storm's uncaught exception handler BEFORE applying agent transformers,
            // since ThreadPatch will block all subsequent setDefaultUncaughtExceptionHandler calls.
            Thread.setDefaultUncaughtExceptionHandler(
                    new StormLogger.Log4JUncaughtExceptionHandler());

            // Retrieve the Instrumentation instance stored by StormBootstrapper.premain()
            // via reflection (bootstrap and core are in different classloaders).
            Instrumentation instrumentation = getInstrumentation();
            if (instrumentation != null) {
                StormClassTransformers.applyAgentTransformers(instrumentation);
            } else {
                LOGGER.warn(
                        "Instrumentation not available, agent-based transformers will not be applied");
            }

            StormBootstrap.loadAndRegisterMods();

            classLoader.loadClass("io.pzstorm.storm.event.core.LuaEventFactory");

            Class<?> eventHandler =
                    classLoader.loadClass("io.pzstorm.storm.event.core.StormEventHandler");
            Class<?> eventDispatcher =
                    classLoader.loadClass("io.pzstorm.storm.event.core.StormEventDispatcher");
            eventDispatcher
                    .getDeclaredMethod("registerEventHandler", Class.class)
                    .invoke(null, eventHandler);

            Class<?> transferHandler =
                    classLoader.loadClass("io.pzstorm.storm.transfer.StormTransferHandler");
            eventDispatcher
                    .getDeclaredMethod("registerEventHandler", Class.class)
                    .invoke(null, transferHandler);

            LOGGER.debug("Preparing to launch Entry Point: {}", getEntryPointClass());

            Class<?> entryPointClass = classLoader.loadClass(getEntryPointClass());
            Method entryPoint = entryPointClass.getMethod(ZOMBOID_ENTRY_POINT, String[].class);

            /* we invoke the entry point using reflection because we don't want to reference
             the entry point class which would to the class being loaded by application class loader
            */
            LOGGER.debug("Launching Project Zomboid...");
            entryPoint.invoke(null, (Object) args);
        } catch (Throwable e) {
            LOGGER.error("An unhandled exception occurred while running Project Zomboid", e);
            throw new RuntimeException(e);
        }
    }

    public static String getEntryPointClass() {
        if (StormEnv.isStormServer()) {
            return SERVER_ENTRY_POINT_CLASS;
        }

        return CLIENT_ENTRY_POINT_CLASS;
    }

    /**
     * Retrieves the {@link Instrumentation} instance stored by {@code StormBootstrapper.premain()}
     * via reflection, since the bootstrap jar is loaded by a different classloader.
     */
    private static @Nullable Instrumentation getInstrumentation() {
        try {
            Class<?> bootstrapperClass =
                    Class.forName(
                            "io.pzstorm.storm.StormBootstrapper",
                            false,
                            ClassLoader.getSystemClassLoader());
            return (Instrumentation)
                    bootstrapperClass.getDeclaredField("instrumentation").get(null);
        } catch (Exception e) {
            LOGGER.warn("Failed to retrieve Instrumentation from StormBootstrapper", e);
            return null;
        }
    }

    private static void verifyByteBuddy()
            throws NoSuchMethodException,
                    InvocationTargetException,
                    InstantiationException,
                    IllegalAccessException {
        Class<?> dynamicType =
                new ByteBuddy()
                        .subclass(Object.class)
                        .method(ElementMatchers.named("toString"))
                        .intercept(FixedValue.value("Hello from ByteBuddy!"))
                        .make()
                        .load(
                                StormLauncher.class.getClassLoader(),
                                ClassLoadingStrategy.Default.WRAPPER)
                        .getLoaded();

        Object instance = dynamicType.getDeclaredConstructor().newInstance();

        LOGGER.debug(instance.toString());
    }
}
