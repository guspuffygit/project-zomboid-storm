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
            System.out.println("Storm version: " + StormVersion.getVersion());
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

            // Mod cataloging below gates workshop-folder mods on the server's enabled mod
            // list, which lives at <cachedir>/Server/<servername>.ini — both come from args.
            StormWorkshopModGate.captureGameArgs(args);

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

            Class<?> ramAllocTracker =
                    classLoader.loadClass("io.pzstorm.storm.diagnostics.RamAllocationTracker");
            eventDispatcher
                    .getDeclaredMethod("registerEventHandler", Class.class)
                    .invoke(null, ramAllocTracker);

            Class<?> screenshotReceiver =
                    classLoader.loadClass("io.pzstorm.storm.screenshot.StormScreenshotReceiver");
            eventDispatcher
                    .getDeclaredMethod("registerEventHandler", Class.class)
                    .invoke(null, screenshotReceiver);

            Class<?> perfSandboxApplier =
                    classLoader.loadClass(
                            "io.pzstorm.storm.sandbox.StormPerformanceSandboxApplier");
            eventDispatcher
                    .getDeclaredMethod("registerEventHandler", Class.class)
                    .invoke(null, perfSandboxApplier);

            Class<?> startupAnalytics =
                    classLoader.loadClass("io.pzstorm.storm.diagnostics.StormStartupAnalytics");
            eventDispatcher
                    .getDeclaredMethod("registerEventHandler", Class.class)
                    .invoke(null, startupAnalytics);

            // Game-port HTTP server (TCP on the game's UDP port, for game communication with
            // clients). The @SubscribeEvent on GamePortHttpServer starts it on OnServerStarted,
            // once GameServer.defaultPort is final.
            if (StormEnv.isStormServer()) {
                Class<?> gamePortHttpServer =
                        classLoader.loadClass("io.pzstorm.storm.http.GamePortHttpServer");
                eventDispatcher
                        .getDeclaredMethod("registerEventHandler", Class.class)
                        .invoke(null, gamePortHttpServer);

                Class<?> gamePortBuiltinEndpoints =
                        classLoader.loadClass("io.pzstorm.storm.http.GamePortBuiltinEndpoints");
                eventDispatcher
                        .getDeclaredMethod("registerEventHandler", Class.class)
                        .invoke(null, gamePortBuiltinEndpoints);

                Class<?> gamePortHandshakeEndpoints =
                        classLoader.loadClass("io.pzstorm.storm.http.GamePortHandshakeEndpoints");
                eventDispatcher
                        .getDeclaredMethod("registerEventHandler", Class.class)
                        .invoke(null, gamePortHandshakeEndpoints);

                Class<?> gamePortRequestDataEndpoints =
                        classLoader.loadClass("io.pzstorm.storm.http.GamePortRequestDataEndpoints");
                eventDispatcher
                        .getDeclaredMethod("registerEventHandler", Class.class)
                        .invoke(null, gamePortRequestDataEndpoints);

                Class<?> gamePortPlayerProfileEndpoints =
                        classLoader.loadClass(
                                "io.pzstorm.storm.http.GamePortPlayerProfileEndpoints");
                eventDispatcher
                        .getDeclaredMethod("registerEventHandler", Class.class)
                        .invoke(null, gamePortPlayerProfileEndpoints);

                Class<?> gamePortChunkEndpoints =
                        classLoader.loadClass("io.pzstorm.storm.http.GamePortChunkEndpoints");
                eventDispatcher
                        .getDeclaredMethod("registerEventHandler", Class.class)
                        .invoke(null, gamePortChunkEndpoints);
            }

            // Property name mirrors io.pzstorm.storm.client.LauncherAutoJoin.AUTOJOIN_FILE_PROPERTY
            if (!StormEnv.isStormServer() && System.getProperty("storm.autojoin.file") != null) {
                Class<?> launcherAutoJoin =
                        classLoader.loadClass("io.pzstorm.storm.client.LauncherAutoJoin");
                eventDispatcher
                        .getDeclaredMethod("registerEventHandler", Class.class)
                        .invoke(null, launcherAutoJoin);
            }

            if (!StormEnv.isStormServer()) {
                try {
                    classLoader
                            .loadClass("io.pzstorm.storm.client.ClientLoadingWatchdog")
                            .getDeclaredMethod("start")
                            .invoke(null);
                } catch (Throwable t) {
                    // diagnostics only — a broken watchdog must never take the client down
                    LOGGER.error("Failed to start client loading watchdog", t);
                }

                try {
                    classLoader
                            .loadClass("io.pzstorm.storm.client.StormTcpChannel")
                            .getDeclaredMethod("start")
                            .invoke(null);
                } catch (Throwable t) {
                    // a broken TCP channel just means UDP-only; never take the client down
                    LOGGER.error("Failed to start Storm TCP channel", t);
                }
            }

            // PZ starts its Prometheus HTTP server on this property alone, so without it the
            // client samples every frame into a registry nothing can ever scrape
            if (!StormEnv.isStormServer() && System.getProperty("prometheusPort") != null) {
                try {
                    // initialize = true so the ~20 Prometheus registrations happen here, inside
                    // this catch. Left to run lazily they fire on the first event dispatch — on a
                    // game thread — as an ExceptionInInitializerError, which is neither a
                    // RuntimeException nor a ReflectiveOperationException and so routes around
                    // every guard between here and the frame.
                    Class<?> clientChunkMetrics =
                            Class.forName(
                                    "io.pzstorm.storm.client.ClientChunkStreamMetrics",
                                    true,
                                    classLoader);
                    eventDispatcher
                            .getDeclaredMethod("registerEventHandler", Class.class)
                            .invoke(null, clientChunkMetrics);
                } catch (Throwable t) {
                    LOGGER.error("Failed to register client chunk stream metrics", t);
                }
            }

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
