package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Replaces the server-side body of {@code FluidContainerUpdateSystem.updateSimulation()} with the
 * hoisted, reordered pass in {@code StormFluidContainerUpdate}. Live profiling (79 players)
 * attributed ~6% inclusive of the server main thread to this method — every registered
 * FluidContainer entity, every 100ms simulation tick (plus uncapped catch-up ticks), pays a
 * fluid-list scan ({@code getPrimaryFluid()}) and a {@code "Petrol"} string compare before the
 * cheap rain guard, and re-reads climate and sandbox state per entity.
 *
 * <p>Server-only by registration gate ({@code StormEnv.isStormServer()}) — {@code
 * FluidContainerUpdateSystem} is loaded by clients too. No stopwatch patch exists on this method
 * (the enclosing {@code Engine.updateSimulation} timing patch targets a different class), so
 * ordering against other transformers is unconstrained.
 *
 * <p>Kill switch: the {@code Storm.FluidContainerUpdateFastPath} sandbox option ({@code false}
 * restores the vanilla pass; live-appliable via admin sandbox push). The fast path also permanently
 * reverts to vanilla if it ever throws.
 */
public class FluidContainerUpdateSimulationFastPathPatch extends StormClassTransformer {

    private static final String TARGET =
            "zombie.entity.components.fluids.FluidContainerUpdateSystem";
    private static final String PKG = "io.pzstorm.storm.advice.fluidcontainerupdate.";

    public FluidContainerUpdateSimulationFastPathPatch() {
        super(TARGET);
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        TypeDescription target = typePool.describe(TARGET).resolve();
        if (target.getDeclaredMethods()
                .filter(
                        ElementMatchers.named("updateSimulation")
                                .and(ElementMatchers.takesArguments(0)))
                .isEmpty()) {
            throw new IllegalStateException(
                    "FluidContainerUpdateSimulationFastPathPatch: FluidContainerUpdateSystem no"
                            + " longer declares updateSimulation() — the name-string hook would"
                            + " silently no-op and reintroduce the per-entity climate/sandbox"
                            + " re-reads and fluid-list scans. Re-verify the patch against the"
                            + " current game source.");
        }
        return builder.visit(
                Advice.to(
                                typePool.describe(
                                                PKG
                                                        + "FluidContainerUpdateSimulationFastPathAdvice")
                                        .resolve(),
                                locator)
                        .on(
                                ElementMatchers.named("updateSimulation")
                                        .and(ElementMatchers.takesArguments(0))));
    }
}
