package io.pzstorm.storm.core;

import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.util.Set;

/**
 * This class bootstraps everything needed to launch the game with static initialization. It should
 * be loaded from {@link StormLauncher} before Storm attempts to launch the game.
 */
@SuppressWarnings("WeakerAccess")
public class StormBootstrap {

    /**
     * {@code ClassLoader} used to transform and load all needed classes. This includes both Project
     * Zomboid and mod classes. Because class loaders maintain their own set of class instances and
     * native libraries this loader should always be used to load classes that access or modify
     * transformed class fields or methods.
     */
    public static final StormClassLoader CLASS_LOADER = new StormClassLoader();

    /** Loaded and initialized {@link StormModLoader} {@code Class}. */
    public static final Class<?> MOD_LOADER_CLASS;

    /**
     * Loaded and initialized {@link StormClassTransformers} {@code Class}. To transform specific
     * classes during load time (<i>on-fly</i>) {@link StormClassLoader} has to read and invoke
     * registered transformers. Due to how class loading works in Java references to classes within
     * {@code ClassLoader} do not get loaded by that specific {@code ClassLoader} but get delegate
     * to {@code AppClassLoader}. For this reason we have to use bootstrapping and reflection to
     * access transformers from {@code StormClassLoader}.
     */
    private static final Class<?> TRANSFORMERS_CLASS;

    /**
     * Represents {@link StormClassTransformers#applyAll(String, byte[])} method.
     *
     * @see #applyAllTransformers(String, byte[])
     */
    private static final Method APPLY_ALL;

    /**
     * Marks the {@code StormBoostrap} as being fully loaded. This variable will be {@code true}
     * when the static block has finished initializing. Required by classes that are loaded before
     * {@code StormBoostrap} but still depend on it.
     */
    private static final boolean hasLoaded;

    static {
        try {
            MOD_LOADER_CLASS =
                    Class.forName("io.pzstorm.storm.core.StormModLoader", true, CLASS_LOADER);
            TRANSFORMERS_CLASS =
                    Class.forName(
                            "io.pzstorm.storm.core.StormClassTransformers", true, CLASS_LOADER);
            APPLY_ALL =
                    TRANSFORMERS_CLASS.getDeclaredMethod("applyAll", String.class, byte[].class);

            hasLoaded = true;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Applies all registered transformers for the given class name sequentially.
     *
     * @throws ReflectiveOperationException if an error occurred while invoking method.
     * @see StormClassTransformers#applyAll(String, byte[])
     */
    static byte[] applyAllTransformers(String name, byte[] rawClass)
            throws ReflectiveOperationException {
        return (byte[]) APPLY_ALL.invoke(null, name, rawClass);
    }

    /**
     * Returns if {@code StormBoostrap} has finished loading.
     *
     * @return {@code true} if boostrap has been fully loaded.
     */
    static boolean hasLoaded() {
        return hasLoaded;
    }

    /**
     * Use {@link io.pzstorm.storm.core.StormModLoader} to catalog mod components and {@link
     * StormModRegistry} to register mod instances from cataloged classes. This method can be called
     * multiple times, for example when Storm wants to load new mods from local directory. Calls
     * {@link io.pzstorm.storm.core.StormModLoader#loadStormMods()} Calls {@link
     * io.pzstorm.storm.core.StormModLoader#loadStormModClasses()} Calls {@link
     * io.pzstorm.storm.core.StormModRegistry#registerMods()}
     */
    @SuppressWarnings("unchecked")
    static void loadAndRegisterMods() throws ReflectiveOperationException {
        MOD_LOADER_CLASS.getDeclaredMethod("loadStormMods").invoke(null);

        // catalogs were updated so update resource paths for StormClassLoader
        StormBootstrap.CLASS_LOADER.setModResourceLoader(
                (URLClassLoader) MOD_LOADER_CLASS.getConstructor().newInstance());
        MOD_LOADER_CLASS.getDeclaredMethod("loadStormModClasses").invoke(null);

        Class<?> modRegistry =
                Class.forName("io.pzstorm.storm.core.StormModRegistry", true, CLASS_LOADER);
        modRegistry.getDeclaredMethod("registerMods").invoke(null);

        // this class should have already been initialized, so just get the reference
        Class<?> zomboidModClass =
                Class.forName("io.pzstorm.storm.mod.ZomboidMod", false, CLASS_LOADER);

        for (Object mod :
                (Set<Object>) modRegistry.getDeclaredMethod("getRegisteredMods").invoke(null)) {
            zomboidModClass.getDeclaredMethod("registerEventHandlers").invoke(mod);
        }

        // collect mod-provided class transformers before any PZ game classes are loaded
        TRANSFORMERS_CLASS.getDeclaredMethod("collectTransformers").invoke(null);

        Class<?> commandRegistry =
                Class.forName("io.pzstorm.storm.core.StormCommandRegistry", true, CLASS_LOADER);
        commandRegistry.getDeclaredMethod("collectCommands").invoke(null);

        startHttpServerIfConfigured();
    }

    private static void startHttpServerIfConfigured() throws ReflectiveOperationException {
        Class<?> httpServerClass =
                Class.forName("io.pzstorm.storm.http.StormHttpServer", true, CLASS_LOADER);
        int port = (int) httpServerClass.getDeclaredMethod("configuredPort").invoke(null);
        if (port <= 0) {
            return;
        }

        Class<?> dispatcherClass =
                Class.forName("io.pzstorm.storm.event.core.StormEventDispatcher", true, CLASS_LOADER);
        Class<?> builtinClass =
                Class.forName("io.pzstorm.storm.http.StormBuiltinEndpoints", true, CLASS_LOADER);
        dispatcherClass
                .getDeclaredMethod("registerEventHandler", Class.class)
                .invoke(null, builtinClass);

        httpServerClass.getDeclaredMethod("start", int.class).invoke(null, port);
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    try {
                                        httpServerClass.getDeclaredMethod("stop").invoke(null);
                                    } catch (ReflectiveOperationException ignored) {
                                        // shutdown best-effort
                                    }
                                }));
    }
}
