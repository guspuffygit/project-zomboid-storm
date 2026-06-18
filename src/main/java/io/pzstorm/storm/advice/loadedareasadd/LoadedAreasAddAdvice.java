package io.pzstorm.storm.advice.loadedareasadd;

import io.pzstorm.storm.patch.performance.StormCellWarmer;
import io.pzstorm.storm.patch.performance.StormCellWarmingConfig;
import java.lang.reflect.Field;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;
import zombie.popman.LoadedAreas;

/**
 * Filters warm cells out of {@link LoadedAreas#add(int, int, int, int)} when called on the
 * server-cells instance. That instance is {@code ZombiePopulationManager.loadedServerCells}, fed to
 * the native zombie pop manager each tick via {@code n_loadedAreas(..., true)}. Without this gate,
 * warm cells stay reported as "loaded server cells" to the native side every tick — wasted work and
 * misleading admin debug overlays.
 *
 * <p>The serverCells branch in {@code LoadedAreas.set()} always invokes {@code add(wx*8, wy*8, 8,
 * 8)}, so {@code wx = x >> 3, wy = y >> 3}. The player-driven / connectArea branches use the other
 * instance (serverCells=false) and are left untouched — players need their relevant areas reported
 * even when those areas overlap warm cells.
 *
 * <p>Server-only via {@code GameServer.server} gate.
 */
public class LoadedAreasAddAdvice {

    private static final Field SERVER_CELLS_FIELD;

    static {
        try {
            Field f = LoadedAreas.class.getDeclaredField("serverCells");
            f.setAccessible(true);
            SERVER_CELLS_FIELD = f;
        } catch (NoSuchFieldException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static int onEnter(
            @Advice.This LoadedAreas la, @Advice.Argument(0) int x, @Advice.Argument(1) int y) {
        if (!GameServer.server) {
            return 0;
        }
        if (!StormCellWarmingConfig.isEnabled()) {
            return 0;
        }
        boolean serverCells;
        try {
            serverCells = SERVER_CELLS_FIELD.getBoolean(la);
        } catch (IllegalAccessException e) {
            return 0;
        }
        if (!serverCells) {
            return 0;
        }
        if (StormCellWarmer.isWarm(x >> 3, y >> 3)) {
            return 1;
        }
        return 0;
    }
}
