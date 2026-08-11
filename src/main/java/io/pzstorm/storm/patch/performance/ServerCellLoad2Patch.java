package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Times {@code ServerCell.Load2} and carries the two hydration stamps behind {@code
 * storm_chunk_hydration_cell_duration_seconds}.
 *
 * <p>The stamps are written from advices on {@code ServerChunkLoader} but read here, from a method
 * of the class that owns them, so the read side can use {@code @Advice.FieldValue} and skip
 * reflection entirely. They are defined on this transformer rather than one of the three other
 * {@code ServerCell} patches because this is where they are consumed — and because {@code
 * ServerCellUnloadPatch} is registered ungated, which would put server-only bookkeeping on client
 * bytecode.
 */
public class ServerCellLoad2Patch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.servercellload2.";

    public ServerCellLoad2Patch() {
        super("zombie.network.ServerMap$ServerCell");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.defineField("storm$hydrateStartNanos", long.class, Visibility.PUBLIC)
                .defineField("storm$recalcStartNanos", long.class, Visibility.PUBLIC)
                .visit(
                        Advice.to(
                                        typePool.describe(PKG + "ServerCellLoad2Advice").resolve(),
                                        locator)
                                .on(
                                        ElementMatchers.named("Load2")
                                                .and(ElementMatchers.takesArguments(0))));
    }
}
