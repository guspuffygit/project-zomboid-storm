package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.MemberSubstitution;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Substitutes the single {@code ArrayList.remove(Object)} bytecode invocation inside {@code
 * IsoObject.removeFromWorld()} with a static call to {@code
 * StormCellMembership.removeStaticUpdaterFromList}, which performs the removal in O(1) using a
 * sidecar index instead of an O(n) linear scan.
 *
 * <p>Pairs with {@link CellAddToStaticUpdaterFastPatch}: the add-side patch primes the sidecar
 * index, this remove-side patch consumes it. Either patch alone is benign (the helper falls through
 * to {@code ArrayList.remove(Object)} when the index has no entry), but both are needed to realise
 * the speedup.
 *
 * <p>Scoping: only the no-arg {@code removeFromWorld()} on {@code IsoObject} is rewritten — the
 * overload {@code removeFromWorld(boolean)} and any other method on the class are left alone. That
 * single method body contains exactly one {@code ArrayList.remove(Object)} call (the {@code
 * cell.getStaticUpdaterObjectList().remove(this)} at decompiled line 4451), so name + signature
 * alone uniquely identifies the substitution target inside the scoped body.
 */
public class IsoObjectStaticUpdaterRemoveSubstPatch extends StormClassTransformer {

    private static final String HELPER_TYPE = "io.pzstorm.storm.iso.StormCellMembership";
    private static final String HELPER_METHOD = "removeStaticUpdaterFromList";

    public IsoObjectStaticUpdaterRemoveSubstPatch() {
        super("zombie.iso.IsoObject");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        TypeDescription helper = typePool.describe(HELPER_TYPE).resolve();
        MethodDescription replacement =
                helper.getDeclaredMethods().filter(ElementMatchers.named(HELPER_METHOD)).getOnly();
        TypeDescription arrayList = typePool.describe("java.util.ArrayList").resolve();
        TypeDescription objectType = typePool.describe("java.lang.Object").resolve();

        return builder.visit(
                MemberSubstitution.relaxed()
                        .method(
                                ElementMatchers.<MethodDescription>named("remove")
                                        .and(ElementMatchers.takesArguments(1))
                                        .and(ElementMatchers.takesArgument(0, objectType))
                                        .and(ElementMatchers.returns(boolean.class))
                                        .and(ElementMatchers.isDeclaredBy(arrayList)))
                        .replaceWith(replacement)
                        .on(
                                ElementMatchers.named("removeFromWorld")
                                        .and(ElementMatchers.takesArguments(0))));
    }
}
