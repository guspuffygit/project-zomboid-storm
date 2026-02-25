package io.pzstorm.storm.core;

import io.pzstorm.storm.mod.ZomboidMod;
import io.pzstorm.storm.patch.*;
import io.pzstorm.storm.patch.debugging.DebugLogPatch;
import io.pzstorm.storm.patch.debugging.ProcessBuilderPatch;
import io.pzstorm.storm.patch.lua.LuaEventManagerPatch;
import io.pzstorm.storm.patch.lua.LuaEventPatch;
import io.pzstorm.storm.patch.lua.LuaExposerDumpPatch;
import io.pzstorm.storm.patch.lua.LuaManagerPatch;
import io.pzstorm.storm.patch.mod.CommandBasePatch;
import io.pzstorm.storm.patch.mod.CoopMasterPatch;
import io.pzstorm.storm.patch.mod.ZomboidFileSystemPatch;
import io.pzstorm.storm.patch.rendering.MainScreenStatePatch;
import io.pzstorm.storm.patch.rendering.TISLogoStatePatch;
import io.pzstorm.storm.patch.rendering.UIWorldMapPatch;
import io.pzstorm.storm.patch.rendering.UIWorldMapV1Patch;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

/**
 * This class defines, initializes and stores {@link StormClassTransformer} instances. To retrieve a
 * mapped instance of registered transformer call {@link #getRegistered(String)}.
 */
@SuppressWarnings({"WeakerAccess", "unused"})
public class StormClassTransformers {

    /**
     * Internal registry of created transformers. This map is checked for entries by {@link
     * StormClassLoader} when loading classes and invokes the transformation chain of methods to
     * transform the class before defining it via JVM.
     */
    private static final Map<String, StormClassTransformer> TRANSFORMERS = new HashMap<>();

    static {
        registerTransformer(new ProcessBuilderPatch());
        registerTransformer(new MainScreenStatePatch());
        registerTransformer(new TISLogoStatePatch());
        registerTransformer(new LuaEventManagerPatch());
        registerTransformer(new LuaManagerPatch());
        registerTransformer(new LuaExposerDumpPatch());
        registerTransformer(new LuaEventPatch());
        registerTransformer(new ZomboidGlobalsPatch());
        registerTransformer(new UIWorldMapPatch());
        registerTransformer(new ChatManagerPatch());
        registerTransformer(new UIWorldMapV1Patch());
        registerTransformer(new DebugLogPatch());
        registerTransformer(new ZomboidFileSystemPatch());
        registerTransformer(new CoopMasterPatch());
        registerTransformer(new CommandBasePatch());
    }

    private static void registerTransformer(StormClassTransformer transformer) {
        TRANSFORMERS.put(transformer.getClassName(), transformer);
    }

    /**
     * Called by {@link StormBootstrap#loadAndRegisterMods()} to collect mod-provided transformers.
     */
    public static void collectTransformers() {
        for (ZomboidMod mod : StormModRegistry.getRegisteredMods()) {
            List<StormClassTransformer> transformers = mod.getClassTransformers();
            if (transformers != null) {
                for (StormClassTransformer transformer : transformers) {
                    registerTransformer(transformer);
                }
            }
        }
    }

    /**
     * Returns registered instance of {@link StormClassTransformer} that matches the given name.
     *
     * @return {@code StormClassTransformer} or {@code null} if no registered instance found.
     */
    @Contract(pure = true)
    public static @Nullable StormClassTransformer getRegistered(String className) {
        return TRANSFORMERS.getOrDefault(className, null);
    }
}
