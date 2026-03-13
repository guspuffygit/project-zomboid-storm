package io.pzstorm.storm.core;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.mod.ZomboidMod;
import io.pzstorm.storm.patch.core.CommandBasePatch;
import io.pzstorm.storm.patch.core.ZomboidFileSystemPatch;
import io.pzstorm.storm.patch.core.ZomboidGlobalsPatch;
import io.pzstorm.storm.patch.debugging.DebugLogPatch;
import io.pzstorm.storm.patch.debugging.ThreadPatch;
import io.pzstorm.storm.patch.events.ChatManagerPatch;
import io.pzstorm.storm.patch.events.LuaEventManagerPatch;
import io.pzstorm.storm.patch.fixes.SpriteConfigFixPatch;
import io.pzstorm.storm.patch.fixes.TranslatorPatch;
import io.pzstorm.storm.patch.lua.LuaExposerDumpPatch;
import io.pzstorm.storm.patch.lua.LuaManagerPatch;
import io.pzstorm.storm.patch.rendering.MainScreenStatePatch;
import io.pzstorm.storm.patch.rendering.TISLogoStatePatch;
import io.pzstorm.storm.patch.rendering.UIWorldMapPatch;
import io.pzstorm.storm.patch.rendering.UIWorldMapV1Patch;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;
import org.jetbrains.annotations.Contract;

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
    private static final Map<String, List<StormClassTransformer>> TRANSFORMERS = new HashMap<>();

    static {
        registerTransformer(new MainScreenStatePatch());
        registerTransformer(new TISLogoStatePatch());
        registerTransformer(new LuaEventManagerPatch());
        registerTransformer(new LuaManagerPatch());
        registerTransformer(new LuaExposerDumpPatch());
        registerTransformer(new ZomboidGlobalsPatch());
        registerTransformer(new UIWorldMapPatch());
        registerTransformer(new ChatManagerPatch());
        registerTransformer(new UIWorldMapV1Patch());
        registerTransformer(new DebugLogPatch());
        registerTransformer(new ZomboidFileSystemPatch());
        registerTransformer(new CommandBasePatch());
        registerTransformer(new ThreadPatch());
        registerTransformer(new SpriteConfigFixPatch());
        registerTransformer(new TranslatorPatch());
    }

    private static void registerTransformer(StormClassTransformer transformer) {
        TRANSFORMERS
                .computeIfAbsent(transformer.getClassName(), k -> new ArrayList<>())
                .add(transformer);
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
     * Returns all registered {@link StormClassTransformer} instances that target the given class.
     *
     * @return list of transformers (empty if none registered).
     */
    @Contract(pure = true)
    public static List<StormClassTransformer> getRegistered(String className) {
        return TRANSFORMERS.getOrDefault(className, Collections.emptyList());
    }

    /**
     * Applies all registered transformers for the given class name sequentially. Each transformer
     * independently redefines the class bytes produced by the previous transformer.
     *
     * @param className the binary name of the class to transform.
     * @param rawClass byte array representing the class.
     * @return transformed byte array, or the original if no transformers are registered.
     */
    public static byte[] applyAll(String className, byte[] rawClass) {
        List<StormClassTransformer> transformers = getRegistered(className);
        for (StormClassTransformer transformer : transformers) {
            rawClass = transformer.transform(rawClass);
        }
        return rawClass;
    }

    /**
     * Applies transformers that target classes blacklisted by {@link StormClassLoader} (e.g. {@code
     * java.lang.*}) using the {@link Instrumentation} retransformation API. The {@code
     * Instrumentation} instance is provided by the bootstrap agent's {@code premain()}.
     */
    public static void applyAgentTransformers(Instrumentation instrumentation) {
        for (String className : TRANSFORMERS.keySet()) {
            if (!StormClassLoader.isBlacklistedClass(className)) {
                continue;
            }

            List<StormClassTransformer> transformers =
                    TRANSFORMERS.getOrDefault(className, Collections.emptyList());
            LOGGER.debug("Applying agent-based transformer for blacklisted class: {}", className);

            ResettableClassFileTransformer agent =
                    new AgentBuilder.Default()
                            .disableClassFormatChanges()
                            .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                            .ignore(ElementMatchers.none())
                            .with(
                                    new AgentBuilder.Listener.Adapter() {
                                        @Override
                                        public void onError(
                                                String typeName,
                                                ClassLoader classLoader,
                                                net.bytebuddy.utility.JavaModule module,
                                                boolean loaded,
                                                Throwable throwable) {
                                            LOGGER.error(
                                                    "Agent transformer failed for {}: {}",
                                                    typeName,
                                                    throwable.getMessage(),
                                                    throwable);
                                        }

                                        @Override
                                        public void onTransformation(
                                                net.bytebuddy.description.type.TypeDescription
                                                        typeDescription,
                                                ClassLoader classLoader,
                                                net.bytebuddy.utility.JavaModule module,
                                                boolean loaded,
                                                DynamicType dynamicType) {
                                            LOGGER.debug(
                                                    "Successfully retransformed: {}",
                                                    typeDescription.getName());
                                        }
                                    })
                            .type(ElementMatchers.named(className))
                            .transform(
                                    (builder, typeDescription, classLoader, module, domain) -> {
                                        @SuppressWarnings("unchecked")
                                        DynamicType.Builder<Object> castedBuilder =
                                                (DynamicType.Builder<Object>)
                                                        (DynamicType.Builder<?>) builder;
                                        for (StormClassTransformer transformer : transformers) {
                                            ClassFileLocator locator =
                                                    new ClassFileLocator.Compound(
                                                            ClassFileLocator.ForClassLoader.of(
                                                                    transformer
                                                                            .getClass()
                                                                            .getClassLoader()),
                                                            ClassFileLocator.ForClassLoader
                                                                    .ofSystemLoader());
                                            TypePool typePool = TypePool.Default.of(locator);
                                            castedBuilder =
                                                    transformer.dynamicType(
                                                            locator, typePool, castedBuilder);
                                        }
                                        return castedBuilder;
                                    })
                            .installOn(instrumentation);

            LOGGER.debug("Installed agent transformer for: {}", className);
        }
    }
}
