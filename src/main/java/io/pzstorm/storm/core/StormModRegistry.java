package io.pzstorm.storm.core;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import com.google.common.collect.ImmutableSet;
import io.pzstorm.storm.mod.ZomboidMod;
import io.pzstorm.storm.util.StormEnv;
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

    /** Called by {@link StormBootstrap#loadAndRegisterMods()} */
    public static void registerMods() throws ReflectiveOperationException {
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
                MOD_REGISTRY.put(
                        entry.getKey(),
                        (ZomboidMod) modClass.get().getDeclaredConstructor().newInstance());
            }
        }
    }

    static boolean isBlockedOnClient(String modClassName) {
        if (StormEnv.isStormServer()) {
            return false;
        }
        for (String blocked : CLIENT_BLOCKED_MOD_PACKAGES) {
            if (modClassName.equals(blocked) || modClassName.startsWith(blocked + '.')) {
                return true;
            }
        }
        return false;
    }

    public static @UnmodifiableView Set<ZomboidMod> getRegisteredMods() {
        return Set.copyOf(MOD_REGISTRY.values());
    }

    @TestOnly
    static @Nullable ZomboidMod getRegisteredMod(String name) {
        return MOD_REGISTRY.get(name);
    }
}
