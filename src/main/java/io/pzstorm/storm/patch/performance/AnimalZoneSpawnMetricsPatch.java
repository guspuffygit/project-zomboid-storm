package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import io.pzstorm.storm.metrics.AnimalSpawnMetrics;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Counts migration-zone animal spawns ({@code pz_animal_zone_spawn_total}, {@code
 * pz_animal_zone_spawn_animals_total}). Observation only — no behaviour change.
 *
 * <p>The static initializer loads {@link AnimalSpawnMetrics} so its scrape-time gauges are
 * registered at server startup rather than at the first spawn, which on a fully explored map may
 * never happen. Registration is gated server-only in {@code StormClassTransformers}, so this class
 * is not touched on a client JVM.
 *
 * @see AnimalSpawnMetrics for what the whole metric family means
 */
public class AnimalZoneSpawnMetricsPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.animalspawn.";

    static {
        AnimalSpawnMetrics.ensureStarted();
    }

    public AnimalZoneSpawnMetricsPatch() {
        super("zombie.characters.animals.AnimalZones");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(PKG + "AnimalZoneSpawnAdvice").resolve(), locator)
                        .on(
                                ElementMatchers.named("spawnAnimalsOnZone")
                                        .and(ElementMatchers.takesArguments(1))));
    }
}
