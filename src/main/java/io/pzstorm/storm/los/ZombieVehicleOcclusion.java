package io.pzstorm.storm.los;

import io.pzstorm.storm.logging.StormLogger;
import io.pzstorm.storm.metrics.StormPerformanceSandboxMetrics;
import java.util.ArrayList;
import org.joml.Vector3f;
import zombie.GameTime;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoZombie;
import zombie.core.math.PZMath;
import zombie.core.physics.WorldSimulation;
import zombie.iso.IsoChunk;
import zombie.iso.Vector2;
import zombie.network.ServerMap;
import zombie.scripting.objects.VehicleScript;
import zombie.vehicles.BaseVehicle;

/**
 * Optimized replacement for {@code IsoZombie.isVehicleBetween} (the zombie-spots-player vehicle
 * occlusion test), wired in by {@code ZombieVehicleOcclusionPatch}.
 *
 * <p>Vanilla iterates every vehicle in the loaded cell per sight test and runs an OBB/segment
 * intersection on each — two world-transform inversions per vehicle — even when the result is
 * provably unused. This replacement:
 *
 * <ol>
 *   <li>returns {@code false} outright when vanilla would ignore the result: the target sits in a
 *       vehicle ({@code carObstacleMod} is 1.0 then), or the spot {@code chance} is already zero at
 *       the call site (target beyond {@code visionRadiusResult} Manhattan range, or behind the
 *       zombie with {@code cosAngle < -0.4}) and the call is not a forced spot;
 *   <li>otherwise scans only vehicles registered in the chunks overlapping the sight segment's AABB
 *       (zombie vision is hard-capped at 20 tiles) instead of the whole cell, rejects each on a
 *       cheap center-vs-segment distance bound derived from its script extents, and only runs the
 *       vanilla-exact {@link BaseVehicle#getIntersectPoint} on survivors.
 * </ol>
 *
 * <p>Known approximations, all bounded by the probabilistic nature of the spot roll: a vehicle
 * whose chunk registration lags its physics transform by a tick (very fast movers) can be missed
 * for that tick, and modded vehicles scaled beyond a ~8-tile footprint diagonal exceed {@link
 * #WINDOW_INFLATE} and may be skipped. Vanilla behavior is restored wholesale with the {@code
 * Storm.ZombieSightVehicleFastPath} sandbox option (set {@code false}), or automatically if the
 * fast path ever throws.
 *
 * <p>Single-threaded by design: {@code spottedNew} only runs on the server main thread, so the
 * capture fields and scratch vector need no synchronization.
 */
public final class ZombieVehicleOcclusion {

    public static final int RESULT_VANILLA = 0;
    public static final int RESULT_FALSE = 1;
    public static final int RESULT_TRUE = 2;

    /** Default for {@code Storm.ZombieSightVehicleFastPath}: fast path on. */
    public static final boolean DEFAULT_ENABLED = true;

    /**
     * Kill switch, driven by the {@code Storm.ZombieSightVehicleFastPath} sandbox option through
     * {@link #setEnabled(boolean)}. Volatile because the sandbox applier may push updates from
     * outside the main thread; the per-call read is a single volatile load.
     */
    private static volatile boolean enabled = DEFAULT_ENABLED;

    /**
     * The {@code other} / {@code bForced} arguments of the enclosing {@code spottedNew} call,
     * captured by {@code SpottedNewCaptureAdvice} ({@code isVehicleBetween} itself only receives
     * coordinates). Cleared on {@code spottedNew} exit so a disconnected player's object graph is
     * not pinned. Public because the inlined advice bytecode accesses them from {@code
     * zombie.characters.IsoZombie}.
     */
    public static Object spottedTarget;

    public static boolean spottedForced;

    /**
     * How far (in tiles) the sight segment's AABB is inflated before choosing which chunks to scan.
     * Must exceed the largest vehicle's reach: max vanilla script extents give ~3.2 tiles from
     * transform origin to the farthest OBB corner (L1 bound), plus {@link #REACH_EPSILON}.
     */
    private static final float WINDOW_INFLATE = 4.0f;

    /** Slack added to each vehicle's computed reach for float rounding and origin drift. */
    private static final float REACH_EPSILON = 0.25f;

    private static final int CHUNK_SIZE = 8;

    /** Scratch for {@link IsoGameCharacter#getLookVector}; main thread only. */
    private static final Vector2 LOOK = new Vector2();

    private static boolean failed;

    private ZombieVehicleOcclusion() {}

    /**
     * Applies the {@code Storm.ZombieSightVehicleFastPath} sandbox option ({@code false} = vanilla
     * scan, {@code true} = fast path) and pushes the applied value to the Prometheus gauge. Single
     * mutation point — sandbox apply and tests both funnel through here.
     *
     * @return the applied value
     */
    public static boolean setEnabled(boolean value) {
        enabled = value;
        StormPerformanceSandboxMetrics.setZombieSightVehicleFastPath(value);
        return value;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * Decides the outcome of an {@code isVehicleBetween(targetX, targetY, targetZ)} call.
     *
     * @return {@link #RESULT_FALSE}/{@link #RESULT_TRUE} to skip the vanilla body with that result,
     *     or {@link #RESULT_VANILLA} to fall through to the vanilla scan.
     */
    public static int evaluate(Object zombieObj, float targetX, float targetY, float targetZ) {
        if (!enabled || failed) {
            return RESULT_VANILLA;
        }
        try {
            IsoZombie zombie = (IsoZombie) zombieObj;
            float zombieX = zombie.getX();
            float zombieY = zombie.getY();

            Object targetObj = spottedTarget;
            if (targetObj instanceof IsoGameCharacter) {
                IsoGameCharacter target = (IsoGameCharacter) targetObj;
                // Sanity-check the capture against the actual arguments so a future call-site
                // change degrades to the pure-geometry path instead of misapplying guards.
                if (target.getX() == targetX && target.getY() == targetY) {
                    if (target.getVehicle() != null) {
                        // Vanilla: carObstacleMod is 1.0 whenever the target is in a vehicle,
                        // so the intersection result is unused.
                        return RESULT_FALSE;
                    }
                    if (!spottedForced
                            && chanceAlreadyZero(zombie, zombieX, zombieY, targetX, targetY)) {
                        return RESULT_FALSE;
                    }
                }
            }
            return scan(zombieX, zombieY, zombie.getZ(), targetX, targetY, targetZ);
        } catch (Throwable t) {
            failed = true;
            StormLogger.LOGGER.error(
                    "ZombieVehicleOcclusion failed — reverting to vanilla isVehicleBetween", t);
            return RESULT_VANILLA;
        }
    }

    /**
     * Replicates the two {@code chance = 0.0F} assignments that precede the vehicle test in {@code
     * spottedNew}: Manhattan distance beyond {@code visionRadiusResult} (fresh — vanilla calls
     * {@code updateVisionRadius()} earlier in the same invocation), and target behind the zombie
     * ({@code cosAngle < -0.4} when the effective view distance exceeds 0.5). {@code chance} is
     * only ever multiplied after those points (barring {@code bForced}, which the caller checks),
     * so a zero here makes the occlusion result unused.
     */
    private static boolean chanceAlreadyZero(
            IsoZombie zombie, float zombieX, float zombieY, float targetX, float targetY) {
        float dx = targetX - zombieX;
        float dy = targetY - zombieY;
        if (Math.abs(dx) + Math.abs(dy) > zombie.visionRadiusResult) {
            return true;
        }
        float length = (float) Math.sqrt(dx * dx + dy * dy);
        float viewDist = GameTime.getInstance().getViewDist();
        if (length < viewDist) {
            viewDist = length;
        }
        viewDist *= 1.1F;
        if (viewDist > GameTime.getInstance().getViewDistMax()) {
            viewDist = GameTime.getInstance().getViewDistMax();
        }
        if (viewDist > 0.5F && length > 0.0F) {
            Vector2 look = zombie.getLookVector(LOOK);
            float cosAngle = (look.x * dx + look.y * dy) / length;
            if (cosAngle < -0.4F) {
                return true;
            }
        }
        return false;
    }

    private static int scan(
            float zombieX,
            float zombieY,
            float zombieZ,
            float targetX,
            float targetY,
            float targetZ) {
        ServerMap map = ServerMap.instance;
        if (map == null) {
            return RESULT_VANILLA;
        }
        float segMinX = Math.min(zombieX, targetX);
        float segMaxX = Math.max(zombieX, targetX);
        float segMinY = Math.min(zombieY, targetY);
        float segMaxY = Math.max(zombieY, targetY);
        int chunkMinX = Math.floorDiv(PZMath.fastfloor(segMinX - WINDOW_INFLATE), CHUNK_SIZE);
        int chunkMaxX = Math.floorDiv(PZMath.fastfloor(segMaxX + WINDOW_INFLATE), CHUNK_SIZE);
        int chunkMinY = Math.floorDiv(PZMath.fastfloor(segMinY - WINDOW_INFLATE), CHUNK_SIZE);
        int chunkMaxY = Math.floorDiv(PZMath.fastfloor(segMaxY + WINDOW_INFLATE), CHUNK_SIZE);
        // Vehicle centers are read from jniTransform (physics frame) — the same source
        // getIntersectPoint transforms against — so the reject bound is frame-exact.
        float offsetX = WorldSimulation.instance.offsetX;
        float offsetY = WorldSimulation.instance.offsetY;

        Vector3f start = null;
        Vector3f end = null;
        Vector3f hit = null;
        boolean intersects = false;

        outer:
        for (int chunkY = chunkMinY; chunkY <= chunkMaxY; chunkY++) {
            for (int chunkX = chunkMinX; chunkX <= chunkMaxX; chunkX++) {
                IsoChunk chunk = map.getChunk(chunkX, chunkY);
                if (chunk == null) {
                    continue;
                }
                ArrayList<BaseVehicle> vehicles = chunk.vehicles;
                for (int i = 0; i < vehicles.size(); i++) {
                    BaseVehicle vehicle = vehicles.get(i);
                    if (vehicle == null) {
                        continue;
                    }
                    float reach = WINDOW_INFLATE;
                    VehicleScript script = vehicle.getScript();
                    if (script != null) {
                        Vector3f extents = script.getExtents();
                        Vector3f massOffset = script.getCenterOfMassOffset();
                        // L1 bound on the farthest OBB corner from the transform origin —
                        // getIntersectPoint's half-extents are extents*0.5 + centerOfMassOffset.
                        reach =
                                Math.abs(extents.x * 0.5f + massOffset.x)
                                        + Math.abs(extents.y * 0.5f + massOffset.y)
                                        + Math.abs(extents.z * 0.5f + massOffset.z)
                                        + REACH_EPSILON;
                    }
                    float vehicleX = vehicle.jniTransform.origin.x + offsetX;
                    float vehicleY = vehicle.jniTransform.origin.z + offsetY;
                    if (vehicleX < segMinX - reach
                            || vehicleX > segMaxX + reach
                            || vehicleY < segMinY - reach
                            || vehicleY > segMaxY + reach) {
                        continue;
                    }
                    if (distanceToSegmentSquared(
                                    vehicleX, vehicleY, targetX, targetY, zombieX, zombieY)
                            > reach * reach) {
                        continue;
                    }
                    if (start == null) {
                        start = BaseVehicle.allocVector3f();
                        end = BaseVehicle.allocVector3f();
                        hit = BaseVehicle.allocVector3f();
                        start.set(targetX, targetY, targetZ + 0.1F);
                        end.set(zombieX, zombieY, zombieZ + 0.1F);
                    }
                    if (vehicle.getIntersectPoint(start, end, hit) != null) {
                        intersects = true;
                        break outer;
                    }
                }
            }
        }
        if (start != null) {
            BaseVehicle.releaseVector3f(start);
            BaseVehicle.releaseVector3f(end);
            BaseVehicle.releaseVector3f(hit);
        }
        return intersects ? RESULT_TRUE : RESULT_FALSE;
    }

    private static float distanceToSegmentSquared(
            float pointX, float pointY, float x1, float y1, float x2, float y2) {
        float segDx = x2 - x1;
        float segDy = y2 - y1;
        float lengthSquared = segDx * segDx + segDy * segDy;
        float t = 0.0f;
        if (lengthSquared > 0.0f) {
            t = ((pointX - x1) * segDx + (pointY - y1) * segDy) / lengthSquared;
            t = Math.max(0.0f, Math.min(1.0f, t));
        }
        float closestX = x1 + t * segDx;
        float closestY = y1 + t * segDy;
        float dx = pointX - closestX;
        float dy = pointY - closestY;
        return dx * dx + dy * dy;
    }
}
