package io.pzstorm.storm.core;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import com.google.common.collect.ImmutableSet;
import io.pzstorm.storm.event.core.StormEventDispatcher;
import io.pzstorm.storm.mod.ZomboidMod;
import io.pzstorm.storm.util.StormEnv;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.util.*;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;
import org.jetbrains.annotations.UnmodifiableView;

public class StormModRegistry {

    private static final Map<String, ZomboidMod> MOD_REGISTRY = new HashMap<>();

    /**
     * Package prefixes of server-only mods that are skipped when Storm runs on a client.
     *
     * <p>These mods register transformers for server classes without gating on {@link
     * StormEnv#isStormServer()}, and a client that loads them dies during bootstrap. {@code
     * org.dotd.authgate} logs through {@code DebugLog} from inside its {@code GameServer}
     * transformer, and because {@code DebugLog.init} is what triggers the {@code GameServer} load
     * in the first place, the reentrant lookup throws {@code ClassCircularityError} and takes the
     * whole client down before the main menu.
     */
    private static final Set<String> CLIENT_BLOCKED_MOD_PACKAGES = Set.of("org.dotd.authgate");

    /**
     * Package prefixes of mods a server must not run without. A server that skips one of these at
     * any load stage refuses to start instead of running unprotected; see {@link #disableMod}.
     */
    private static final Set<String> FAIL_CLOSED_MOD_PACKAGES =
            Set.of("com.sentientsimulations.projectzomboid.securitypatches");

    /** Called by {@link StormBootstrap#loadAndRegisterMods()} */
    public static void registerMods() {
        for (Map.Entry<String, ImmutableSet<Class<?>>> entry :
                StormModLoader.CLASS_CATALOG.entrySet()) {
            Optional<Class<?>> modClass =
                    entry.getValue().stream()
                            .filter(
                                    cls ->
                                            ZomboidMod.class.isAssignableFrom(cls)
                                                    && !cls.isInterface()
                                                    && !Modifier.isAbstract(cls.getModifiers()))
                            .findFirst();

            if (modClass.isPresent()) {
                String className = modClass.get().getName();
                if (isBlockedOnClient(className)) {
                    LOGGER.warn(
                            "Skipping server-only mod {} ({}) because Storm is running on a client",
                            entry.getKey(),
                            className);
                    continue;
                }
                ZomboidMod mod;
                try {
                    mod = (ZomboidMod) modClass.get().getDeclaredConstructor().newInstance();
                } catch (InvocationTargetException e) {
                    disableMod(entry.getKey(), "instantiating " + className, e.getCause());
                    continue;
                } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                    disableMod(entry.getKey(), "instantiating " + className, e);
                    continue;
                }
                MOD_REGISTRY.put(entry.getKey(), mod);
            }
        }
    }

    /** Called by {@link StormBootstrap#loadAndRegisterMods()} after transformers are collected. */
    public static void registerEventHandlers() {
        for (Map.Entry<String, ZomboidMod> entry : getRegisteredModsById().entrySet()) {
            try {
                entry.getValue().registerEventHandlers();
            } catch (RuntimeException | LinkageError e) {
                disableMod(entry.getKey(), "registerEventHandlers", e);
            }
        }
    }

    /**
     * Drops everything the mod registered so far (registry entry, transformers, event handlers and
     * commands) so the rest of the load runs as if the mod were not installed. A mod under {@link
     * #FAIL_CLOSED_MOD_PACKAGES} on a server is never dropped; the server refuses to start instead.
     *
     * @throws IllegalStateException when the mod must fail closed.
     */
    public static void disableMod(String modId, String stage, Throwable cause) {
        if (isFailClosed(modId)) {
            LOGGER.error(
                    "Mod {} failed while {}; it is a required security mod, so the server will not"
                            + " start. Rebuild it against this game version.",
                    modId,
                    stage,
                    cause);
            throw new IllegalStateException(
                    "Required security mod " + modId + " failed while " + stage, cause);
        }
        Set<Class<?>> modClasses =
                StormModLoader.CLASS_CATALOG.getOrDefault(modId, ImmutableSet.of());
        MOD_REGISTRY.remove(modId);
        StormClassTransformers.dropTransformers(modId);
        StormEventDispatcher.unregisterEventHandlers(modClasses);
        StormCommandRegistry.dropCommands(modClasses);
        LOGGER.warn(
                "Mod {} failed while {}; it is disabled for this session as if it were not"
                        + " installed. Rebuild it against this game version.",
                modId,
                stage,
                cause);
    }

    static boolean isBlockedOnClient(String modClassName) {
        if (StormEnv.isStormServer()) {
            return false;
        }
        return isUnderAny(modClassName, CLIENT_BLOCKED_MOD_PACKAGES);
    }

    static boolean isFailClosed(String modId) {
        if (!StormEnv.isStormServer()) {
            return false;
        }
        ZomboidMod mod = MOD_REGISTRY.get(modId);
        if (mod != null) {
            return isUnderAny(mod.getClass().getName(), FAIL_CLOSED_MOD_PACKAGES);
        }
        for (String className : StormModLoader.getModClassNames(modId)) {
            if (isUnderAny(className, FAIL_CLOSED_MOD_PACKAGES)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isUnderAny(String className, Set<String> packages) {
        for (String pkg : packages) {
            if (className.equals(pkg) || className.startsWith(pkg + '.')) {
                return true;
            }
        }
        return false;
    }

    public static @UnmodifiableView Set<ZomboidMod> getRegisteredMods() {
        return Set.copyOf(MOD_REGISTRY.values());
    }

    public static @UnmodifiableView Map<String, ZomboidMod> getRegisteredModsById() {
        return Map.copyOf(MOD_REGISTRY);
    }

    @TestOnly
    static @Nullable ZomboidMod getRegisteredMod(String name) {
        return MOD_REGISTRY.get(name);
    }
}
