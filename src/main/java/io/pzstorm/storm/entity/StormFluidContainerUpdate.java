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
 * This replacement applies exactly five safe transformations:
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
 *       side-effect-free read, and for entities that do qualify, every fluid mutation ({@code
 *       adjustAmount}, {@code addFluid}) runs in the exact vanilla order: petrol branch first, then
 *       rain branch.
 *   <li><b>Evaluates that shared prefix before the entity/component validity gates.</b> Live
 *       profiling (ATF 2026-08-24, 112 players) attributed ~2% of the main thread to the per-entity
 *       validity machinery ({@code isEntityValid}/{@code isValidEngineEntity}/{@code
 *       Component.isValid} and the meta gate) running for every registered fluid container — nearly
 *       all of which (water bottles, jerrycans) then exit on {@code rainCatcher == 0}. The validity
 *       gates are themselves pure reads that only decide whether {@code updateEntity} runs, and an
 *       entity failing the shared prefix does no observable work in vanilla regardless of its
 *       validity, so hoisting the prefix above them is outcome-identical; the component is fetched
 *       first ({@code getComponent} is null-safe on any entity) and a null component — impossible
 *       for an entity vanilla's gates would pass — skips like an invalid one. For prefix-passing
 *       candidates the vanilla gate order resumes unchanged, with the meta gate reduced to {@code
 *       isValid()} because {@code isQualifiesForMetaStorage()} is exactly {@code getRainCatcher() >
 *       0}, already established true.
 *   <li><b>Replaces the {@code "Petrol"} string comparison with an enum identity compare.</b>
 *       {@code getPrimaryFluid().getFluidType() == FluidType.Petrol} is exactly equivalent to
 *       vanilla's {@code getPrimaryFluid().getFluidTypeString().equals("Petrol")}: a builtin {@code
 *       Fluid}'s {@code fluidTypeStr} is {@code fluidType.toString()} (the enum name — {@code
 *       FluidType} does not override {@code toString}), and a modded fluid can never carry the
 *       string {@code "Petrol"} because {@code FluidDefinitionScript} maps any name matching a
 *       builtin case-insensitively to the builtin enum instead of {@code FluidType.Modded}. Null
 *       handling matches too: both dereference the {@code getPrimaryFluid()} result unconditionally
 *       (non-null here because the {@code !isEmpty()} guard precedes it, as in vanilla).
 *   <li><b>Coalesces the per-branch network sync into one deferred send.</b> Vanilla calls {@code
 *       sync()} inside both the petrol and the rain branch, so an entity that hits both in the same
 *       sync-window pass broadcasts its full state twice back to back. The replacement sets a flag
 *       in each branch and sends once after both — every SyncIsoObject packet serializes the
 *       object's <i>current</i> full state, so a single post-mutation send delivers exactly the
 *       state clients would hold after vanilla's second packet. The send still goes through {@code
 *       IsoObject.sync()}, which {@code IsoObjectSyncGatePatch} relevancy-gates per connection (see
 *       {@code StormSyncIsoObjectGate}) — off-range connections stop paying for rain-barrel ticks
 *       entirely.
 * </ol>
 *
 * <p>{@code objectSyncLimiter.Check()} is stateful (it advances the limiter window), so it is
 * called exactly once per call, unconditionally, exactly as vanilla does — including when the
 * entity list is empty. Vanilla's {@code !GameClient.client} gate is subsumed by the advice's
 * {@code GameServer.server} guard (this never runs on a client JVM).
 *
 * <p>On top of the per-entity transformations, the pass itself is <b>strided</b> ({@code
 * -Dstorm.fluid.simStride=N}, default {@value #DEFAULT_SIM_STRIDE}, {@code 1} = every pass): only
 * every Nth {@code updateSimulation()} call walks the bucket, with every per-pass fluid delta
 * multiplied by the number of coalesced passes. This is outcome-equivalent because both mutations
 * are linear in the per-pass delta and clamped at the ends ({@code adjustAmount} clamps to {@code
 * [0, capacity]}, the private {@code addFluid} clamps to free capacity), so N unit steps and one
 * N-scaled step land on the same amount (in the already-full-in-rain case the clean/tainted mix
 * converges along one N-sized displacement step instead of N unit steps — second-order on a delta
 * of hundredths of a litre); the branch predicates and {@code isFilledWithCleanWater} are sampled
 * at N&times;100ms instead of 100ms granularity, so a rain start/stop or an indoors/outdoors move
 * can be integrated up to N&minus;1 passes early or late — bounded by N&times;100ms of a fill rate
 * that is a few hundredths of a litre per pass (ATF profile 2026-08-27: the every-pass walk of the
 * full bucket was 2.31% of main, almost all of it entities that end up doing no work). The 1000ms
 * sync limiter is checked once per walking pass; {@code UpdateLimit.Check} is wall-clock-windowed,
 * so a sync fires on the first walking pass after the window elapses — at most N&minus;1 deferred
 * passes later than vanilla.
 *
 * <p>Vanilla behavior is restored wholesale with the {@code Storm.FluidContainerUpdateFastPath}
 * sandbox option (set {@code false}, live-appliable), and permanently if the optimized pass ever
 * throws. Either revert drops at most the currently deferred N&minus;1 passes (&le; N&times;100ms
 * of fluid time) before vanilla cadence resumes.
 *
 * <p>Single-threaded by design: {@code updateSimulation()} only runs on the server main thread
 * (engine update inside {@code GameEntityManager.Update}), so the metric tallies and the failure
 * latch need no synchronization.
 */
public final class StormFluidContainerUpdate {

    /** Default for {@code Storm.FluidContainerUpdateFastPath}: fast path on. */
    public static final boolean DEFAULT_ENABLED = true;

    /** Default for {@code -Dstorm.fluid.simStride}: walk the bucket every 5th 100ms pass. */
    public static final int DEFAULT_SIM_STRIDE = 5;

    /** Passes per bucket walk; {@code 1} restores a walk on every {@code updateSimulation()}. */
    public static final int SIM_STRIDE =
            Math.max(1, Integer.getInteger("storm.fluid.simStride", DEFAULT_SIM_STRIDE));

    /**
     * Calls deferred since the last walking pass. Main-thread only, like the failure latch;
     * deliberately not reset when the kill switch or failure latch reverts to vanilla — the
     * deferred passes are dropped (≤ (N−1)×100ms of fluid time) rather than replayed.
     */
    private static int passesSinceWalk;

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
            if (++passesSinceWalk < SIM_STRIDE) {
                FluidContainerUpdateMetrics.recordDeferred();
                return true;
            }
            int coalescedPasses = passesSinceWalk;
            passesSinceWalk = 0;
            ensureInit();
            FluidContainerUpdateSystem system = (FluidContainerUpdateSystem) systemObj;
            UpdateLimit objectSyncLimiter = (UpdateLimit) fObjectSyncLimiter.get(system);
            EntityBucket fluidContainerEntities =
                    (EntityBucket) fFluidContainerEntities.get(system);
            run(objectSyncLimiter, fluidContainerEntities, coalescedPasses);
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
     * The hoisted/reordered equivalent of {@code coalescedPasses} consecutive vanilla {@code
     * updateSimulation()} + {@code updateEntity(...)} calls, applied as one walk with every fluid
     * delta scaled by {@code coalescedPasses} (see the stride paragraph in the class doc). Every
     * kept expression mirrors its vanilla counterpart in order; comments name what vanilla does at
     * each seam.
     */
    private static void run(
            UpdateLimit objectSyncLimiter,
            EntityBucket fluidContainerEntities,
            int coalescedPasses) {
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
                // Most-selective prefilter first — see class doc item 3. Every expression here
                // through the validity gates is a side-effect-free read, and an entity failing
                // the shared branch prefix (canPlayerEmpty() && getRainCatcher() > 0) does no
                // observable work in vanilla either, so evaluating the prefix before the
                // validity machinery is outcome-identical. Almost every fluid container on a
                // live server (water bottles, jerrycans, ...) exits on the rainCatcher check.
                FluidContainer fluidContainer = entity.getComponent(ComponentType.FluidContainer);
                if (fluidContainer == null) {
                    // Unreachable when vanilla's validity gates would pass (bucket membership
                    // implies the component); vanilla would NPE here otherwise.
                    shortCircuited++;
                    continue;
                }
                float rainCatcher = fluidContainer.getRainCatcher();
                if (!(rainCatcher > 0.0F) || !fluidContainer.canPlayerEmpty()) {
                    shortCircuited++;
                    continue;
                }

                // Vanilla isValidEntity(entity) — inlined (it is private on the system). Only
                // rain-catcher candidates pay for these now.
                if (!entity.isEntityValid() || !entity.isValidEngineEntity()) {
                    continue;
                }
                // Vanilla's meta gate (!isMeta() && !isQualifiesForMetaStorage()) is subsumed:
                // isQualifiesForMetaStorage() is getRainCatcher() > 0, already known true.
                if (!fluidContainer.isValid()) {
                    continue;
                }

                boolean didFluidWork = false;
                boolean needSync = false;

                // Petrol evaporation branch — vanilla condition order preserved:
                // canPlayerEmpty && rainCatcher > 0 (above) && !isEmpty() && primary is Petrol.
                if (!fluidContainer.isEmpty()) {
                    didFluidWork = true;
                    Fluid primaryFluid = fluidContainer.getPrimaryFluid();
                    // Identity compare, exactly equivalent to vanilla's
                    // getFluidTypeString().equals("Petrol") — see class doc.
                    if (primaryFluid.getFluidType() == FluidType.Petrol) {
                        // Vanilla per-pass delta × coalesced passes; clamped below like vanilla,
                        // so N unit steps and one N-scaled step drain to the same amount.
                        float amount = 1.0E-4F * rainCatcher / dayLengthDivisor * coalescedPasses;
                        if (fluidContainer.getAmount() < amount) {
                            amount = fluidContainer.getAmount();
                        }
                        fluidContainer.adjustAmount(fluidContainer.getAmount() - amount);
                        if (doSync) {
                            needSync = true;
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
                        // Vanilla per-pass delta × coalesced passes; addFluid clamps to free
                        // capacity, so the N-scaled fill tops out where N unit fills would.
                        float rainAmount =
                                rainAmountBase * rainCatcher * gameSecondsPerTick * coalescedPasses;
                        if (fluidContainer.getFreeCapacity() < rainAmount) {
                            fluidContainer.adjustAmount(fluidContainer.getCapacity() - rainAmount);
                        }
                        fluidContainer.addFluid(waterType, rainAmount);
                        if (doSync) {
                            needSync = true;
                        }
                    }
                }

                // Coalesced sync — one full-state send after the last mutation instead of
                // vanilla's per-branch sends; see class doc item 5.
                if (needSync && entity instanceof IsoObject isoObject) {
                    isoObject.sync();
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
