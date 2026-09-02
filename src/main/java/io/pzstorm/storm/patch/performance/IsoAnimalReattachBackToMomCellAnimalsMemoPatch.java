package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.MemberSubstitution;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Substitutes the {@code this.getCell().getAnimals()} call inside {@code
 * IsoAnimal.reattachBackToMom()} with {@link ReattachCellAnimalsMemo#animalsThisTick(Object)}, so
 * every orphaned animal searching for its mother in the same tick shares one walk of the cell's
 * moving-object set instead of each doing its own. The {@code DesignationZoneAnimal.getAnimals()}
 * calls in the same method are a different declaring type and are left alone.
 *
 * <p>Scoped to the one method via {@code MemberSubstitution.on(...)}; {@code IsoCell.getAnimals()}
 * itself is unchanged for every other caller (it is public API used from Lua). The matcher names
 * {@code zombie.iso.IsoCell} as a string so registering this patch does not load the class before
 * its own transformers are in place.
 *
 * <p>Server-only. Companion to {@code IsoCellGetAnimalsPatch}, which made the list an {@code
 * ArrayList} so the indexed scan in {@code findMotherAndAttach} stopped being O(n²).
 */
public class IsoAnimalReattachBackToMomCellAnimalsMemoPatch extends StormClassTransformer {

    public IsoAnimalReattachBackToMomCellAnimalsMemoPatch() {
        super("zombie.characters.animals.IsoAnimal");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        try {
            return builder.visit(
                    MemberSubstitution.relaxed()
                            .method(
                                    ElementMatchers.named("getAnimals")
                                            .and(ElementMatchers.takesArguments(0))
                                            .and(
                                                    ElementMatchers.isDeclaredBy(
                                                            ElementMatchers.named(
                                                                    "zombie.iso.IsoCell"))))
                            .replaceWith(
                                    ReattachCellAnimalsMemo.class.getDeclaredMethod(
                                            "animalsThisTick", Object.class))
                            .on(
                                    ElementMatchers.named("reattachBackToMom")
                                            .and(ElementMatchers.takesArguments(0))));
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(
                    "Failed to setup MemberSubstitution for IsoAnimal.reattachBackToMom", e);
        }
    }
}
