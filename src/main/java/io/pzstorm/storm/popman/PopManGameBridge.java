package io.pzstorm.storm.popman;

import io.pzstorm.storm.logging.StormLogger;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * What the population simulation takes from the rest of the port: the collision map as its world,
 * the save directory the game state names, the collision map's path queue, and the shared game
 * state. Nothing here touches a game class — every input arrived through {@code MapCollisionData}'s
 * own natives before {@code ZombiePopulationManager.n_init} ran, exactly as the DLL had it.
 */
public final class PopManGameBridge implements PopManCore.Environment {

    private final PopManCore core;
    private final PopManMapCollision collision;
    private final PopManGameState state;

    public PopManGameBridge(PopManCore core, PopManMapCollision collision, PopManGameState state) {
        this.core = core;
        this.collision = collision;
        this.state = state;
    }

    @Override
    public PopManWorld world() {
        return collision.grid();
    }

    /** {@code GameModeCacheDir + GameSaveWorld}; the store appends the {@code zpop} folder. */
    @Override
    public Path saveDirectory() {
        return Paths.get(state.gameModeCacheDir + state.gameSaveWorld);
    }

    @Override
    public PopManGameState gameState() {
        return state;
    }

    /**
     * Queued on the collision map and walked by its next {@code n_update}, which runs on this same
     * thread just before the population ticks; the callback lands in {@link
     * PopManCore#completePath} with the square the walk ended on.
     */
    @Override
    public void requestPath(int fromX, int fromY, int toX, int toY, PopManRepopulateTask task) {
        long started = System.nanoTime();
        collision.requestPath(
                fromX,
                fromY,
                toX,
                toY,
                (status, endX, endY) -> {
                    if (StormLogger.LOGGER.isDebugEnabled()) {
                        StormLogger.LOGGER.debug(
                                "popman: repop path {},{} -> {},{} status={} reached={},{} count={}"
                                        + " in {} ms",
                                fromX,
                                fromY,
                                toX,
                                toY,
                                status,
                                endX,
                                endY,
                                task.count,
                                (System.nanoTime() - started) / 1_000_000);
                    }
                    core.completePath(task, status, endX, endY);
                });
    }
}
