package io.pzstorm.storm.patch.performance;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.metrics.StormPoolCompactionMetrics;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import zombie.network.GameServer;
import zombie.network.statistics.counters.PoolCounter;
import zombie.network.statistics.data.PerformanceStatistic;
import zombie.util.Pool;

/**
 * Repairs the permanent probe-length cliff in {@code zombie.util.Pool}'s per-thread in-use set.
 *
 * <p>{@code Pool.PoolStacks} tracks every checked-out object in a Trove {@code THashSet} whose
 * constructor calls {@code setAutoCompactionFactor(0.0F)}. Three vanilla mechanics compound from
 * there:
 *
 * <ol>
 *   <li>{@code THash.removeAt} only compacts when the factor is non-zero, so every removal leaves a
 *       REMOVED tombstone that is never reclaimed.
 *   <li>{@code TObjectHash.insertKeyRehash} terminates its probe walk only at a FREE slot —
 *       tombstones are remembered as {@code firstRemoved} but do not stop the walk. Average probe
 *       length is therefore {@code capacity / (_free + 1)}.
 *   <li>When an insert lands on {@code firstRemoved} it returns without setting {@code
 *       consumeFreeSlot}, so {@code THash.postInsertHook} does not decrement {@code _free}. Only
 *       inserts whose probe met no tombstone still consume one, and those get rarer as tombstones
 *       spread — the drain is asymptotic. The {@code _free == 0} emergency rehash (the one escape
 *       left once auto-compaction is off) is therefore reachable but arrives only after the table
 *       has already spent hours at its worst, and the cycle then restarts. The other trigger
 *       ({@code _size > _maxSize}) keys off live size, which is stable under pooling.
 * </ol>
 *
 * <p>Measured on a production server at 14.5h uptime: {@code capacity=823117}, {@code _free=7},
 * {@code live=316322}, ~510k tombstones — ~102,889 expected {@code equals} probes per insert, all
 * of it {@code AnimatorsBoneTransform.alloc} under zombie realization (41.9% of the server main
 * thread when sampled earlier at {@code _free=20}/~39,196 probes). Compacting that table in place
 * took 9ms and moved the probe estimate to 1 with the live count unchanged.
 *
 * <p>{@code _free} is an accurate health signal despite being the field that freezes: it counts
 * genuinely-untouched slots, and tombstone reuse legitimately consumes none. Probe length is
 * governed by exactly that count, so a low {@code _free} against a large capacity is the cliff and
 * nothing else. {@code compact()} rehashes to fit live size, drops every tombstone, and {@code
 * computeMaxSize} resets {@code _free = capacity - _size}, so one call fully unwinds it.
 *
 * <p><b>When it runs.</b> The sweep fires only from the world-save advice on {@code
 * ServerMap.QueuedSaveAll(false)}. At the start of every save it measures every pool and rehashes
 * any table over the probe threshold. The rehash itself is not free — 9ms on the observed
 * production table, about one tick at the tickrate that motivated this — but the save is already
 * committed to blocking the main thread for hundreds of milliseconds, so the cost folds into a
 * hitch the players were going to get anyway. Running it anywhere else would introduce a mid-play
 * hitch that a performance patch has no business creating.
 *
 * <p>Compaction must run on the thread that owns the {@code PoolStacks} — {@code
 * ThreadLocal}-confined — and {@code RagdollController.checkForActiveRagdoll} iterates {@code
 * getInUse()} with no lock (reachable server-side from {@code IsoChunk}). The save runs on the main
 * loop, the same thread every pooled type is allocated from, so the same-thread invariant holds.
 * {@code QueuedSaveAll(true)} — the shutdown path — runs on the shutdown-hook thread instead, so
 * the advice skips it: the stacks are {@code ThreadLocal}, and compacting on the way out would
 * rehash the wrong thread's table for no benefit. {@code Pool.release} may run on a foreign thread
 * but never compacts.
 *
 * <p>A server with {@code SaveWorldEveryMinutes=0} never saves and so is never compacted;
 * degeneration takes hours to form and any ordinary save interval resolves it long before it
 * becomes a problem.
 *
 * <p>Only the main thread's stacks are swept, which is where the cost was measured; other threads'
 * pools degrade harmlessly because they are not the bottleneck. Server-only, and any throwable
 * latches the sweep off permanently.
 */
public final class StormPoolCompaction {

    /**
     * Average probe length a pool's in-use set is allowed to reach before it is compacted. A
     * healthy open-addressed set at Trove's 0.5 load factor probes 1-2 slots per insert; production
     * servers have been measured at ~39,000.
     */
    private static final int MAX_PROBE = 64;

    /** Skip tables too small for a long probe to cost anything. */
    private static final int MIN_CAPACITY = 1024;

    private static final Pattern POOL_ID_SUFFIX = Pattern.compile("_\\d+$");

    private static final Field IN_USE_FIELD;

    private static final Field LOCK_FIELD;

    private static Field setField;

    private static Field freeField;

    private static Method compactMethod;

    private static boolean failed;

    static {
        Field inUse = null;
        Field lock = null;
        try {
            inUse = Pool.PoolStacks.class.getDeclaredField("inUse");
            inUse.setAccessible(true);
            lock = Pool.PoolStacks.class.getDeclaredField("lock");
            lock.setAccessible(true);
        } catch (Throwable t) {
            failed = true;
            LOGGER.error("StormPoolCompaction: cannot reflect Pool.PoolStacks; sweep disabled", t);
        }
        IN_USE_FIELD = inUse;
        LOCK_FIELD = lock;
    }

    private StormPoolCompaction() {}

    /** Average slots walked per insert; see {@code TObjectHash.insertKeyRehash}. */
    public static int probeEstimate(int capacity, int free) {
        if (capacity <= 0) {
            return 0;
        }
        return capacity / (Math.max(free, 0) + 1);
    }

    public static boolean shouldCompact(int capacity, int free, int maxProbe, int minCapacity) {
        return capacity >= minCapacity && probeEstimate(capacity, free) > maxProbe;
    }

    /**
     * Invoked from the world-save advice on {@code ServerMap.QueuedSaveAll(false)}. Public because
     * Byte Buddy inlines the calling advice body into {@code zombie.network.ServerMap}, so the
     * {@code invokestatic} is resolved from there.
     */
    public static void compactAtSaveWindow() {
        if (failed || !GameServer.server) {
            return;
        }
        try {
            List<Pool<?>> snapshot;
            Set<Pool<?>> pools = PerformanceStatistic.getInstance().pools;
            synchronized (pools) {
                snapshot = new ArrayList<>(pools);
            }
            int worst = 0;
            for (int i = 0; i < snapshot.size(); i++) {
                int probe = inspect(snapshot.get(i));
                if (probe > worst) {
                    worst = probe;
                }
            }
            StormPoolCompactionMetrics.setMaxProbeEstimate(worst);
        } catch (Throwable t) {
            failed = true;
            LOGGER.error("StormPoolCompaction sweep failed; disabling for this session", t);
        }
    }

    private static int inspect(Pool<?> pool) throws Exception {
        ThreadLocal<Pool.PoolStacks> threadStacks = pool.getPoolStacks();
        Pool.PoolStacks stacks = threadStacks.get();
        if (stacks.getPoolMonitoringCounter() == null) {
            // withInitial just minted an empty PoolStacks: this thread has never allocated from
            // the pool (every alloc/release registers the counter). Drop it again.
            threadStacks.remove();
            return 0;
        }
        Object inUse = IN_USE_FIELD.get(stacks);
        if (inUse == null) {
            return 0;
        }
        resolveTroveFields(inUse);

        Object lock = LOCK_FIELD.get(stacks);
        synchronized (lock) {
            Object[] set = (Object[]) setField.get(inUse);
            if (set == null) {
                return 0;
            }
            int capacity = set.length;
            int free = freeField.getInt(inUse);
            int probe = probeEstimate(capacity, free);

            if (!shouldCompact(capacity, free, MAX_PROBE, MIN_CAPACITY)) {
                return probe;
            }

            int size = ((Set<?>) inUse).size();
            long startNanos = System.nanoTime();
            compactMethod.invoke(inUse);
            long elapsedNanos = System.nanoTime() - startNanos;

            int capacityAfter = ((Object[]) setField.get(inUse)).length;
            int freeAfter = freeField.getInt(inUse);
            int probeAfter = probeEstimate(capacityAfter, freeAfter);

            String name = poolName(pool);
            StormPoolCompactionMetrics.recordCompaction(name, elapsedNanos / 1_000_000_000.0);
            LOGGER.info(
                    "StormPoolCompaction: compacted {} in {} ms — live={} capacity {}->{},"
                            + " free {}->{}, probe {}->{}",
                    name,
                    elapsedNanos / 1_000_000L,
                    size,
                    capacity,
                    capacityAfter,
                    free,
                    freeAfter,
                    probe,
                    probeAfter);
            return probeAfter;
        }
    }

    private static void resolveTroveFields(Object inUse) throws NoSuchMethodException {
        if (setField != null) {
            return;
        }
        Field set = findField(inUse.getClass(), "_set");
        Field free = findField(inUse.getClass(), "_free");
        Method compact = inUse.getClass().getMethod("compact");
        set.setAccessible(true);
        free.setAccessible(true);
        compactMethod = compact;
        freeField = free;
        setField = set;
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // keep walking; the field is declared on a Trove base class
            }
        }
        throw new IllegalStateException("No field " + name + " on " + type.getName());
    }

    private static String poolName(Pool<?> pool) {
        PoolCounter counter = pool.getMonitoringPoolCounter();
        if (counter == null || counter.used == null) {
            return "pool_" + pool.getID();
        }
        String name = counter.used.getName();
        if (name.endsWith("_used")) {
            name = name.substring(0, name.length() - "_used".length());
        }
        // Vanilla names pools Pool<Type>_<id>; the id is a PoolIDGenerator sequence number that
        // shifts with init order, so it is dropped to keep the metric label stable.
        return POOL_ID_SUFFIX.matcher(name).replaceFirst("");
    }
}
