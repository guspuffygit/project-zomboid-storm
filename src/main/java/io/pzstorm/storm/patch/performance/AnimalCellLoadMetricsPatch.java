package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import io.pzstorm.storm.metrics.AnimalSpawnMetrics;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Counts animal-cell loads split by whether the cell had a save file ({@code
 * pz_animal_cell_load_total}). A cell loaded fresh is the only trigger for migration-zone spawning,
 * so this series is the upstream answer to "why did nothing spawn". Observation only.
 *
 * <p>Matches the no-argument {@code load()}, not the {@code load(String)} / {@code
 * load(ByteBuffer)} overloads.
 *
 * @see AnimalSpawnMetrics
 */
public class AnimalCellLoadMetricsPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.animalspawn.";

    public AnimalCellLoadMetricsPatch() {
        super("zombie.characters.animals.AnimalCell");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(PKG + "AnimalCellLoadAdvice").resolve(), locator)
                        .on(
                                ElementMatchers.named("load")
                                        .and(ElementMatchers.takesArguments(0))
                                        .and(ElementMatchers.returns(void.class))));
    }
}
