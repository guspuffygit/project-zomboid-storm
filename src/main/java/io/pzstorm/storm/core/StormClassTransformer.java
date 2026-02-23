package io.pzstorm.storm.core;

import lombok.Getter;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.pool.TypePool;

public abstract class StormClassTransformer {

    @Getter protected final String className;

    public StormClassTransformer(String className) {
        this.className = className;
    }

    /**
     * Calls method chain to transform the given {@code Class} byte array.
     *
     * @param rawClass byte array representing the {@code Class} to transform.
     * @return byte array representing transformed class.
     */
    public byte[] transform(byte[] rawClass) {
        try {
            ClassFileLocator locator = defaultClassFileLocator(rawClass);
            TypePool typePool = TypePool.Default.of(locator);

            DynamicType.Builder<Object> builder =
                    new ByteBuddy().redefine(typePool.describe(className).resolve(), locator);

            return dynamicType(locator, typePool, builder).make().getBytes();
        } catch (Exception e) {
            throw new RuntimeException("Failed to apply ByteBuddy patch to UIElement", e);
        }
    }

    public abstract DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder);

    public ClassFileLocator defaultClassFileLocator(byte[] rawClass) {
        return new ClassFileLocator.Compound(
                ClassFileLocator.Simple.of(className, rawClass),
                ClassFileLocator.ForClassLoader.of(this.getClass().getClassLoader()),
                ClassFileLocator.ForClassLoader.ofSystemLoader());
    }
}
