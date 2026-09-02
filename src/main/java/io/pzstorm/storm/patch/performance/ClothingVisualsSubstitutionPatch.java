package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import io.pzstorm.storm.inventory.StormClothingVisuals;
import java.lang.reflect.Method;
import net.bytebuddy.asm.MemberSubstitution;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Base for the three call-site substitutions that route clothing-visual resolution through {@link
 * StormClothingVisuals}: inside the named methods of the target class, {@code
 * InventoryItem.getVisual()} becomes {@link StormClothingVisuals#cachedVisual(Object)} and {@code
 * IsoGameCharacter.getItemVisuals(ItemVisuals)} becomes {@link
 * StormClothingVisuals#fillItemVisuals(Object, Object)}. Matchers are by name, arity and return
 * type rather than declaring type, so the {@code Clothing}-typed receivers at the hole-check sites
 * match too. Fails loud if a named method disappears; the substitution itself is {@code relaxed()}
 * (a silent no-op on a matcher miss), which the bytecode test covers.
 */
abstract class ClothingVisualsSubstitutionPatch extends StormClassTransformer {

    private final String target;
    private final String[] methodNames;

    ClothingVisualsSubstitutionPatch(String target, String... methodNames) {
        super(target);
        this.target = target;
        this.methodNames = methodNames;
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        for (String name : methodNames) {
            if (typePool.describe(target)
                    .resolve()
                    .getDeclaredMethods()
                    .filter(ElementMatchers.named(name))
                    .isEmpty()) {
                throw new IllegalStateException(
                        getClass().getSimpleName()
                                + ": "
                                + target
                                + " no longer declares "
                                + name
                                + " — re-verify against the current game source.");
            }
        }
        ElementMatcher.Junction<MethodDescription> inNamedMethods =
                ElementMatchers.namedOneOf(methodNames);
        Method cachedVisual;
        Method fillItemVisuals;
        try {
            cachedVisual =
                    StormClothingVisuals.class.getDeclaredMethod("cachedVisual", Object.class);
            fillItemVisuals =
                    StormClothingVisuals.class.getDeclaredMethod(
                            "fillItemVisuals", Object.class, Object.class);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("StormClothingVisuals helper signature changed", e);
        }
        return builder.visit(
                        MemberSubstitution.relaxed()
                                .method(
                                        ElementMatchers.named("getVisual")
                                                .and(ElementMatchers.takesArguments(0))
                                                .and(
                                                        ElementMatchers.returns(
                                                                ElementMatchers.named(
                                                                        "zombie.core.skinnedmodel"
                                                                                + ".visual.ItemVisual"))))
                                .replaceWith(cachedVisual)
                                .on(inNamedMethods))
                .visit(
                        MemberSubstitution.relaxed()
                                .method(
                                        ElementMatchers.named("getItemVisuals")
                                                .and(ElementMatchers.takesArguments(1))
                                                .and(
                                                        ElementMatchers.isDeclaredBy(
                                                                ElementMatchers.named(
                                                                        "zombie.characters"
                                                                                + ".IsoGameCharacter"))))
                                .replaceWith(fillItemVisuals)
                                .on(inNamedMethods));
    }
}
