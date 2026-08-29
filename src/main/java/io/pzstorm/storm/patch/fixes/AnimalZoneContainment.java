package io.pzstorm.storm.patch.fixes;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.metrics.StormPerformanceSandboxMetrics;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import zombie.characters.animals.IsoAnimal;
import zombie.characters.animals.datas.AnimalData;
import zombie.core.random.Rand;
import zombie.iso.IsoGridSquare;
import zombie.iso.areas.DesignationZoneAnimal;

/**
 * Pure logic behind {@link AnimalZoneContainmentPatch}: makes a player-placed animal zone an actual
 * boundary instead of a wander hint, so livestock stops chewing through pen walls and wandering
 * off.
 *
 * <h2>The bug this heals</h2>
 *
 * <p>{@code DesignationZoneAnimal} (the "animal zone" a player paints around a pen) never
 * constrains an animal's movement in vanilla. It appears in exactly one place that affects
 * behavior: {@code BaseAnimalBehavior.wanderIdle()} re-rolls the random wander target until it
 * lands in <i>some</i> animal zone — and that bias is skipped entirely once hunger or thirst
 * reaches 0.9, gives up and uses the out-of-zone target after 100 failed re-rolls, and tests "any
 * zone" rather than the animal's own connected zone. Nothing else — pathfinding, collision, states
 * — knows the zone exists.
 *
 * <p>The escapes themselves come from the pathfinder. {@code
 * IsoAnimal.shouldBreakObstaclesDuringPathfinding()} returns true once hunger or thirst passes 0.8
 * (for any animal whose definition has {@code canThump}, which is the default), which sets {@code
 * PathFindRequest.canThump} and therefore {@code PMMover.canThump}. In {@code
 * VGAStar.canNotMoveBetween} the animal branch then consults {@code canAnimalBreakObstacle}, which
 * lets the path cross any edge that carries both a collide bit and a can-path bit. {@code
 * SquareUpdateTask.hasSquareThumpable}/{@code hasWallThumpableN|W} set exactly those bits for
 * player-built {@code IsoThumpable} walls — so a hungry animal plans a route <i>through</i> the pen
 * wall. {@code animalShouldThump()} (hunger/thirst &gt;= 0.9 after a thump delay, or stress &gt;=
 * 80, or having been attacked) plus {@code tryThump} then hand the wall to {@code
 * AnimalAttackState}, which damages it via {@code IsoThumpable.animalHit} — a method that
 * force-sets {@code isThumpable} to true, so even structures flagged unbreakable take the damage —
 * destroys it at zero health and repaths through the hole. Vanilla map walls carry no can-path
 * bits, which is why this reads as "animals only escape the pens players build".
 *
 * <h2>The fix</h2>
 *
 * <p>Three boundary advices on {@code IsoAnimal}, all keyed on the animal being <i>contained</i> —
 * standing in an animal zone, or a non-wild animal that has strayed within {@link
 * #getLeashDistance()} tiles of one:
 *
 * <ul>
 *   <li>{@code shouldBreakObstaclesDuringPathfinding()} returns false, so the pathfinder never
 *       routes a contained animal through a thumpable wall in the first place.
 *   <li>{@code animalShouldThump()} returns false, so a contained animal never picks a pen wall,
 *       fence or gate as a thump target. The vanilla body still runs, so its {@code thumpDelay}
 *       bookkeeping is unchanged and turning containment off restores vanilla timing immediately.
 *   <li>{@code pathToLocation(x, y, z)} has its target clamped into the animal's own connected zone
 *       whenever it points outside — closing all three holes in {@code wanderIdle}'s bias at once,
 *       and doubling as the way home for an animal that is already out.
 * </ul>
 *
 * <p>Everything a zone points animals at is inside the zone by construction ({@code zone.troughs},
 * {@code zone.foodOnGround}, and {@code nearWaterSquares}, which is itself filtered on {@code
 * getZone(sq) == this}), so clamping does not cut a contained animal off from its food or water.
 * Food-luring is unaffected because it moves the animal through {@code pathToCharacter}, not {@code
 * pathToLocation}; a player's {@code callOut} will bring contained animals to the near edge of the
 * zone rather than out of it.
 *
 * <p>The trade the fix makes deliberately: a contained animal that runs out of food and water will
 * now stay in its pen and starve instead of eating the wall and leaving. That is the point of the
 * boundary, and {@code Storm.AnimalZoneContainment} turns it off for admins who disagree.
 *
 * <p>Any throw latches {@link #broken} and every entry point becomes a no-op — vanilla behavior
 * returns and the server keeps running.
 */
public final class AnimalZoneContainment {

    public static final boolean DEFAULT_ENABLED = true;

    /**
     * How far outside an animal zone a non-wild animal is still treated as belonging to it, in
     * tiles. 0 means only animals actually standing in a zone are contained.
     */
    public static final int DEFAULT_LEASH_DISTANCE = 20;

    public static final int MIN_LEASH_DISTANCE = 0;
    public static final int MAX_LEASH_DISTANCE = 200;

    /** Returned by {@link #clampTarget} when the caller's target must be left alone. */
    public static final long NO_CLAMP = Long.MIN_VALUE;

    /** Attempts to find a free square inside the zone before giving up on a clamp. */
    private static final int FREE_SQUARE_TRIES = 16;

    private static volatile boolean enabled = DEFAULT_ENABLED;
    private static volatile int leashDistance = DEFAULT_LEASH_DISTANCE;

    /** Permanent fail-soft latch: any throw reverts to vanilla animal behavior. */
    private static volatile boolean broken;

    private AnimalZoneContainment() {}

    /**
     * Applies the {@code Storm.AnimalZoneContainment} sandbox option and pushes the applied value
     * to the Prometheus gauge. Single mutation point — sandbox apply and tests both funnel through
     * here. Safe to flip live in either direction; the advices are stateless.
     */
    public static boolean setEnabled(boolean value) {
        enabled = value;
        StormPerformanceSandboxMetrics.setAnimalZoneContainment(value);
        return value;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * Applies the {@code Storm.AnimalZoneLeashDistance} sandbox option and pushes the applied value
     * to the Prometheus gauge.
     *
     * @return the applied (clamped) value
     */
    public static int setLeashDistance(int tiles) {
        int clamped = Math.max(MIN_LEASH_DISTANCE, Math.min(MAX_LEASH_DISTANCE, tiles));
        leashDistance = clamped;
        StormPerformanceSandboxMetrics.setAnimalZoneLeashDistance(clamped);
        return clamped;
    }

    public static int getLeashDistance() {
        return leashDistance;
    }

    /** Test hook: clears the fail-soft latch. */
    public static void resetBroken() {
        broken = false;
    }

    /**
     * Exit driver for {@code IsoAnimal.shouldBreakObstaclesDuringPathfinding()} and {@code
     * IsoAnimal.animalShouldThump()}: a contained animal never gets permission to break through an
     * obstacle. Parameter typed {@code Object} so the inlined advice does not embed a checkcast
     * against a game class.
     *
     * @param vanilla what the vanilla method decided
     * @return {@code vanilla}, or false when the animal is contained
     */
    public static boolean allowObstacleBreaking(Object animalRef, boolean vanilla) {
        if (broken || !enabled || !vanilla) {
            return vanilla;
        }
        try {
            return !isContained((IsoAnimal) animalRef);
        } catch (Throwable t) {
            handle(t);
            return vanilla;
        }
    }

    /**
     * Enter driver for {@code IsoAnimal.pathToLocation(int, int, int)}: keeps a contained animal's
     * path target inside its own zone.
     *
     * @return {@link #NO_CLAMP} to leave the target alone, otherwise the replacement target packed
     *     with {@link #pack(int, int)}
     */
    public static long clampTarget(Object animalRef, int x, int y, int z) {
        if (broken || !enabled) {
            return NO_CLAMP;
        }
        try {
            IsoAnimal animal = (IsoAnimal) animalRef;
            ArrayList<DesignationZoneAnimal> zones = animal.getConnectedDZone();
            if (zones.isEmpty()) {
                DesignationZoneAnimal home = strayHomeZone(animal);
                return home == null ? NO_CLAMP : clampInto(animal, home, x, y, z);
            }
            // Vanilla's wanderIdle accepts any zone anywhere; only the animal's own connected
            // zone group counts as inside.
            if (zones.contains(DesignationZoneAnimal.getZone(x, y, z))) {
                return NO_CLAMP;
            }
            long best = NO_CLAMP;
            long bestDist = Long.MAX_VALUE;
            for (int i = 0; i < zones.size(); i++) {
                long packed = clampInto(animal, zones.get(i), x, y, z);
                if (packed == NO_CLAMP) {
                    continue;
                }
                long dist = squaredDistance(unpackX(packed), unpackY(packed), x, y);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = packed;
                }
            }
            return best;
        } catch (Throwable t) {
            handle(t);
            return NO_CLAMP;
        }
    }

    public static long pack(int x, int y) {
        return ((long) x << 32) | (y & 0xFFFFFFFFL);
    }

    public static int unpackX(long packed) {
        return (int) (packed >> 32);
    }

    public static int unpackY(long packed) {
        return (int) packed;
    }

    /**
     * Pure geometry: Chebyshev distance from a point to a zone rectangle, 0 when the point is
     * inside it. Zone rectangles cover {@code [x, x + w)} by {@code [y, y + h)}.
     */
    public static int rectDistance(int zoneX, int zoneY, int w, int h, int x, int y) {
        int dx = Math.max(Math.max(zoneX - x, x - (zoneX + w - 1)), 0);
        int dy = Math.max(Math.max(zoneY - y, y - (zoneY + h - 1)), 0);
        return Math.max(dx, dy);
    }

    /**
     * An animal is contained when it is standing in an animal zone, or when it is a stray that
     * still belongs to one. Wild animals are never contained, so deer and rabbits keep vanilla
     * behavior next to a player's pen.
     */
    private static boolean isContained(IsoAnimal animal) {
        return !animal.getConnectedDZone().isEmpty() || strayHomeZone(animal) != null;
    }

    /**
     * The zone a non-wild animal standing outside every zone still belongs to: the nearest animal
     * zone on its own floor within {@link #getLeashDistance()} tiles. Animals a player is leading
     * or that are tied to a tree are exempt — those are being moved on purpose.
     */
    private static DesignationZoneAnimal strayHomeZone(IsoAnimal animal) {
        int radius = leashDistance;
        if (radius <= 0 || animal.isWild()) {
            return null;
        }
        AnimalData data = animal.getData();
        if (data == null || data.getAttachedPlayer() != null || data.getAttachedTree() != null) {
            return null;
        }
        int x = (int) animal.getX();
        int y = (int) animal.getY();
        int z = (int) animal.getZ();
        ArrayList<DesignationZoneAnimal> zones = DesignationZoneAnimal.getAllZones();
        DesignationZoneAnimal best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (int i = 0; i < zones.size(); i++) {
            DesignationZoneAnimal zone = zones.get(i);
            if (zone == null || zone.z != z) {
                continue;
            }
            int distance = rectDistance(zone.x, zone.y, zone.w, zone.h, x, y);
            if (distance <= radius && distance < bestDistance) {
                bestDistance = distance;
                best = zone;
            }
        }
        return best;
    }

    /**
     * Nearest usable square inside {@code zone} to the requested target: the target clamped to the
     * zone rectangle when that square is walkable, otherwise a random free square in the zone.
     * Returns {@link #NO_CLAMP} when the zone sits on another floor or has no reachable square
     * loaded, in which case the caller's original target stands — a wall the animal can no longer
     * break is what stops it either way.
     */
    private static long clampInto(
            IsoAnimal animal, DesignationZoneAnimal zone, int x, int y, int z) {
        if (zone == null || zone.z != z || zone.w <= 0 || zone.h <= 0) {
            return NO_CLAMP;
        }
        int cx = Math.max(zone.x, Math.min(zone.x + zone.w - 1, x));
        int cy = Math.max(zone.y, Math.min(zone.y + zone.h - 1, y));
        if (isWalkable(animal, cx, cy, z)) {
            return pack(cx, cy);
        }
        for (int i = 0; i < FREE_SQUARE_TRIES; i++) {
            int rx = Rand.Next(zone.x, zone.x + zone.w);
            int ry = Rand.Next(zone.y, zone.y + zone.h);
            if (isWalkable(animal, rx, ry, z)) {
                return pack(rx, ry);
            }
        }
        return NO_CLAMP;
    }

    private static boolean isWalkable(IsoAnimal animal, int x, int y, int z) {
        IsoGridSquare square =
                animal.getCell() == null ? null : animal.getCell().getGridSquare(x, y, z);
        return square != null && square.isFree(true);
    }

    private static long squaredDistance(int x1, int y1, int x2, int y2) {
        long dx = (long) x1 - x2;
        long dy = (long) y1 - y2;
        return dx * dx + dy * dy;
    }

    /**
     * Fail-soft policy. The zone list and an animal's connected-zone list are plain {@code
     * ArrayList}s owned by the server main thread, and {@code
     * shouldBreakObstaclesDuringPathfinding()} is read while a path request is being built — a
     * transient index race there must cost one skipped decision, not permanently disable
     * containment, so those two exception types fall through to vanilla for that call only.
     * Anything else means the fix itself is wrong about the game and latches off for good.
     */
    private static void handle(Throwable t) {
        if (t instanceof IndexOutOfBoundsException
                || t instanceof ConcurrentModificationException) {
            return;
        }
        broken = true;
        LOGGER.error("Storm: animal zone containment failed; reverting to vanilla behavior", t);
    }
}
