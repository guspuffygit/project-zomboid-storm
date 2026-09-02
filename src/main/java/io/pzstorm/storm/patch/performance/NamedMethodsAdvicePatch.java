package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Base for the small family of patches that weave one advice class onto a named set of methods (all
 * overloads) of a single target class. Fails loud at weave time if any name no longer matches a
 * declared method, so a renamed vanilla mutator cannot silently drop an epoch source.
 */
public abstract class NamedMethodsAdvicePatch extends StormClassTransformer {

    private final String target;
    private final String adviceClass;
    private final String[] methodNames;

    protected NamedMethodsAdvicePatch(String target, String adviceClass, String... methodNames) {
        super(target);
        this.target = target;
        this.adviceClass = adviceClass;
        this.methodNames = methodNames;
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        TypeDescription type = typePool.describe(target).resolve();
        for (String name : methodNames) {
            if (type.getDeclaredMethods().filter(ElementMatchers.named(name)).isEmpty()) {
                throw new IllegalStateException(
                        getClass().getSimpleName()
                                + ": "
                                + target
                                + " no longer declares "
                                + name
                                + " — re-verify against the current game source.");
            }
        }
        return builder.visit(
                Advice.to(typePool.describe(adviceClass).resolve(), locator)
                        .on(ElementMatchers.namedOneOf(methodNames)));
    }
}
