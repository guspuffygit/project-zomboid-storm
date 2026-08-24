package io.pzstorm.storm.vehicles;

import io.pzstorm.storm.logging.StormLogger;
import java.util.ArrayList;
import zombie.characters.IsoGameCharacter;
import zombie.core.math.PZMath;
import zombie.iso.IsoChunk;
import zombie.network.ServerMap;
import zombie.vehicles.BaseVehicle;

/**
 * Optimized replacement for {@code IsoGameCharacter.checkIsNearVehicle}, wired in by {@code
 * IsoGameCharacterCheckIsNearVehiclePatch}.
 *
 * <p>Vanilla iterates every vehicle in the loaded cell (the whole server world) per player per
 * tick. Its only side effect — setting the {@code "nearWallCrouching"} animation variable — is
 * gated on {@code this.sneaking}, and both call sites ({@code IsoPlayer.updateInternal}) discard
 * the return value. So:
 *
 * <ol>
 *   <li>for a non-sneaking character the whole scan is a provable no-op and is skipped outright
 *       (byte-identical behavior);
 *   <li>for a sneaking character only the chunks within reach are scanned — the same chunk window
 *       and the same {@code DistTo < 3.5} (Manhattan) predicate as vanilla's own chunk-local
 *       template, {@code IsoPlayer.isNearVehicle}. A vehicle within 3.5 tiles is registered at most
 *       one chunk away, so the window is a superset of vanilla's hit set; the only divergence is a
 *       vehicle whose chunk registration lags its position by more than a chunk, which vanilla's
 *       own interact path already tolerates.
 * </ol>
 *
 * <p>Return-value divergence (non-sneakers always get {@code false}, and the sneaking scan may
 * visit vehicles in a different order) is unobservable: no call site reads the return.
 *
 * <p>Always on; vanilla behavior is restored automatically and permanently if the fast path ever
 * throws.
 */
public final class StormCheckIsNearVehicle {

    public static final int RESULT_VANILLA = 0;
    public static final int RESULT_FALSE = 1;
    public static final int RESULT_TRUE = 2;

    private static boolean failed;

    private StormCheckIsNearVehicle() {}

    /**
     * Decides the outcome of a {@code checkIsNearVehicle()} call.
     *
     * @return {@link #RESULT_FALSE}/{@link #RESULT_TRUE} to skip the vanilla body with that result,
     *     or {@link #RESULT_VANILLA} to fall through to the vanilla whole-cell scan.
     */
    public static int evaluate(Object characterObj) {
        if (failed) {
            return RESULT_VANILLA;
        }
        try {
            IsoGameCharacter character = (IsoGameCharacter) characterObj;
            if (!character.isSneaking()) {
                return RESULT_FALSE;
            }
            return sneakingScan(character);
        } catch (Throwable t) {
            failed = true;
            StormLogger.LOGGER.error(
                    "StormCheckIsNearVehicle failed — reverting to vanilla checkIsNearVehicle", t);
            return RESULT_VANILLA;
        }
    }

    private static int sneakingScan(IsoGameCharacter character) {
        ServerMap map = ServerMap.instance;
        if (map == null) {
            return RESULT_VANILLA;
        }
        float x = character.getX();
        float y = character.getY();
        // Same window arithmetic as vanilla IsoPlayer.isNearVehicle: ±4 tiles plus a one-chunk
        // margin comfortably covers the 3.5-tile Manhattan reach of the vanilla predicate.
        int chunkMinX = (PZMath.fastfloor(x) - 4) / 8 - 1;
        int chunkMinY = (PZMath.fastfloor(y) - 4) / 8 - 1;
        int chunkMaxX = (int) Math.ceil((x + 4.0F) / 8.0F) + 1;
        int chunkMaxY = (int) Math.ceil((y + 4.0F) / 8.0F) + 1;
        for (int chunkY = chunkMinY; chunkY < chunkMaxY; chunkY++) {
            for (int chunkX = chunkMinX; chunkX < chunkMaxX; chunkX++) {
                IsoChunk chunk = map.getChunk(chunkX, chunkY);
                if (chunk == null) {
                    continue;
                }
                ArrayList<BaseVehicle> vehicles = chunk.vehicles;
                for (int i = 0; i < vehicles.size(); i++) {
                    BaseVehicle vehicle = vehicles.get(i);
                    if (vehicle != null && vehicle.DistTo(character) < 3.5F) {
                        character.setVariable("nearWallCrouching", true);
                        return RESULT_TRUE;
                    }
                }
            }
        }
        return RESULT_FALSE;
    }
}
