package com.sentientsimulations.storm.core;

import io.pzstorm.storm.IntegrationTest;
import io.pzstorm.storm.core.StormBootstrap;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Verifies that multiple {@code StormClassTransformer} instances can be registered for the same
 * target class and that {@code StormClassTransformers.applyAll()} chains all of them sequentially.
 */
class MultipleTransformersIntegrationTest implements IntegrationTest {

    private static final String TARGET_CLASS = "zombie.MultiPatchTarget";

    private static final Class<?> STORM_CLASS_TRANSFORMER;
    private static final Class<?> STORM_CLASS_TRANSFORMERS;

    private static final Method REGISTER_TRANSFORMER;
    private static final Method GET_REGISTERED;
    private static final Method APPLY_ALL;

    static {
        try {
            STORM_CLASS_TRANSFORMER =
                    Class.forName(
                            "io.pzstorm.storm.core.StormClassTransformer",
                            true,
                            StormBootstrap.CLASS_LOADER);
            STORM_CLASS_TRANSFORMERS =
                    Class.forName(
                            "io.pzstorm.storm.core.StormClassTransformers",
                            true,
                            StormBootstrap.CLASS_LOADER);

            REGISTER_TRANSFORMER =
                    STORM_CLASS_TRANSFORMERS.getDeclaredMethod(
                            "registerTransformer", STORM_CLASS_TRANSFORMER);
            REGISTER_TRANSFORMER.setAccessible(true);

            GET_REGISTERED =
                    STORM_CLASS_TRANSFORMERS.getDeclaredMethod("getRegistered", String.class);

            APPLY_ALL =
                    STORM_CLASS_TRANSFORMERS.getDeclaredMethod(
                            "applyAll", String.class, byte[].class);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Remove test transformers from the registry after each test to avoid polluting other tests.
     */
    @AfterEach
    void cleanup() throws ReflectiveOperationException {
        Field transformersField = STORM_CLASS_TRANSFORMERS.getDeclaredField("TRANSFORMERS");
        transformersField.setAccessible(true);
        Object transformers = transformersField.get(null);
        Method remove = transformers.getClass().getMethod("remove", Object.class);
        remove.invoke(transformers, TARGET_CLASS);
    }

    private Object createTransformer(String transformerClassName)
            throws ReflectiveOperationException {
        Class<?> transformerClass =
                Class.forName(transformerClassName, true, StormBootstrap.CLASS_LOADER);
        Constructor<?> constructor = transformerClass.getConstructor();
        return constructor.newInstance();
    }

    private void registerTransformer(Object transformer) throws ReflectiveOperationException {
        REGISTER_TRANSFORMER.invoke(null, STORM_CLASS_TRANSFORMER.cast(transformer));
    }

    @Test
    @SuppressWarnings("unchecked")
    void applyAll_shouldApplyMultipleTransformersToSameClass() throws Exception {
        // Register two transformers targeting the same class
        Object transformerA = createTransformer("io.pzstorm.storm.core.MultiPatchTransformerA");
        Object transformerB = createTransformer("io.pzstorm.storm.core.MultiPatchTransformerB");
        registerTransformer(transformerA);
        registerTransformer(transformerB);

        // Verify both are registered
        List<?> registered = (List<?>) GET_REGISTERED.invoke(null, TARGET_CLASS);
        Assertions.assertEquals(
                2, registered.size(), "Both transformers should be registered for the same class");

        // Get raw class bytes
        byte[] rawClass;
        try (InputStream is =
                getClass().getClassLoader().getResourceAsStream("zombie/MultiPatchTarget.class")) {
            Assertions.assertNotNull(is, "MultiPatchTarget.class should be on the classpath");
            rawClass = is.readAllBytes();
        }

        // Apply all transformers
        byte[] transformed = (byte[]) APPLY_ALL.invoke(null, TARGET_CLASS, rawClass);

        // Load the transformed class and verify both patches took effect
        Class<?> patched = defineClassFromBytes(TARGET_CLASS, transformed);

        String resultA = (String) patched.getDeclaredMethod("getA").invoke(null);
        String resultB = (String) patched.getDeclaredMethod("getB").invoke(null);

        Assertions.assertEquals("patched-a", resultA, "TransformerA should have patched getA()");
        Assertions.assertEquals("patched-b", resultB, "TransformerB should have patched getB()");
    }

    @Test
    void applyAll_shouldReturnOriginalBytesWhenNoTransformersRegistered() throws Exception {
        byte[] rawClass;
        try (InputStream is =
                getClass().getClassLoader().getResourceAsStream("zombie/MultiPatchTarget.class")) {
            Assertions.assertNotNull(is, "MultiPatchTarget.class should be on the classpath");
            rawClass = is.readAllBytes();
        }

        byte[] result =
                (byte[]) APPLY_ALL.invoke(null, "zombie.NoTransformersRegistered", rawClass);

        Assertions.assertArrayEquals(
                rawClass,
                result,
                "applyAll should return original bytes when no transformers are registered");
    }

    private static Class<?> defineClassFromBytes(String name, byte[] bytes) {
        return new ClassLoader(MultipleTransformersIntegrationTest.class.getClassLoader()) {
            Class<?> define() {
                return defineClass(name, bytes, 0, bytes.length);
            }
        }.define();
    }
}
