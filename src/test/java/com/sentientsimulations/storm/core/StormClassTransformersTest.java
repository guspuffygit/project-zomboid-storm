package com.sentientsimulations.storm.core;

import io.pzstorm.storm.IntegrationTest;
import io.pzstorm.storm.core.StormBootstrap;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@code StormClassTransformers.collectTransformers()} picks up transformers provided
 * by mods via {@code ZomboidMod.getClassTransformers()}, drops a mod whose provider throws, and
 * refuses to continue when that mod is a required security mod on a server.
 */
class StormClassTransformersTest implements IntegrationTest {

    private static final Class<?> STORM_CLASS_TRANSFORMERS;
    private static final Class<?> STORM_MOD_REGISTRY;

    private static final Method GET_REGISTERED;
    private static final Method COLLECT_TRANSFORMERS;
    private static final Method DROP_TRANSFORMERS;
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
            DROP_TRANSFORMERS =
                    STORM_CLASS_TRANSFORMERS.getDeclaredMethod("dropTransformers", String.class);
            DROP_TRANSFORMERS.setAccessible(true);

            MOD_REGISTRY_MAP = STORM_MOD_REGISTRY.getDeclaredField("MOD_REGISTRY");
            MOD_REGISTRY_MAP.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    /** Remove the test mods and their transformers after each test to avoid polluting others. */
    @AfterEach
    @SuppressWarnings("unchecked")
    void cleanupRegistry() throws ReflectiveOperationException {
        Map<String, Object> registry = (Map<String, Object>) MOD_REGISTRY_MAP.get(null);
        for (String modId :
                List.of("test-transformer-mod", "test-throwing-mod", "security-patches")) {
            registry.remove(modId);
            DROP_TRANSFORMERS.invoke(null, modId);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void collectTransformers_shouldRegisterModProvidedTransformers()
            throws ReflectiveOperationException {

        // Verify the transformer is NOT registered before collection
        List<?> before = (List<?>) GET_REGISTERED.invoke(null, TestModTransformer.TARGET_CLASS);
        Assertions.assertTrue(
                before.isEmpty(), "Transformer should not be registered before collection");

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
        List<?> after = (List<?>) GET_REGISTERED.invoke(null, TestModTransformer.TARGET_CLASS);
        Assertions.assertFalse(
                after.isEmpty(), "Transformer should be registered after collection");

        // Verify it targets the correct class
        Object transformer = after.get(0);
        Method getClassName = transformer.getClass().getMethod("getClassName");
        String registeredClassName = (String) getClassName.invoke(transformer);
        Assertions.assertEquals(TestModTransformer.TARGET_CLASS, registeredClassName);
    }

    @Test
    @SuppressWarnings("unchecked")
    void collectTransformers_shouldDeregisterModWhoseProviderThrows()
            throws ReflectiveOperationException {

        Map<String, Object> registry = (Map<String, Object>) MOD_REGISTRY_MAP.get(null);
        registry.put(
                "test-throwing-mod",
                newMod("com.sentientsimulations.storm.core.TestThrowingTransformerMod"));
        registry.put(
                "test-transformer-mod",
                newMod("com.sentientsimulations.storm.core.TestTransformerMod"));

        withStormServer(false, () -> COLLECT_TRANSFORMERS.invoke(null));

        Assertions.assertFalse(
                registry.containsKey("test-throwing-mod"),
                "Mod whose getClassTransformers throws should be removed from the registry");
        Assertions.assertTrue(
                registry.containsKey("test-transformer-mod"),
                "Mods that did not throw should stay registered");
        List<?> registered = (List<?>) GET_REGISTERED.invoke(null, TestModTransformer.TARGET_CLASS);
        Assertions.assertFalse(
                registered.isEmpty(), "Healthy mod's transformer should still be collected");
    }

    @Test
    @SuppressWarnings("unchecked")
    void collectTransformers_shouldFailClosedWhenSecurityModThrowsOnServer()
            throws ReflectiveOperationException {

        Map<String, Object> registry = (Map<String, Object>) MOD_REGISTRY_MAP.get(null);
        registry.put(
                "security-patches",
                newMod("com.sentientsimulations.projectzomboid.securitypatches.TestFailClosedMod"));

        InvocationTargetException thrown =
                Assertions.assertThrows(
                        InvocationTargetException.class,
                        () -> withStormServer(true, () -> COLLECT_TRANSFORMERS.invoke(null)));

        Assertions.assertEquals(
                "java.lang.IllegalStateException", thrown.getCause().getClass().getName());
        Assertions.assertTrue(
                thrown.getCause().getMessage().contains("security-patches"),
                "Failure should name the mod: " + thrown.getCause().getMessage());
        Assertions.assertTrue(
                registry.containsKey("security-patches"),
                "A fail-closed mod is never silently dropped from the registry");
    }

    @Test
    @SuppressWarnings("unchecked")
    void collectTransformers_shouldFailSoftWhenSecurityModThrowsOnClient()
            throws ReflectiveOperationException {

        Map<String, Object> registry = (Map<String, Object>) MOD_REGISTRY_MAP.get(null);
        registry.put(
                "security-patches",
                newMod("com.sentientsimulations.projectzomboid.securitypatches.TestFailClosedMod"));

        withStormServer(false, () -> COLLECT_TRANSFORMERS.invoke(null));

        Assertions.assertFalse(
                registry.containsKey("security-patches"),
                "Off the server the security mod is dropped like any other failing mod");
    }

    @Test
    void collectTransformers_shouldNotFailWithNoMods() {
        // With no test mods injected, collectTransformers should complete without error
        Assertions.assertDoesNotThrow(() -> COLLECT_TRANSFORMERS.invoke(null));
    }

    private static Object newMod(String className) throws ReflectiveOperationException {
        Class<?> modClass = Class.forName(className, true, StormBootstrap.CLASS_LOADER);
        Constructor<?> constructor = modClass.getDeclaredConstructor();
        return constructor.newInstance();
    }

    private interface ReflectiveAction {
        void run() throws ReflectiveOperationException;
    }

    private static void withStormServer(boolean isServer, ReflectiveAction action)
            throws ReflectiveOperationException {
        String previous = System.getProperty("storm.server");
        System.setProperty("storm.server", Boolean.toString(isServer));
        try {
            action.run();
        } finally {
            if (previous != null) {
                System.setProperty("storm.server", previous);
            } else {
                System.clearProperty("storm.server");
            }
        }
    }
}
