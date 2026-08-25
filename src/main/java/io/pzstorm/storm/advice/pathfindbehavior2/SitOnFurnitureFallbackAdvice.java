package io.pzstorm.storm.advice.pathfindbehavior2;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import gnu.trove.list.array.TFloatArrayList;
import net.bytebuddy.asm.Advice;
import zombie.core.math.PZMath;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.pathfind.PolygonalMap2;
import zombie.vehicles.BaseVehicle;

/**
 * Advice for {@code PathFindBehavior2.pathToSitOnFurnitureNoSpriteGrid(IsoObject,
 * TFloatArrayList)}.
 *
 * <p>Chair sprites carry {@code solidtrans}, so a chair's own square is never standable. Vanilla
 * therefore pushes each seating position out of the chair square onto one of three neighbouring
 * tiles — Front, Left and Right relative to the chair's seating direction — and adds those
 * positions to {@code locations} <em>without</em> checking that anything can stand on them. A chair
 * with a table in front and chairs on both sides has all three neighbours blocked, so the A* is
 * handed nothing but unreachable targets, fails, and {@code
 * ISWorldObjectContextMenu.onRestPathFailed} drops the player on the floor instead of into the
 * seat.
 *
 * <p>This advice appends the chair's remaining walkable orthogonal neighbours (in practice the tile
 * <em>behind</em> the chair) as extra targets, but only when every position vanilla produced is
 * unreachable. {@code ISRestAction:waitToStart} picks the nearest seating side from wherever the
 * character ends up and {@code PlayerSitOnFurnitureState.enter} snaps them onto the seat, so any
 * walkable tile beside the chair is a good enough approach.
 *
 * <p>Fails soft: any throwable is logged and vanilla behaviour is left untouched.
 */
public class SitOnFurnitureFallbackAdvice {

    /** The flag set {@code IsoGameCharacter.canStandAt} passes to {@link PolygonalMap2}. */
    public static final int CAN_STAND_FLAGS = 17;

    /** Matches the 0.3 tile inset vanilla uses when it pushes a seat position out of its square. */
    public static final float EDGE_INSET = 0.3F;

    @Advice.OnMethodExit
    public static void onExit(
            @Advice.Argument(0) Object furniture, @Advice.Argument(1) Object locations) {
        SitOnFurnitureFallbackAdvice.addFallbackApproaches(furniture, locations);
    }

    /**
     * Appends the walkable orthogonal neighbours of {@code furniture}'s square to {@code locations}
     * when nothing already in the list can be stood on.
     *
     * <p>Both parameters are typed as {@link Object} so the inlined advice body carries no
     * reference to either PZ type; the casts below are real {@code checkcast} instructions.
     */
    public static void addFallbackApproaches(Object furnitureObj, Object locationsObj) {
        try {
            TFloatArrayList locations = (TFloatArrayList) locationsObj;
            // An empty list means the sprite has no seating data at all — vanilla deliberately
            // falls through to sitting on the ground there, so leave it alone.
            if (locations == null || locations.isEmpty()) {
                return;
            }
            IsoObject furniture = (IsoObject) furnitureObj;
            IsoGridSquare seat = furniture == null ? null : furniture.getSquare();
            if (seat == null) {
                return;
            }
            for (int i = 0; i + 2 < locations.size(); i += 3) {
                if (canStandAt(locations.get(i), locations.get(i + 1), locations.get(i + 2))) {
                    return;
                }
            }
            float x = seat.getX();
            float y = seat.getY();
            int z = seat.getZ();
            addIfReachable(locations, seat, x + 0.5F, y - EDGE_INSET, z);
            addIfReachable(locations, seat, x + 0.5F, y + 1.0F + EDGE_INSET, z);
            addIfReachable(locations, seat, x - EDGE_INSET, y + 0.5F, z);
            addIfReachable(locations, seat, x + 1.0F + EDGE_INSET, y + 0.5F, z);
        } catch (Throwable t) {
            LOGGER.error("SitOnFurnitureBoxedInChairPatch failed, falling back to vanilla", t);
        }
    }

    private static void addIfReachable(
            TFloatArrayList locations, IsoGridSquare seat, float x, float y, int z) {
        IsoGridSquare adjacent =
                seat.getCell().getGridSquare(PZMath.fastfloor(x), PZMath.fastfloor(y), z);
        if (!isGoodChairAdjacentSquare(seat, adjacent) || !canStandAt(x, y, z)) {
            return;
        }
        locations.add(x);
        locations.add(y);
        locations.add(z);
    }

    /**
     * Reimplements {@code PathFindBehavior2.isGoodChairAdjacentSquare} rather than calling it —
     * reaching back into the transform target from an advice body invites a {@code
     * ClassCircularityError}.
     */
    private static boolean isGoodChairAdjacentSquare(IsoGridSquare seat, IsoGridSquare adjacent) {
        return adjacent != null
                && !adjacent.isSolid()
                && !adjacent.isSolidTrans()
                && (!adjacent.getProperties().has(IsoFlagType.water)
                        || adjacent.hasFloorOverWater())
                && adjacent.canReachTo(seat);
    }

    private static boolean canStandAt(float x, float y, float z) {
        return PolygonalMap2.instance.canStandAt(
                x, y, PZMath.fastfloor(z), (BaseVehicle) null, CAN_STAND_FLAGS);
    }
}
