package io.pzstorm.storm.popman;

import java.util.ArrayList;
import java.util.List;

/**
 * Growing a cell's population back after players have thinned it out. One job per cell at a time,
 * gated on three separate clocks, and delivered by walking a path in from somewhere off-screen
 * rather than by placing zombies directly.
 */
public final class PopManRepopulation {

    /** A deficit smaller than this is not worth chasing, and resets the cell's clock. */
    public static final int MIN_PENDING = 10;

    /** However small the quota works out, a batch is never smaller than this. */
    public static final int MIN_BATCH = 10;

    /** Not derived from any config value. */
    public static final int BATCH_DIVISOR = 36;

    /** Tries to find a spawnable destination square inside the chosen chunk. */
    public static final int DESTINATION_TRIES = 20;

    /** The flood fill around a path endpoint is five chunks on a side. */
    public static final int WINDOW_SIDE = 40;

    /** Where the incoming zombies are considered to walk in from. */
    public interface SpawnSource {
        boolean pick(int[] outSquareXY);
    }

    public interface PathRequests {
        void request(int fromX, int fromY, int toX, int toY, PopManRepopulateTask task);
    }

    /**
     * The repopulation window's fill, which fences off every square a player is streaming in before
     * it starts. That fence — not any distance check — is what keeps zombies from appearing in
     * front of someone.
     */
    static final class Window extends PopManFloodFill {
        Window() {
            super(WINDOW_SIDE);
        }

        @Override
        protected void prepare(PopManMap world) {
            for (int y = originY(); y < originY() + side(); y++) {
                for (int x = originX(); x < originX() + side(); x++) {
                    if (world.isInsideLoadedArea(x, y)) {
                        markVisited(x, y);
                    }
                }
            }
        }
    }

    private final PopManConfig config;
    private final PopManMap world;
    private final PopManCellMap cells;
    private final SpawnSource spawnSource;
    private final PathRequests paths;
    private final Window window = new Window();
    private final int[] source = new int[2];
    private int nextTaskId;

    public PopManRepopulation(
            PopManConfig config,
            PopManMap world,
            PopManCellMap cells,
            SpawnSource spawnSource,
            PathRequests paths) {
        this.config = config;
        this.world = world;
        this.cells = cells;
        this.spawnSource = spawnSource;
        this.paths = paths;
    }

    /** The last repopulation window fill; what the debug overlay paints blue. */
    public PopManFloodFill window() {
        return window;
    }

    public boolean isEnabled() {
        return !world.areZombiesDisabled() && config.respawnHours > 0.0F;
    }

    /**
     * Whether this cell both owes zombies and has waited long enough to be allowed them. Read by
     * the worker's park predicate, which must not sleep while any cell is in this state; the
     * repopulation pass itself re-derives the same numbers rather than trusting this.
     */
    public boolean isCellDue(PopManCell cell, double worldAgeHours, long nowMs) {
        if (!isEnabled() || cell.outstandingTasks >= 1) {
            return false;
        }
        float age = (float) worldAgeHours;
        int desired = PopManPopulation.desiredCellPopulation(config, cell.basePopSum, age);
        int deficit = Math.max(0, desired - cell.currentPopulation());
        int pending = deficit - neighbourSurplus(cell, worldAgeHours, nowMs);
        return pending >= MIN_PENDING && cell.lastRepopTime + config.respawnHours < age;
    }

    /**
     * Considers one cell for repopulation, emitting at most one path job. Returns the job, or null
     * when the cell is not due, is already busy, or has nowhere to put anyone.
     */
    public PopManRepopulateTask repopulateCell(PopManCell cell, double worldAgeHours, long nowMs) {

        if (!isEnabled() || cell.outstandingTasks >= 1) {
            return null;
        }
        float age = (float) worldAgeHours;

        int desired = PopManPopulation.desiredCellPopulation(config, cell.basePopSum, age);
        int deficit = Math.max(0, desired - cell.currentPopulation());
        int pending = deficit - neighbourSurplus(cell, worldAgeHours, nowMs);

        if (pending < MIN_PENDING) {
            cell.repopQuotaTarget = PopManCell.NO_QUOTA;
            cell.lastRepopTime = age;
            return null;
        }
        cell.lastRepopTime = Math.min(cell.lastRepopTime, age);
        if (!(cell.lastRepopTime + config.respawnHours < age)) {
            cell.repopQuotaTarget = PopManCell.NO_QUOTA;
            return null;
        }

        List<PopManChunk> candidates = new ArrayList<>();
        for (PopManChunk chunk : cell.chunks) {
            chunk.lastRepopTime = Math.min(chunk.lastRepopTime, age);
            if (!world.isInsideLoadedArea(chunk.minSquareX(), chunk.minSquareY())
                    && chunk.lastSeenTime + config.respawnUnseenHours < age
                    && chunk.zombies.size()
                            < PopManPopulation.desiredChunkPopulation(config, chunk.basePop, age)
                    && chunk.lastRepopTime + config.respawnHours < age) {
                candidates.add(chunk);
            }
        }

        if (cell.repopQuotaTarget == PopManCell.NO_QUOTA) {
            int target = PopManPopulation.desiredCellPopulation(config, cell.basePopSum, age);
            cell.repopQuotaBase = (int) (target * config.respawnMultiplier / 100.0F);
            cell.repopQuotaTarget =
                    (int) ((age - cell.lastRepopTime) / config.respawnHours) * cell.repopQuotaBase;
            cell.repopQuotaProgress = 0;
        }

        int batch = Math.min(Math.max(MIN_BATCH, cell.repopQuotaBase / BATCH_DIVISOR), pending);

        while (!candidates.isEmpty()) {
            PopManChunk chunk = candidates.get(world.random(candidates.size()));
            chunk.lastRepopTime = age;
            if (spawnSource.pick(source)) {
                for (int attempt = 0; attempt < DESTINATION_TRIES; attempt++) {
                    int toX = chunk.minSquareX() + world.random(PopManGeometry.SQUARES_PER_CHUNK);
                    int toY = chunk.minSquareY() + world.random(PopManGeometry.SQUARES_PER_CHUNK);
                    if (world.isValidSpawnSquare(toX, toY, 0)) {
                        PopManRepopulateTask task =
                                new PopManRepopulateTask(++nextTaskId, cell, batch);
                        paths.request(source[0], source[1], toX, toY, task);
                        return task;
                    }
                }
            }
            candidates.remove(chunk);
        }

        cell.lastRepopTime = age;
        cell.repopQuotaTarget = PopManCell.NO_QUOTA;
        return null;
    }

    /**
     * What the eight neighbouring cells are carrying above their own targets. A cell ringed by
     * over-populated neighbours gets no respawn budget, on the assumption that the surplus will
     * walk in by itself. Neighbours are loaded on demand, which is how an unvisited cell first gets
     * a population.
     */
    public int neighbourSurplus(PopManCell cell, double worldAgeHours, long nowMs) {
        float age = (float) worldAgeHours;
        int surplus = 0;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) {
                    continue;
                }
                int nx = cell.cellX + dx;
                int ny = cell.cellY + dy;
                if (!cells.inWorld(nx, ny)) {
                    continue;
                }
                PopManCell neighbour = cells.peek(nx, ny, worldAgeHours, nowMs);
                int desired =
                        PopManPopulation.desiredCellPopulation(config, neighbour.basePopSum, age);
                surplus += Math.max(0, neighbour.currentPopulation() - desired);
            }
        }
        return surplus;
    }

    /**
     * The pathfinder has finished with a job. The batch lands around wherever the path actually
     * ended, which need not be the cell that asked for it.
     */
    public void completePath(
            PopManRepopulateTask task,
            int status,
            int endX,
            int endY,
            double worldAgeHours,
            long nowMs,
            PopManResultFrame out,
            boolean isServer) {

        task.cell.outstandingTasks--;
        if (status != PopManRepopulateTask.PATH_FOUND) {
            return;
        }
        float age = (float) worldAgeHours;
        PopManCell endCell = cells.residentForSquare(endX, endY);
        if (endCell == null || !task.cell.loaded) {
            return;
        }

        int halfSquares =
                (WINDOW_SIDE / PopManGeometry.SQUARES_PER_CHUNK)
                        / 2
                        * PopManGeometry.SQUARES_PER_CHUNK;
        int originX =
                Math.max(
                        PopManGeometry.chunkOfSquare(endX) * PopManGeometry.SQUARES_PER_CHUNK
                                - halfSquares,
                        endCell.minSquareX());
        int originY =
                Math.max(
                        PopManGeometry.chunkOfSquare(endY) * PopManGeometry.SQUARES_PER_CHUNK
                                - halfSquares,
                        endCell.minSquareY());
        originX =
                Math.min(
                        originX,
                        endCell.minSquareX() + PopManGeometry.SQUARES_PER_CELL - WINDOW_SIDE);
        originY =
                Math.min(
                        originY,
                        endCell.minSquareY() + PopManGeometry.SQUARES_PER_CELL - WINDOW_SIDE);

        window.run(endX, endY, originX, originY, world);
        if (window.resultCount() == 0) {
            return;
        }

        int firstChunkX = PopManGeometry.chunkOfSquare(originX);
        int firstChunkY = PopManGeometry.chunkOfSquare(originY);
        int lastChunkX = PopManGeometry.chunkOfSquare(originX + WINDOW_SIDE - 1);
        int lastChunkY = PopManGeometry.chunkOfSquare(originY + WINDOW_SIDE - 1);
        for (int cy = firstChunkY; cy <= lastChunkY; cy++) {
            for (int cx = firstChunkX; cx <= lastChunkX; cx++) {
                endCell.chunkAt(cx, cy).lastRepopTime = age;
            }
        }

        if (isServer && out != null) {
            out.repopEvents.add(new PopManResultFrame.RepopEvent(firstChunkX, firstChunkY, age));
        }

        int spawnCount = Math.min(task.count, window.resultCount());
        task.count = spawnCount;
        for (int i = 0; i < spawnCount; i++) {
            int pick = world.random(window.resultCount());
            int squareX = window.resultX(pick);
            int squareY = window.resultY(pick);
            PopManChunk chunk = endCell.chunkAtSquare(squareX, squareY);
            chunk.zombies.add(
                    PopManZombie.spawnedAt(
                            squareX, squareY, () -> world.random(PopManZombie.DIRECTION_COUNT)));
        }
        endCell.virtualCount += spawnCount;
        endCell.repopQuotaProgress += task.count;
        if (endCell.repopQuotaTarget <= endCell.repopQuotaProgress) {
            endCell.lastRepopTime = age;
        }
    }
}
