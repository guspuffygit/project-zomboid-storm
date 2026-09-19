package io.pzstorm.storm.metrics;

import io.prometheus.metrics.core.metrics.CounterWithCallback;

/**
 * Metrics for the native pathfinder's chunk task drain. Single writer (the pathfinder thread), read
 * by the scrape thread, hence the volatile fields.
 */
public final class PathfindChunkTaskDrainMetrics {

    private static volatile long drained;
    private static volatile long budgetExhausted;

    @SuppressWarnings("unused")
    private static final CounterWithCallback DRAINED =
            CounterWithCallback.builder()
                    .name("storm_pathfind_chunk_tasks_drained_total")
                    .help(
                            "Native pathfinder chunk tasks (chunk updates and removes) executed by"
                                    + " Storm's per-frame drain, ahead of vanilla's ten updates"
                                    + " per frame.")
                    .callback(callback -> callback.call((double) drained))
                    .register(StormPrometheus.registry());

    @SuppressWarnings("unused")
    private static final CounterWithCallback BUDGET_EXHAUSTED =
            CounterWithCallback.builder()
                    .name("storm_pathfind_chunk_task_budget_exhausted_total")
                    .help(
                            "Pathfinder frames whose drain spent its time budget with chunk tasks"
                                    + " still queued. A steady climb means the queue is backing up"
                                    + " again: raise -Dstorm.pathfind.chunkTaskBudgetMs.")
                    .callback(callback -> callback.call((double) budgetExhausted))
                    .register(StormPrometheus.registry());

    private PathfindChunkTaskDrainMetrics() {}

    public static void record(int tasksDrained, boolean tasksLeft) {
        drained += tasksDrained;
        if (tasksLeft) {
            budgetExhausted++;
        }
    }
}
