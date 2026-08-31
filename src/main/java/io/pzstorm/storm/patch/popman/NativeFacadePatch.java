package io.pzstorm.storm.patch.popman;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Replaces a class's {@code n_*} natives with forwarders to same-named static methods on a Java
 * facade. ByteBuddy's {@code redefine} clears {@code ACC_NATIVE} and installs the body, so the JVM
 * never looks the symbol up in {@code PZPopMan64}.
 *
 * <p>Natives are bound by name rather than a wildcard so a game update that adds or renames one
 * fails the weave test instead of silently leaving a live JNI call in a Java-backed class.
 */
public abstract class NativeFacadePatch extends StormClassTransformer {

    private final Class<?> facade;
    private final String[] natives;

    protected NativeFacadePatch(String target, Class<?> facade, String[] natives) {
        super(target);
        this.facade = facade;
        this.natives = natives;
    }

    public String[] natives() {
        return natives.clone();
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        for (String name : natives) {
            builder =
                    builder.method(ElementMatchers.named(name).and(ElementMatchers.isNative()))
                            .intercept(
                                    MethodDelegation.withDefaultConfiguration()
                                            .filter(ElementMatchers.named(name))
                                            .to(facade));
        }
        return builder;
    }
}
