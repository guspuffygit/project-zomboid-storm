package io.pzstorm.storm.entity;

import io.pzstorm.storm.logging.StormLogger;
import io.pzstorm.storm.metrics.FluidContainerUpdateMetrics;
import io.pzstorm.storm.metrics.StormPerformanceSandboxMetrics;
import java.lang.reflect.Field;
import zombie.SandboxOptions;
import zombie.core.utils.UpdateLimit;
import zombie.entity.ComponentType;
import zombie.entity.EntityBucket;
import zombie.entity.EntitySimulation;
import zombie.entity.GameEntity;
import zombie.entity.components.fluids.Fluid;
import zombie.entity.components.fluids.FluidContainer;
import zombie.entity.components.fluids.FluidContainerUpdateSystem;
import zombie.entity.components.fluids.FluidType;
import zombie.entity.util.ImmutableArray;
import zombie.iso.IsoObject;
import zombie.iso.weather.ClimateManager;

/**
 * Server-only replacement for the body of {@code FluidContainerUpdateSystem.updateSimulation()},
 * wired in by {@code FluidContainerUpdateSimulationFastPathPatch}.
 *
 * <p>Vanilla walks every registered FluidContainer entity on every 100ms simulation tick (plus
 * uncapped catch-up ticks) and, per entity, re-reads {@code
 * ClimateManager.getInstance().getPrecipitationIntensity()} / {@code getPrecipitationIsSnow()} and
 * {@code SandboxOptions.getDayLengthMinutes()}, and runs {@code getPrimaryFluid()} (a fluid-list
 * scan) plus {@code getFluidTypeString().equals("Petrol")} before the rain branch's cheap guards.
 * Only the network sync is throttled ({@code objectSyncLimiter}, 1000ms) — the scan itself is not.
 * This replacement applies exactly three safe transformations:
 *
 * <ol>
 *   <li><b>Hoists per-call invariants out of the per-entity loop:</b> precipitation intensity,
 *       precipitation-is-snow (folded into {@code snowModifier}), the day-length divisor, and the
 *       game-seconds-per-tick factor. All are provably invariant within one {@code
 *       updateSimulation()} call: {@code ClimateManager}'s precipitation fields are written only by
 *       its own {@code update()} step and admin commands, both of which run on the server main
 *       thread outside {@code GameEntityManager.Update}; sandbox options change only via an admin
 *       push processed outside engine update; {@code EntitySimulation.getGameSecondsPerTick()}
 *       returns a compile-time constant. Nothing reachable from {@code adjustAmount}/{@code
 *       addFluid}/{@code IsoObject.sync} writes any of them.
 *   <li><b>Reorders per-entity checks cheapest-first.</b> The {@code canPlayerEmpty() &&
 *       getRainCatcher() > 0} conjunction is the shared prefix of both vanilla branches (petrol
 *       evaporation and rain fill), so entities failing it skip everything, including the {@code
 *       getPrimaryFluid()} list scan. When hoisted precipitation is zero the rain branch is skipped
 *       without the per-entity {@code isOutside()}/{@code isMultiTileMoveable()} reads. Every
 *       skipped vanilla expression ({@code isOutside}, {@code isMultiTileMoveable}, {@code
 *       canPlayerEmpty}, {@code getRainCatcher}, {@code getPrecipitationIntensity}) is a
 *       side-effect-free read, and for entities that do qualify, every state change ({@code
 *       adjustAmount}, {@code addFluid}, {@code sync}) runs in the exact vanilla order: petrol
 *       branch first, then rain branch.
 *   <li><b>Replaces the {@code "Petrol"} string comparison with an enum identity compare.</b>
 *       {@code getPrimaryFluid().getFluidType() == FluidType.Petrol} is exactly equivalent to
 *       vanilla's {@code getPrimaryFluid().getFluidTypeString().equals("Petrol")}: a builtin {@code
 *       Fluid}'s {@code fluidTypeStr} is {@code fluidType.toString()} (the enum name — {@code
 *       FluidType} does not override {@code toString}), and a modded fluid can never carry the
 *       string {@code "Petrol"} because {@code FluidDefinitionScript} maps any name matching a
 *       builtin case-insensitively to the builtin enum instead of {@code FluidType.Modded}. Null
 *       handling matches too: both dereference the {@code getPrimaryFluid()} result unconditionally
 *       (non-null here because the {@code !isEmpty()} guard precedes it, as in vanilla).
 * </ol>
 *
 * <p>{@code objectSyncLimiter.Check()} is stateful (it advances the limiter window), so it is
 * called exactly once per call, unconditionally, exactly as vanilla does — including when the
 * entity list is empty. Vanilla's {@code !GameClient.client} gate is subsumed by the advice's
 * {@code GameServer.server} guard (this never runs on a client JVM).
 *
 * <p>Vanilla behavior is restored wholesale with the {@code Storm.FluidContainerUpdateFastPath}
 * sandbox option (set {@code false}, live-appliable), and permanently if the optimized pass ever
 * throws.
 *
 * <p>Single-threaded by design: {@code updateSimulation()} only runs on the server main thread
 * (engine update inside {@code GameEntityManager.Update}), so the metric tallies and the failure
 * latch need no synchronization.
 */
public final class StormFluidContainerUpdate {

    /** Default for {@code Storm.FluidContainerUpdateFastPath}: fast path on. */
    public static final boolean DEFAULT_ENABLED = true;

    /**
     * Kill switch, driven by the {@code Storm.FluidContainerUpdateFastPath} sandbox option through
     * {@link #setEnabled(boolean)}. Volatile because the sandbox applier may push updates from
     * outside the main thread; the per-call read is a single volatile load.
     */
    private static volatile boolean enabled = DEFAULT_ENABLED;

    /** Permanent revert-to-vanilla latch; set on the first {@link Throwable} out of the body. */
    private static boolean failed;

    private static volatile boolean initialized;

    // FluidContainerUpdateSystem internals (objectSyncLimiter is package-private, the bucket is
    // private; same reflection bridge pattern as StormPlayerLos).
    private static Field fObjectSyncLimiter;
    private static Field fFluidContainerEntities;

    private StormFluidContainerUpdate() {}

    /**
     * Applies the {@code Storm.FluidContainerUpdateFastPath} sandbox option ({@code false} =
     * vanilla pass, {@code true} = hoisted/reordered pass) and pushes the applied value to the
     * Prometheus gauge. Single mutation point — sandbox apply and tests both funnel through here.
     *
     * @return the applied value
     */
    public static boolean setEnabled(boolean value) {
        enabled = value;
        StormPerformanceSandboxMetrics.setFluidContainerUpdateFastPath(value);
        return value;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * Runs the hoisted, reordered {@code updateSimulation()} body for {@code systemObj}.
     *
     * @param systemObj the {@code FluidContainerUpdateSystem} ({@code @Advice.This}; typed {@code
     *     Object} so the advice never references the transform target)
     * @return {@code true} if the optimized pass ran (the advice skips the vanilla body); {@code
     *     false} to fall through to vanilla (kill switch off or failure latch tripped)
     */
    public static boolean runOptimized(Object systemObj) {
        if (failed || !enabled) {
            FluidContainerUpdateMetrics.recordVanilla();
            return false;
        }
        try {
            ensureInit();
            FluidContainerUpdateSystem system = (FluidContainerUpdateSystem) systemObj;
            UpdateLimit objectSyncLimiter = (UpdateLimit) fObjectSyncLimiter.get(system);
            EntityBucket fluidContainerEntities =
                    (EntityBucket) fFluidContainerEntities.get(system);
            run(objectSyncLimiter, fluidContainerEntities);
            return true;
        } catch (Throwable t) {
            failed = true;
            StormLogger.LOGGER.error(
                    "StormFluidContainerUpdate failed — reverting to vanilla"
                            + " FluidContainerUpdateSystem.updateSimulation",
                    t);
            FluidContainerUpdateMetrics.recordVanilla();
            return false;
        }
    }

    /**
     * The hoisted/reordered equivalent of vanilla {@code updateSimulation()} + {@code
     * updateEntity(...)}. Every kept expression mirrors its vanilla counterpart in order; comments
     * name what vanilla does at each seam.
     */
    private static void run(UpdateLimit objectSyncLimiter, EntityBucket fluidContainerEntities) {
        // Stateful: advances the 1000ms sync window. Exactly one call per pass, like vanilla
        // (vanilla calls it before the entity-count check).
        boolean doSync = objectSyncLimiter.Check();
        ImmutableArray<GameEntity> entities = fluidContainerEntities.getEntities();
        long shortCircuited = 0;
        long worked = 0;
        if (entities.size() != 0) {
            // Hoisted per-call invariants — see class doc for the loop-invariance argument.
            ClimateManager climate = ClimateManager.getInstance();
            float precipitationIntensity = climate.getPrecipitationIntensity();
            boolean raining = precipitationIntensity > 0.0F;
            float snowModifier = climate.getPrecipitationIsSnow() ? 0.5F : 1.0F;
            // Left-associated exactly like vanilla's
            // 0.005F * intensity * snowModifier * rainCatcher * (float) secondsPerTick.
            float rainAmountBase = 0.005F * precipitationIntensity * snowModifier;
            float gameSecondsPerTick = (float) EntitySimulation.getGameSecondsPerTick();
            // Integer arithmetic preserved from vanilla (getDayLengthMinutes() returns int).
            int dayLengthDivisor = SandboxOptions.getInstance().getDayLengthMinutes() * 24 / 60;

            for (int i = 0; i < entities.size(); i++) {
                GameEntity entity = entities.get(i);
                // Vanilla isValidEntity(entity) — inlined (it is private on the system).
                if (!entity.isEntityValid() || !entity.isValidEngineEntity()) {
                    continue;
                }
                FluidContainer fluidContainer = entity.getComponent(ComponentType.FluidContainer);
                if (!fluidContainer.isValid()
                        || (!entity.isMeta() && !fluidContainer.isQualifiesForMetaStorage())) {
                    continue;
                }

                // ==== vanilla updateEntity(entity, fluidContainer, doSync), reordered ====
                // Shared prefix of both vanilla branches; both are pure reads, stable within
                // the call (owner and rainCatcher are untouched by adjustAmount/addFluid/sync),
                // so evaluating them once up front is outcome-identical.
                if (!fluidContainer.canPlayerEmpty()) {
                    shortCircuited++;
                    continue;
                }
                float rainCatcher = fluidContainer.getRainCatcher();
                if (!(rainCatcher > 0.0F)) {
                    shortCircuited++;
                    continue;
                }

                boolean didFluidWork = false;

                // Petrol evaporation branch — vanilla condition order preserved:
                // canPlayerEmpty && rainCatcher > 0 (above) && !isEmpty() && primary is Petrol.
                if (!fluidContainer.isEmpty()) {
                    didFluidWork = true;
                    Fluid primaryFluid = fluidContainer.getPrimaryFluid();
                    // Identity compare, exactly equivalent to vanilla's
                    // getFluidTypeString().equals("Petrol") — see class doc.
                    if (primaryFluid.getFluidType() == FluidType.Petrol) {
                        float amount = 1.0E-4F * rainCatcher / dayLengthDivisor;
                        if (fluidContainer.getAmount() < amount) {
                            amount = fluidContainer.getAmount();
                        }
                        fluidContainer.adjustAmount(fluidContainer.getAmount() - amount);
                        if (doSync && entity instanceof IsoObject isoObject) {
                            isoObject.sync();
                        }
                    }
                }

                // Rain-fill branch — vanilla condition order preserved (precipitation first,
                // then isOutside, then !isMultiTileMoveable); the trailing canPlayerEmpty &&
                // rainCatcher > 0 conjuncts are already known true from the shared prefix.
                if (raining
                        && fluidContainer.getGameEntity().isOutside()
                        && !fluidContainer.isMultiTileMoveable()) {
                    didFluidWork = true;
                    FluidType waterType =
                            fluidContainer.isFilledWithCleanWater()
                                    ? FluidType.Water
                                    : FluidType.TaintedWater;
                    if (fluidContainer.canAddFluid(Fluid.Get(waterType))) {
                        float rainAmount = rainAmountBase * rainCatcher * gameSecondsPerTick;
                        if (fluidContainer.getFreeCapacity() < rainAmount) {
                            fluidContainer.adjustAmount(fluidContainer.getCapacity() - rainAmount);
                        }
                        fluidContainer.addFluid(waterType, rainAmount);
                        if (doSync && entity instanceof IsoObject isoObject) {
                            isoObject.sync();
                        }
                    }
                }

                if (didFluidWork) {
                    worked++;
                } else {
                    shortCircuited++;
                }
            }
        }
        FluidContainerUpdateMetrics.recordOptimized(shortCircuited, worked);
    }

    private static void ensureInit() throws ReflectiveOperationException {
        if (initialized) {
            return;
        }
        synchronized (StormFluidContainerUpdate.class) {
            if (initialized) {
                return;
            }
            fObjectSyncLimiter =
                    FluidContainerUpdateSystem.class.getDeclaredField("objectSyncLimiter");
            fFluidContainerEntities =
                    FluidContainerUpdateSystem.class.getDeclaredField("fluidContainerEntities");
            fObjectSyncLimiter.setAccessible(true);
            fFluidContainerEntities.setAccessible(true);
            initialized = true;
            StormLogger.LOGGER.info(
                    "StormFluidContainerUpdate: FluidContainerUpdateSystem reflection bridge"
                            + " initialized");
        }
    }
}
