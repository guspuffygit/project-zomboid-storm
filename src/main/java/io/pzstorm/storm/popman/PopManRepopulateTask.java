package io.pzstorm.storm.popman;

/**
 * One outstanding "grow this cell back" job. It asks the pathfinder for a route from somewhere
 * zombies could plausibly come from to a square inside an under-populated chunk, and spawns its
 * batch wherever that route ends up.
 *
 * <p>Registering with the cell on construction is what enforces one job per cell at a time.
 */
public final class PopManRepopulateTask {

    /** The pathfinder found a route. Any other status abandons the batch. */
    public static final int PATH_FOUND = 3;

    public final int id;
    public final PopManCell cell;

    /** How many zombies this batch may place; trimmed down to what the flood fill found. */
    public int count;

    public PopManRepopulateTask(int id, PopManCell cell, int count) {
        this.id = id;
        this.cell = cell;
        this.count = count;
        cell.outstandingTasks++;
    }
}
