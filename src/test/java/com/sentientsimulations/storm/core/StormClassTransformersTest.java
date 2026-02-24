package com.sentientsimulations.storm.core;

import io.pzstorm.storm.IntegrationTest;
import io.pzstorm.storm.core.StormBootstrap;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@code StormClassTransformers.collectTransformers()} picks up transformers provided
 * by mods via {@code ZomboidMod.getClassTransformers()}.
 */
class StormClassTransformersTest implements IntegrationTest {

    private static final Class<?> STORM_CLASS_TRANSFORMERS;
    private static final Class<?> STORM_MOD_REGISTRY;

    private static final Method GET_REGISTERED;
    private static final Method COLLECT_TRANSFORMERS;
    private static final Field MOD_REGISTRY_MAP;

    static {
        try {
            STORM_CLASS_TRANSFORMERS =
                    Class.forName(
                            "io.pzstorm.storm.core.StormClassTransformers",
                            true,
                            StormBootstrap.CLASS_LOADER);
            STORM_MOD_REGISTRY =
                    Class.forName(
                            "io.pzstorm.storm.core.StormModRegistry",
                            true,
                            StormBootstrap.CLASS_LOADER);

            GET_REGISTERED =
                    STORM_CLASS_TRANSFORMERS.getDeclaredMethod("getRegistered", String.class);
            COLLECT_TRANSFORMERS =
                    STORM_CLASS_TRANSFORMERS.getDeclaredMethod("collectTransformers");

            MOD_REGISTRY_MAP = STORM_MOD_REGISTRY.getDeclaredField("MOD_REGISTRY");
            MOD_REGISTRY_MAP.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    /** Remove the test mod from the registry after each test to avoid polluting other tests. */
    @AfterEach
    @SuppressWarnings("unchecked")
    void cleanupRegistry() throws ReflectiveOperationException {
        Map<String, Object> registry = (Map<String, Object>) MOD_REGISTRY_MAP.get(null);
        registry.remove("test-transformer-mod");
    }

    @Test
    @SuppressWarnings("unchecked")
    void collectTransformers_shouldRegisterModProvidedTransformers()
            throws ReflectiveOperationException {

        // Verify the transformer is NOT registered before collection
        Object before = GET_REGISTERED.invoke(null, TestModTransformer.TARGET_CLASS);
        Assertions.assertNull(before, "Transformer should not be registered before collection");

        // Load TestTransformerMod through StormClassLoader so it implements the correct
        // ZomboidMod interface (loaded by the same classloader as StormModRegistry)
        Class<?> testModClass =
                Class.forName(
                        "com.sentientsimulations.storm.core.TestTransformerMod",
                        true,
                        StormBootstrap.CLASS_LOADER);
        Constructor<?> constructor = testModClass.getDeclaredConstructor();
        Object testMod = constructor.newInstance();

        // Inject the test mod into StormModRegistry.MOD_REGISTRY
        Map<String, Object> registry = (Map<String, Object>) MOD_REGISTRY_MAP.get(null);
        registry.put("test-transformer-mod", testMod);

        // Call collectTransformers() — this should pick up our test mod's transformer
        COLLECT_TRANSFORMERS.invoke(null);

        // Verify the transformer IS now registered
        Object after = GET_REGISTERED.invoke(null, TestModTransformer.TARGET_CLASS);
        Assertions.assertNotNull(after, "Transformer should be registered after collection");

        // Verify it targets the correct class
        Method getClassName = after.getClass().getMethod("getClassName");
        String registeredClassName = (String) getClassName.invoke(after);
        Assertions.assertEquals(TestModTransformer.TARGET_CLASS, registeredClassName);
    }

    @Test
    void collectTransformers_shouldNotFailWithNoMods() {
        // With no test mods injected, collectTransformers should complete without error
        Assertions.assertDoesNotThrow(() -> COLLECT_TRANSFORMERS.invoke(null));
    }
}
