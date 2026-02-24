package com.sentientsimulations.storm.core;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.pool.TypePool;

/**
 * A no-op {@link StormClassTransformer} used to verify that mod-provided transformers are
 * collected and registered by {@code StormClassTransformers.collectTransformers()}.
 */
public class TestModTransformer extends StormClassTransformer {

    public static final String TARGET_CLASS = "zombie.test.FakeTargetClass";

    public TestModTransformer() {
        super(TARGET_CLASS);
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder; // no-op — only used to test registration
    }
}
