package io.pzstorm.storm.advice.weatherfxmask;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import net.bytebuddy.asm.Advice;

/**
 * Advice for {@code zombie.iso.weather.fx.WeatherFxMask.scanForTiles(int)}.
 *
 * <p>Vanilla rasterizes two triangles covering the entire visible viewport every render frame,
 * calling {@code addMaskLocation} on every enumerated tile. {@code addMaskLocation} already
 * short-circuits internally when {@code PlayerFxMask.requiresUpdate} is false, but the outer
 * scanline iteration and per-tile {@code chunkMap.getGridSquare} still execute. Live-client
 * sampling attributed 3.35% of MainThread to the {@code addMaskLocation} enumeration path and an
 * additional 2.43% to the {@code TLongObjectHashMap.get} inside {@code getMask} for a combined
 * 5.78% cluster.
 *
 * <p>{@code requiresUpdate} only transitions to true when the player's grid square changes (see
 * {@code PlayerFxMask.initMask}). When it is false the entire scan is guaranteed to produce zero
 * new mask entries — the existing {@code maskHashMap} from the prior frame is already valid for
 * {@code drawFxMask} to render from. Skipping the scan is behavior-preserving.
 *
 * <p>Reflection is cached at advice class initialization; the runtime path is a static array read +
 * one field access, both JIT-inlined after warm-up.
 */
public class WeatherFxScanSkipAdvice {

    public static final Field REQUIRES_UPDATE_FIELD;
    public static final Field PLAYER_MASKS_FIELD;

    static {
        try {
            Class<?> inner = Class.forName("zombie.iso.weather.fx.WeatherFxMask$PlayerFxMask");
            REQUIRES_UPDATE_FIELD = inner.getDeclaredField("requiresUpdate");
            REQUIRES_UPDATE_FIELD.setAccessible(true);
            Class<?> outer = Class.forName("zombie.iso.weather.fx.WeatherFxMask");
            PLAYER_MASKS_FIELD = outer.getDeclaredField("playerMasks");
            PLAYER_MASKS_FIELD.setAccessible(true);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    @Advice.OnMethodEnter(skipOn = Advice.OnDefaultValue.class)
    public static boolean onEnter(@Advice.Argument(0) int nPlayer) {
        try {
            Object playerMasks = PLAYER_MASKS_FIELD.get(null);
            if (playerMasks == null || nPlayer < 0 || nPlayer >= Array.getLength(playerMasks)) {
                return true;
            }
            Object pfm = Array.get(playerMasks, nPlayer);
            if (pfm == null) {
                return true;
            }
            boolean requiresUpdate = REQUIRES_UPDATE_FIELD.getBoolean(pfm);
            return requiresUpdate;
        } catch (Throwable t) {
            return true;
        }
    }
}
