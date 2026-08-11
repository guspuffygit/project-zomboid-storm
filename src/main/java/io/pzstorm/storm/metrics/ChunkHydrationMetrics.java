package io.pzstorm.storm.metrics;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.Gauge;
import io.prometheus.metrics.core.metrics.Histogram;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.List;
import zombie.iso.IsoChunk;
import zombie.network.ServerMap;

/**
 * Depth of the server's own world-hydration pipeline — the upstream cause of every {@code cold}
 * chunk in {@link ChunkStreamMetrics}.
 *
 * <p>A download worker cannot generate a chunk. {@code sendArray} only stats the save file, and a
 * never-visited chunk has none, so the request goes onto the 3-strike retry ladder and the client
 * waits. The only thing that can produce that chunk is the cell pipeline measured here: {@code
 * ServerMap.preupdate} enqueues a whole {@code ServerCell} (8x8 chunks) onto {@code
 * ServerChunkLoader}, the single {@code LoadChunk} thread reads or generates all 64, the single
 * {@code RecalcAll} thread makes three full passes over them, and only then does the main thread
 * run {@code Load2}/{@code RecalcAll2} and set {@code isLoaded}. Until that completes, {@code
 * ServerMap.getChunk} returns null for all 64 and every client request for them is unanswerable.
 *
 * <p>Both worker stages are single-threaded and their queues are unbounded, so depth here is the
 * honest measure of how far behind world hydration is. There is also no per-tick budget anywhere in
 * that path — {@code preupdate} submits every pending cell in one go and {@code Load2}s every
 * recalc-complete cell in the same tick — so a spike in {@code storm_chunk_hydration_cells_pending}
 * lands on the main thread whole.
 *
 * <p>Every field here is private or package-private in PZ, so reads go through cached reflection.
 * Each handle resolves once and latches off on failure; a PZ rename costs one warning and a flat
 * zero on these series, never an exception on the tick path.
 */
public final class ChunkHydrationMetrics {

    private static final Gauge CELLS_PENDING =
            Gauge.builder()
                    .name("storm_chunk_hydration_cells_pending")
                    .help(
                            "ServerCells wanted by a player but not yet finished loading"
                                    + " (ServerMap.toLoad). Each is 64 chunks that currently answer null"
                                    + " from ServerMap.getChunk, so this multiplied by 64 is an upper"
                                    + " bound on the chunks the server physically cannot serve right"
                                    + " now, however short the download queue is.")
                    .register(StormPrometheus.registry());

    private static final Gauge CELLS_LOADED =
            Gauge.builder()
                    .name("storm_chunk_hydration_cells_loaded")
                    .help(
                            "ServerCells currently resident (ServerMap.loadedCells). Context for"
                                    + " storm_chunk_hydration_cells_pending and for the cell-unload"
                                    + " budget: cells being unloaded and immediately re-requested by a"
                                    + " player who turned around show up as churn between these two.")
                    .register(StormPrometheus.registry());

    private static final Gauge QUEUE_DEPTH =
            Gauge.builder()
                    .name("storm_chunk_hydration_queue_depth")
                    .help(
                            "Cells waiting at each stage of ServerChunkLoader. load_in = queued for"
                                    + " the single LoadChunk thread, which reads or world-generates all"
                                    + " 64 chunks of a cell in one uninterruptible go; load_out = read"
                                    + " and waiting for the main thread to forward them; recalc_in ="
                                    + " queued for the single max-priority RecalcAll thread, which makes"
                                    + " three full 8x8x64 passes per cell; recalc_out = waiting for the"
                                    + " main thread to run Load2. Depth at load_in or recalc_in means"
                                    + " world hydration is the bottleneck and no amount of extra"
                                    + " download bandwidth will help.")
                    .labelNames("stage")
                    .register(StormPrometheus.registry());

    private static final Histogram DISK_READ_DURATION =
            Histogram.builder()
                    .name("storm_chunk_disk_read_duration_seconds")
                    .help(
                            "Time in IsoChunk.SafeRead, by which thread called it. download = a"
                                    + " PlayerDownloadServer worker serving a client request; other ="
                                    + " the LoadChunk thread or a save path. SafeRead holds a fair"
                                    + " per-chunk read-write lock across the whole file read and"
                                    + " acquires it through a global monitor with a linear scan, so a"
                                    + " queued writer on a hot chunk blocks every subsequent reader"
                                    + " including all download workers.")
                    .labelNames("caller")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private static final Histogram CHECKSUM_DURATION =
            Histogram.builder()
                    .name("storm_chunk_checksum_duration_seconds")
                    .help(
                            "Time in ChunkChecksum.getChecksum, by calling thread. The entire method"
                                    + " body — including a full file read through a shared 1 KB buffer"
                                    + " on a cache miss — runs inside one static monitor, so every"
                                    + " download worker on the server plus the save thread serialise"
                                    + " here. This is a cross-peer coupling point: one player's cold"
                                    + " checksum stalls every other player's download worker.")
                    .labelNames("caller")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private static final Counter CHECKSUM_CALLS =
            Counter.builder()
                    .name("storm_chunk_checksum_calls_total")
                    .help(
                            "Calls to ChunkChecksum.getChecksum, by calling thread. Paired with the"
                                    + " duration histogram this gives the total time the global checksum"
                                    + " monitor is held per second — the figure that matters for"
                                    + " deciding whether it needs to stop being global.")
                    .labelNames("caller")
                    .register(StormPrometheus.registry());

    private static final Counter CANCELLED_CELLS =
            Counter.builder()
                    .name("storm_chunk_hydration_cancelled_cells_total")
                    .help(
                            "ServerCells whose load was cancelled because no player was near them any"
                                    + " more, by how far they had got. before_dispatch = cancelled while"
                                    + " still queued, so nothing was wasted. in_flight = a worker thread"
                                    + " had already started, so up to 64 chunks of disk reads or"
                                    + " worldgen are discarded — and a cell containing brand-new chunks"
                                    + " cannot be cancelled at the recalc stage at all, so it pays the"
                                    + " full three-pass recalc before being thrown away. A driver who"
                                    + " outruns hydration generates this churn continuously: cells are"
                                    + " requested, half-loaded, abandoned as they pass, and requested"
                                    + " again on the way back. High in_flight means the hydration"
                                    + " threads are busy doing work nobody will ever see, which is why"
                                    + " the cells actually ahead of the player stay pending. Counted"
                                    + " only on Storm's own postupdate bodies — cell warming, or the"
                                    + " unload budget when Storm.CellUnloadBudgetPerTick > 0. Set the"
                                    + " budget to 0 with warming off, or trip either failure latch,"
                                    + " and the uninstrumented vanilla body runs instead: cancellation"
                                    + " carries on, this counter flatlines, and zero here then means"
                                    + " unmeasured rather than none.")
                    .labelNames("stage")
                    .register(StormPrometheus.registry());

    private static final Histogram CELL_DURATION =
            Histogram.builder()
                    .name("storm_chunk_hydration_cell_duration_seconds")
                    .help(
                            "How long a ServerCell spent in each half of hydration, measured from"
                                    + " ServerChunkLoader.addJob to the Load2 that finally sets"
                                    + " isLoaded. load = queued for and processed by the single"
                                    + " LoadChunk thread, which reads or world-generates all 64 chunks"
                                    + " in one uninterruptible go. recalc = queued for and processed by"
                                    + " the single max-priority RecalcAll thread, plus the wait for the"
                                    + " main thread to run Load2. Both stages are one thread with an"
                                    + " unbounded queue, so whichever side carries the time is the"
                                    + " bottleneck — and they need different fixes, which is why an"
                                    + " end-to-end number alone is not enough to act on.")
                    .labelNames("stage")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private static final Histogram CELL_TOTAL_DURATION =
            Histogram.builder()
                    .name("storm_chunk_hydration_cell_total_seconds")
                    .help(
                            "End-to-end hydration time for one ServerCell, split by what the content"
                                    + " cost. disk = all 64 chunks came from save files. generated = at"
                                    + " least one chunk went through IsoChunk.LoadBrandNew, which runs"
                                    + " worldgen over a 2x2 chunk quad. The split is per-cell but the"
                                    + " branch is per-chunk, so a cell at the edge of explored territory"
                                    + " legitimately mixes both and is reported as generated. This is"
                                    + " the number that bounds how fast a player can drive into"
                                    + " never-visited terrain without outrunning the server.")
                    .labelNames("content")
                    .nativeOnly()
                    .register(StormPrometheus.registry());

    private static final Gauge OLDEST_PENDING =
            Gauge.builder()
                    .name("storm_chunk_hydration_oldest_pending_seconds")
                    .help(
                            "Age of the longest-waiting cell still in ServerMap.toLoad that has been"
                                    + " handed to a worker. The duration histograms only record cells"
                                    + " that finish, so they are blind to exactly the case that matters:"
                                    + " a player parked next to a cell that never loads. This is also"
                                    + " the only signal for a permanently stranded cell — if a worker"
                                    + " throws, the cell keeps startedLoading set, preupdate will never"
                                    + " re-dispatch it, and ServerMap.getChunk answers null for its 64"
                                    + " chunks forever. That shows up here as a value that climbs"
                                    + " without bound instead of sawtoothing.")
                    .register(StormPrometheus.registry());

    private static final String DOWNLOAD_THREAD_PREFIX = "PlayerDownloadServer";

    private static Field toLoadField;
    private static Field chunkLoaderField;
    private static Field threadLoadField;
    private static Field threadRecalcField;
    private static Field loadInField;
    private static Field loadOutField;
    private static Field recalcInField;
    private static Field recalcOutField;

    private static volatile Field hydrateStartField;
    private static volatile Field recalcStartField;

    private static boolean resolved;
    private static volatile boolean stampFieldsResolved;
    private static volatile boolean stampFieldsFailed;
    private static volatile boolean failed;

    private ChunkHydrationMetrics() {}

    /** Label a measurement by whether it is on the client-download path or the hydration path. */
    public static String callerRole() {
        return Thread.currentThread().getName().startsWith(DOWNLOAD_THREAD_PREFIX)
                ? "download"
                : "other";
    }

    public static void recordDiskRead(String caller, long nanos) {
        DISK_READ_DURATION.labelValues(caller).observe(nanos / 1e9);
    }

    public static void recordChecksum(String caller, long nanos) {
        CHECKSUM_CALLS.labelValues(caller).inc();
        CHECKSUM_DURATION.labelValues(caller).observe(nanos / 1e9);
    }

    /**
     * Tally one tick's worth of cancelled cell loads, called from Storm's re-implementations of
     * {@code ServerMap.postupdate}. Takes both totals at once so the caller can accumulate plain
     * locals in its scan loop instead of paying a varargs allocation per cancelled cell.
     */
    public static void recordCancelledCells(long beforeDispatch, long inFlight) {
        if (beforeDispatch > 0) {
            CANCELLED_CELLS.labelValues("before_dispatch").inc(beforeDispatch);
        }
        if (inFlight > 0) {
            CANCELLED_CELLS.labelValues("in_flight").inc(inFlight);
        }
    }

    /**
     * Start a cell's hydration clock, from the exit advice on {@code ServerChunkLoader.addJob} —
     * the single point at which a cell is handed to the LoadChunk thread, with exactly one call
     * site in {@code ServerMap.preupdate}.
     *
     * <p>Unlike {@code ClientChunkRequest}, {@code ServerCell} is never pooled: {@code preupdate}
     * allocates a fresh one and drops it by nulling {@code cellMap}, so a stamp cannot outlive its
     * cell and be misread as a later load.
     */
    public static void recordCellQueued(Object cell) {
        if (failed) {
            return;
        }
        try {
            setStamp(hydrateStartField(), cell, System.nanoTime());
            setStamp(recalcStartField(), cell, 0L);
        } catch (Throwable t) {
            disable(t);
        }
    }

    /**
     * Close the load stage and open the recalc stage, from the exit advice on {@code
     * ServerChunkLoader.addRecalcJob} — also one call site, on the line after {@code preupdate}
     * drains the loader's output.
     */
    public static void recordCellRecalcQueued(Object cell) {
        if (failed) {
            return;
        }
        try {
            long queued = getStamp(hydrateStartField(), cell);
            long now = System.nanoTime();
            if (queued > 0L) {
                CELL_DURATION.labelValues("load").observe(Math.max(0L, now - queued) / 1e9);
            }
            setStamp(recalcStartField(), cell, now);
        } catch (Throwable t) {
            disable(t);
        }
    }

    /**
     * Close out a finished cell, from the {@code Load2} exit advice on the {@code return true} path
     * only — the fall-through return is a drain-and-bail that does no hydration work.
     *
     * <p>{@code content} is decided by scanning the cell's 64 chunks for {@code isNewChunk()},
     * which {@code LoadBrandNew} sets and only {@code resetForStore} clears, so it stays valid for
     * the whole time the chunk belongs to this cell.
     */
    public static void recordCellLoaded(long hydrateStart, long recalcStart, IsoChunk[][] chunks) {
        if (failed) {
            return;
        }
        try {
            long now = System.nanoTime();
            if (recalcStart > 0L) {
                CELL_DURATION.labelValues("recalc").observe(Math.max(0L, now - recalcStart) / 1e9);
            }
            if (hydrateStart > 0L) {
                CELL_TOTAL_DURATION
                        .labelValues(anyGenerated(chunks) ? "generated" : "disk")
                        .observe(Math.max(0L, now - hydrateStart) / 1e9);
            }
        } catch (Throwable t) {
            disable(t);
        }
    }

    private static boolean anyGenerated(IsoChunk[][] chunks) {
        if (chunks == null) {
            return false;
        }
        for (IsoChunk[] column : chunks) {
            if (column == null) {
                continue;
            }
            for (IsoChunk chunk : column) {
                if (chunk != null && chunk.isNewChunk()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Publish the pipeline depths. Called on the main thread from the server tick. */
    public static void sampleTick() {
        if (failed) {
            return;
        }
        try {
            sampleTickInner();
        } catch (Throwable t) {
            disable(t);
        }
    }

    /**
     * The recorders run inside {@code ServerMap.preupdate}, whose only enclosing handler is a
     * {@code finally} that resumes {@code ServerLOS}. Anything escaping from here aborts cell
     * hydration for the tick, so every entry point swallows and latches instead.
     */
    private static void disable(Throwable cause) {
        if (failed) {
            return;
        }
        failed = true;
        LOGGER.warn(
                "Storm: chunk-hydration metrics failed and are now disabled for this session",
                cause);
    }

    private static void sampleTickInner() throws Exception {
        ServerMap map = ServerMap.instance;
        if (map == null) {
            return;
        }
        resolveFields();

        List<?> loadedCells = map.loadedCells;
        CELLS_LOADED.set(loadedCells == null ? 0 : loadedCells.size());

        Object toLoad = toLoadField == null ? null : toLoadField.get(map);
        CELLS_PENDING.set(toLoad instanceof List ? ((List<?>) toLoad).size() : 0);
        sampleOldestPending(toLoad instanceof List ? (List<?>) toLoad : null);

        Object loader = chunkLoaderField == null ? null : chunkLoaderField.get(null);
        if (loader == null) {
            return;
        }
        Object threadLoad = threadLoadField == null ? null : threadLoadField.get(loader);
        Object threadRecalc = threadRecalcField == null ? null : threadRecalcField.get(loader);

        QUEUE_DEPTH.labelValues("load_in").set(sizeOf(loadInField, threadLoad));
        QUEUE_DEPTH.labelValues("load_out").set(sizeOf(loadOutField, threadLoad));
        QUEUE_DEPTH.labelValues("recalc_in").set(sizeOf(recalcInField, threadRecalc));
        QUEUE_DEPTH.labelValues("recalc_out").set(sizeOf(recalcOutField, threadRecalc));
    }

    /**
     * The stamps live on {@code ServerCell} but are written from advices on {@code
     * ServerChunkLoader}, a different class, so {@code @Advice.FieldValue} cannot reach them and
     * these go through reflection. Every caller is on the server main thread — {@code preupdate}
     * for the writes, the tick sampler for the reads — so no synchronization is needed.
     */
    private static void setStamp(Field field, Object cell, long value) {
        if (field == null || cell == null) {
            return;
        }
        try {
            field.setLong(cell, value);
        } catch (IllegalAccessException | IllegalArgumentException e) {
            disableStamps("write", e);
        }
    }

    private static long getStamp(Field field, Object cell) {
        if (field == null || cell == null) {
            return 0L;
        }
        try {
            return field.getLong(cell);
        } catch (IllegalAccessException | IllegalArgumentException e) {
            disableStamps("read", e);
            return 0L;
        }
    }

    /**
     * Nulls the fields as well as setting the flag. {@code resolveStampFields} short-circuits on
     * {@code stampFieldsResolved} before it ever reads {@code stampFieldsFailed}, so the flag alone
     * would leave a live {@code Field} in place and every later call would throw and warn again —
     * once per pending cell per tick. The {@code field == null} guard above is the real latch.
     */
    private static void disableStamps(String operation, Throwable cause) {
        if (stampFieldsFailed) {
            return;
        }
        hydrateStartField = null;
        recalcStartField = null;
        stampFieldsFailed = true;
        LOGGER.warn(
                "Storm: ServerCell hydration stamp "
                        + operation
                        + " failed — storm_chunk_hydration_cell_duration_seconds is now disabled",
                cause);
    }

    private static Field hydrateStartField() {
        resolveStampFields();
        return hydrateStartField;
    }

    private static Field recalcStartField() {
        resolveStampFields();
        return recalcStartField;
    }

    private static void resolveStampFields() {
        if (stampFieldsResolved || stampFieldsFailed) {
            return;
        }
        try {
            Class<?> cellClass = Class.forName("zombie.network.ServerMap$ServerCell");
            hydrateStartField = openField(cellClass, "storm$hydrateStartNanos");
            recalcStartField = openField(cellClass, "storm$recalcStartNanos");
            stampFieldsResolved = true;
        } catch (ClassNotFoundException | RuntimeException | LinkageError e) {
            stampFieldsResolved = true;
            stampFieldsFailed = true;
            LOGGER.warn(
                    "Storm: ServerCell hydration stamps unavailable —"
                            + " storm_chunk_hydration_cell_duration_seconds will stay empty",
                    e);
        }
    }

    /**
     * Age the oldest dispatched-but-unfinished cell. Reads {@code toLoad} directly rather than
     * tracking a separate collection: a cell leaves that list only when it loads or is cancelled,
     * so anything still in it with a stamp is genuinely outstanding.
     */
    private static void sampleOldestPending(List<?> toLoad) {
        Field field = hydrateStartField();
        if (field == null || toLoad == null) {
            OLDEST_PENDING.set(0.0);
            return;
        }
        long now = System.nanoTime();
        long oldest = 0L;
        for (int i = 0; i < toLoad.size(); i++) {
            Object cell;
            try {
                cell = toLoad.get(i);
            } catch (IndexOutOfBoundsException e) {
                break;
            }
            long queued = getStamp(field, cell);
            if (queued <= 0L) {
                continue;
            }
            long age = now - queued;
            if (age > oldest) {
                oldest = age;
            }
        }
        OLDEST_PENDING.set(oldest / 1e9);
    }

    private static int sizeOf(Field field, Object owner) throws IllegalAccessException {
        if (field == null || owner == null) {
            return 0;
        }
        Object value = field.get(owner);
        return value instanceof Collection ? ((Collection<?>) value).size() : 0;
    }

    /**
     * The chain is {@code ServerMap$ServerCell.chunkLoader} (private static) → {@code
     * ServerChunkLoader.threadLoad}/{@code threadRecalc} (private) → each thread's {@code
     * toThread}/{@code fromThread} (private, on package-private inner classes). Resolved once from
     * the live objects rather than by name so an inner-class rename does not break the walk.
     */
    private static void resolveFields() throws Exception {
        if (resolved) {
            return;
        }
        resolved = true;

        toLoadField = openField(ServerMap.class, "toLoad");
        chunkLoaderField =
                openField(Class.forName("zombie.network.ServerMap$ServerCell"), "chunkLoader");
        Class<?> loaderClass = Class.forName("zombie.network.ServerChunkLoader");
        threadLoadField = openField(loaderClass, "threadLoad");
        threadRecalcField = openField(loaderClass, "threadRecalc");

        if (threadLoadField != null) {
            loadInField = openField(threadLoadField.getType(), "toThread");
            loadOutField = openField(threadLoadField.getType(), "fromThread");
        }
        if (threadRecalcField != null) {
            recalcInField = openField(threadRecalcField.getType(), "toThread");
            recalcOutField = openField(threadRecalcField.getType(), "fromThread");
        }
    }

    private static Field openField(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException | RuntimeException e) {
            LOGGER.warn(
                    "Storm: {}.{} not found — the matching chunk-hydration series will stay at 0",
                    owner.getSimpleName(),
                    name,
                    e);
            return null;
        }
    }
}
