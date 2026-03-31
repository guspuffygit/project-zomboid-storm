package com.sentientsimulations.storm.core;

import static org.junit.jupiter.api.Assertions.*;

import io.pzstorm.storm.core.StormClassTransformer;
import io.pzstorm.storm.core.StormClassTransformers;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.pool.TypePool;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Integration test that applies every registered ByteBuddy patch to its target game class and
 * verifies the transformation produces valid bytecode. This catches issues such as:
 *
 * <ul>
 *   <li>Advice referencing methods/fields that no longer exist in the game class
 *   <li>ByteBuddy configuration errors (wrong matchers, bad substitution targets)
 *   <li>Incompatible type references between Storm and game code
 * </ul>
 */
class AllPatchesIntegrationTest {

    @SuppressWarnings("unchecked")
    private static Map<String, List<StormClassTransformer>> getTransformersMap() throws Exception {
        Field field = StormClassTransformers.class.getDeclaredField("TRANSFORMERS");
        field.setAccessible(true);
        return (Map<String, List<StormClassTransformer>>) field.get(null);
    }

    @TestFactory
    Collection<DynamicTest> allPatchesShouldProduceValidBytecode() throws Exception {
        Map<String, List<StormClassTransformer>> transformers = getTransformersMap();
        List<DynamicTest> tests = new ArrayList<>();

        for (Map.Entry<String, List<StormClassTransformer>> entry : transformers.entrySet()) {
            String className = entry.getKey();
            for (StormClassTransformer transformer : entry.getValue()) {
                String patchName = transformer.getClass().getSimpleName();
                String displayName = patchName + " -> " + className;

                if (className.startsWith("java.")) {
                    tests.add(
                            DynamicTest.dynamicTest(
                                    displayName + " [SKIPPED - requires Instrumentation agent]",
                                    () -> {}));
                    continue;
                }

                tests.add(
                        DynamicTest.dynamicTest(
                                displayName,
                                () -> {
                                    String resourcePath = className.replace('.', '/') + ".class";
                                    byte[] rawClass;
                                    try (InputStream is =
                                            getClass()
                                                    .getClassLoader()
                                                    .getResourceAsStream(resourcePath)) {
                                        assertNotNull(
                                                is,
                                                "Target class should be on the classpath: "
                                                        + className);
                                        rawClass = is.readAllBytes();
                                    }

                                    byte[] transformed =
                                            assertDoesNotThrow(
                                                    () -> transformer.transform(rawClass),
                                                    patchName
                                                            + " should transform "
                                                            + className
                                                            + " without throwing");

                                    assertNotNull(
                                            transformed, "Transformed bytes should not be null");
                                    assertTrue(
                                            transformed.length > 0,
                                            "Transformed bytes should not be empty");

                                    // Verify the transformed bytes are resolvable by ByteBuddy's
                                    // own type system (uses its bundled ASM which supports Java 25)
                                    ClassFileLocator locator =
                                            ClassFileLocator.Simple.of(className, transformed);
                                    TypePool typePool = TypePool.Default.of(locator);
                                    TypePool.Resolution resolution = typePool.describe(className);
                                    assertTrue(
                                            resolution.isResolved(),
                                            patchName
                                                    + " output should be resolvable as "
                                                    + className);
                                }));
            }
        }
        return tests;
    }
}
