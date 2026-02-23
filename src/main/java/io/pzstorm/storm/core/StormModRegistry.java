package io.pzstorm.storm.core;

import com.google.common.collect.ImmutableSet;
import io.pzstorm.storm.mod.ZomboidMod;
import java.util.*;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;
import org.jetbrains.annotations.UnmodifiableView;

public class StormModRegistry {

    private static final Map<String, ZomboidMod> MOD_REGISTRY = new HashMap<>();

    /** Called by {@link StormBootstrap#loadAndRegisterMods()} */
    public static void registerMods() throws ReflectiveOperationException {
        for (Map.Entry<String, ImmutableSet<Class<?>>> entry :
                StormModLoader.CLASS_CATALOG.entrySet()) {
            // find the first class that implements ZomboidMod interface
            Optional<Class<?>> modClass =
                    entry.getValue().stream()
                            .filter(ZomboidMod.class::isAssignableFrom)
                            .findFirst();

            if (modClass.isPresent()) {
                MOD_REGISTRY.put(
                        entry.getKey(),
                        (ZomboidMod) modClass.get().getDeclaredConstructor().newInstance());
            }
        }
    }

    public static @UnmodifiableView Set<ZomboidMod> getRegisteredMods() {
        return Set.copyOf(MOD_REGISTRY.values());
    }

    @TestOnly
    static @Nullable ZomboidMod getRegisteredMod(String name) {
        return MOD_REGISTRY.get(name);
    }
}
