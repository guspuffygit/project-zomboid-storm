package io.pzstorm.storm.advice.isogridsquaregetroomnulldefguard;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import java.util.concurrent.atomic.AtomicBoolean;
import net.bytebuddy.asm.Advice;
import zombie.iso.areas.IsoRoom;

/**
 * Advice for {@code IsoGridSquare.getRoom()} that treats a gutted {@code IsoRoom} — one whose
 * {@code def} was nulled by {@code WorldRegionToMetaGrid.removeIsoRoom} during a player-built-room
 * rebuild — as "no room" and returns {@code null} instead of the husk.
 *
 * <p>Every build change re-creates all user-defined buildings in the cell; the teardown guts the
 * live {@code IsoRoom} in place while squares outside the dirty-chunk re-stamp sweep keep a cached
 * reference to it. Returning {@code null} here routes every consumer onto its vanilla no-room path
 * (which for player-built rooms falls back to the {@code IsoWorldRegion} player-room branch), the
 * same semantics vanilla's own {@code hasRoomDef()} check encodes.
 *
 * <p>Hot path: two field reads and a branch per call, no allocation. No lambdas / streams &mdash;
 * advice bodies are inlined into the target method and must be plain imperative Java. Static fields
 * referenced from the body must be {@code public}.
 */
public class IsoGridSquareGetRoomNullDefGuardAdvice {

    /**
     * Once-per-session log latch: getRoom() is called many times per frame, so an unthrottled warn
     * would flood the log while a stale square exists.
     */
    public static final AtomicBoolean WARNED = new AtomicBoolean();

    @Advice.OnMethodExit
    public static void onExit(@Advice.Return(readOnly = false) IsoRoom returned) {
        if (returned != null && returned.def == null) {
            if (WARNED.compareAndSet(false, true)) {
                LOGGER.warn(
                        "IsoGridSquareGetRoomNullDefGuardPatch: square holds a torn-down IsoRoom"
                                + " (null RoomDef, stale after player-room rebuild);"
                                + " treating as no room");
            }
            returned = null;
        }
    }
}
