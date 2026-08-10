package io.pzstorm.storm.patch.fixes;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Fixes the class of NPE crashes caused by squares holding a stale reference to a torn-down
 * player-built room. Observed live as a client kick-to-menu while building a plank floor on a
 * second storey:
 *
 * <pre>NullPointerException: Cannot invoke "zombie.iso.RoomDef.getArea()" because the return
 *     value of "zombie.iso.areas.IsoRoom.getRoomDef()" is null
 *     at ParameterFirearmRoomSize.getRoomSize
 *     at FMODParameter.update ... IsoPlayer.update ... IngameState.updateInternal</pre>
 *
 * <p>Root mechanism: every build change makes {@code WorldRegionToMetaGrid} tear down and rebuild
 * all user-defined buildings in the cell. The teardown ({@code removeIsoRoom}) guts the live {@code
 * IsoRoom} in place ({@code def = null}, building/squares/rects cleared) while {@code
 * IsoGridSquare.room} references to it survive: the re-stamp sweep ({@code updateSquares}) only
 * covers dirty chunks in current players' chunk maps, the room's {@code squares} back-ref list is
 * only populated on chunk load, and room IDs are renumbered by list index each rebuild. Any square
 * the sweep misses keeps {@code roomId != -1} plus the gutted husk, and {@code getRoom()} serves it
 * to every consumer; those that skip vanilla's own {@code hasRoomDef()}-style null check (e.g.
 * {@code ParameterFirearmRoomSize}, recomputed every tick for the local player) NPE, and the catch
 * at {@code IngameState.updateInternal} exits the world.
 *
 * <p>The patch guards the single chokepoint all consumers use: {@code getRoom()} returns {@code
 * null} for a gutted room, routing callers onto their vanilla no-room paths — which for
 * player-built rooms is the {@code IsoWorldRegion.isPlayerRoom()} fallback vanilla wrote for
 * exactly this case (so e.g. the firearm-audio room size becomes the region's real square count,
 * not a placeholder). Registered on both JVMs; the stale-reference state can occur wherever the
 * region rebuild runs.
 */
public class IsoGridSquareGetRoomNullDefGuardPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.isogridsquaregetroomnulldefguard.";

    public IsoGridSquareGetRoomNullDefGuardPatch() {
        super("zombie.iso.IsoGridSquare");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(
                                typePool.describe(PKG + "IsoGridSquareGetRoomNullDefGuardAdvice")
                                        .resolve(),
                                locator)
                        .on(
                                ElementMatchers.named("getRoom")
                                        .and(ElementMatchers.takesArguments(0))));
    }
}
