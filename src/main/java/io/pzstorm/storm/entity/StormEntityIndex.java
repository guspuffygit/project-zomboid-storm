package io.pzstorm.storm.entity;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.metrics.EntityIndexMetrics;
import io.pzstorm.storm.metrics.StormPerformanceSandboxMetrics;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * O(1) removal indexes for the engine's entity arrays, replacing the linear identity scans in
 * {@code EngineEntityManager.removeEntityInternal}'s {@code entities.removeValue(entity, true)} and
 * in {@code EntityBucket.updateMembership}'s per-bucket {@code entities.removeValue(entity, true)}
 * (~123k entities in the global array at 79 players; the bucket scans alone were ~2% of main-thread
 * time at 116 players because every entity removal re-scans each bucket's array).
 *
 * <p><b>Why this is sound.</b> Every tracked array is constructed unordered ({@code new
 * Array<>(false, 16)}), so {@code Array.removeIndex} is swap-with-last O(1) — the whole cost of a
 * removal is the linear index search this class eliminates. Neither array is ever leaked mutably:
 * the only wrapper is {@code ImmutableArray} (read-only views; {@code toArray()} copies), and the
 * only mutation sites in the entire game source are single-arg {@code add(T)} and {@code
 * removeValue(T, true)} — {@code EngineEntityManager.addEntityInternal}/{@code
 * removeEntityInternal} for the global array, {@code EntityBucket.updateMembership} lines 74/94 for
 * bucket arrays. No clear/sort/set/insert ever touches them. All mutation runs on the server main
 * thread (the vanilla manager is entirely unsynchronized), so the indexes need no locking.
 *
 * <p><b>How indexes are attached.</b> {@code EntityArrayRemoveFastPathPatch} redefines {@code
 * zombie.entity.util.Array} to carry a {@code stormEntityArrayIndex} slot ({@link
 * StormIndexedArray}); a {@code null} slot means untracked and costs the advice helpers one field
 * read. Because the index lives on the array, world reloads need no registry cleanup — a dead
 * world's indexes are garbage-collected with its arrays. Registration points:
 *
 * <ul>
 *   <li>{@code EngineEntityManagerCreatedAdvice} (constructor exit) calls {@link
 *       #onManagerCreated(Object)} — indexes the manager's global {@code entities} array and any
 *       buckets that already exist (the renderer bucket is constructed inside the manager's own
 *       constructor, before this advice fires).
 *   <li>{@code EntityBucketCreatedAdvice} (constructor exit) calls {@link #onBucketCreated(Object)}
 *       — indexes each lazily-created bucket's array.
 *   <li>{@code EntityArrayAddAdvice} ({@code Array.add(T)} exit) calls {@link #onArrayAdd} — for
 *       tracked arrays, records {@code value -> size - 1} at the instant of the append.
 *   <li>{@code EntityArrayRemoveValueAdvice} ({@code Array.removeValue} enter) calls {@link
 *       #onRemoveValue} — for tracked arrays, performs the removal itself (index lookup, identity
 *       self-check, {@code removeIndex}, swapped-element index fixup) and skips the vanilla linear
 *       scan. Untracked arrays fall through to vanilla untouched.
 * </ul>
 *
 * <p><b>Self-check.</b> Before an indexed removal the helper verifies {@code items[index] ==
 * entity}. On mismatch (which would mean an index desynced — a bug) it latches {@link #failed},
 * logs loudly, and falls back to the vanilla linear scan permanently for all arrays. A wrong-entity
 * removal is therefore impossible: an index is only ever used after an identity match.
 *
 * <p><b>Kill switch.</b> {@code Storm.EntityRemoveFastPath} (boolean, default on). While disabled
 * the indexes are not maintained; re-enabling bumps {@link #ENABLE_EPOCH}, and each index lazily
 * rebuilds itself from its array on its next main-thread touch. Removals that miss an index (only
 * possible around toggles) are handled by an inline identity scan with the same swap fixup, keeping
 * the index consistent.
 */
public final class StormEntityIndex {

    /** Default for {@code Storm.EntityRemoveFastPath}: fast removal on. */
    public static final boolean DEFAULT_ENABLED = true;

    /**
     * Kill switch, driven by the {@code Storm.EntityRemoveFastPath} sandbox option through {@link
     * #setEnabled(boolean)}. Volatile because the sandbox applier may push updates from outside the
     * main thread.
     */
    private static volatile boolean enabled = DEFAULT_ENABLED;

    /** Permanent revert-to-vanilla latch; set on the first self-check mismatch or throw. */
    private static volatile boolean failed;

    /**
     * Bumped on every off-to-on kill-switch transition. An {@link EntityArrayIndex} whose {@code
     * epoch} differs went stale while maintenance was off and rebuilds on its next use — the
     * rebuild runs inline on the main thread, so index mutations never happen off-thread.
     */
    private static final AtomicInteger ENABLE_EPOCH = new AtomicInteger(1);

    /** The current engine's global-array index, kept for the size gauge; null until tracked. */
    private static EntityArrayIndex engineIndex;

    /** Arrays indexed for the current world generation; reset when a new manager is created. */
    private static int trackedArrays;

    private static Field entitiesField;
    private static Field bucketManagerField;
    private static Field bucketsArrayField;
    private static Field bucketEntitiesField;

    private StormEntityIndex() {}

    /**
     * Applies the {@code Storm.EntityRemoveFastPath} sandbox option and pushes the applied value to
     * the Prometheus gauge. Enabling schedules per-array rebuilds (the indexes went stale while
     * off); disabling stops maintenance and consultation immediately.
     *
     * @return the applied value
     */
    public static boolean setEnabled(boolean value) {
        boolean was = enabled;
        enabled = value;
        if (value && !was) {
            ENABLE_EPOCH.incrementAndGet();
        }
        StormPerformanceSandboxMetrics.setEntityRemoveFastPath(value);
        return value;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** Current global-array index size; dirty-read by the scrape callback. */
    public static int indexSize() {
        EntityArrayIndex idx = engineIndex;
        return idx == null ? 0 : idx.map.size();
    }

    /** Arrays indexed for the current world generation; dirty-read by the scrape callback. */
    public static int trackedArrayCount() {
        return trackedArrays;
    }

    /**
     * Called from {@code EngineEntityManagerCreatedAdvice} when a new manager (new world) is
     * constructed. Indexes the manager's freshly-created global entity array, plus any buckets its
     * bucket manager already holds — the renderer bucket is created inside the manager's own
     * constructor chain, so its {@code EntityBucketCreatedAdvice} fired before this reset.
     *
     * @param managerObj the {@code EngineEntityManager} ({@code @Advice.This}; typed {@code Object}
     *     so the advice never references the transform target).
     */
    public static void onManagerCreated(Object managerObj) {
        try {
            if (entitiesField == null) {
                Field f = managerObj.getClass().getDeclaredField("entities");
                f.setAccessible(true);
                entitiesField = f;
            }
            if (bucketManagerField == null) {
                Field f = managerObj.getClass().getDeclaredField("bucketManager");
                f.setAccessible(true);
                bucketManagerField = f;
            }
            trackedArrays = 0;
            engineIndex = null;
            Object entities = entitiesField.get(managerObj);
            EntityArrayIndex idx = registerArray(entities, "engine");
            if (idx == null) {
                failed = true;
                LOGGER.error(
                        "StormEntityIndex could not index the global entity array — entity"
                                + " removal stays vanilla");
                return;
            }
            engineIndex = idx;
            Object bucketManager = bucketManagerField.get(managerObj);
            if (bucketsArrayField == null) {
                Field f = bucketManager.getClass().getDeclaredField("bucketsArray");
                f.setAccessible(true);
                bucketsArrayField = f;
            }
            zombie.entity.util.Array<?> buckets =
                    (zombie.entity.util.Array<?>) bucketsArrayField.get(bucketManager);
            for (int i = 0; i < buckets.size; i++) {
                onBucketCreated(buckets.items[i]);
            }
            LOGGER.info(
                    "StormEntityIndex: tracking new EngineEntityManager ({} arrays indexed)",
                    trackedArrays);
        } catch (Throwable t) {
            failed = true;
            engineIndex = null;
            LOGGER.error(
                    "StormEntityIndex failed to track EngineEntityManager — entity removal stays"
                            + " vanilla",
                    t);
        }
    }

    /**
     * Called from {@code EntityBucketCreatedAdvice} after every {@code EntityBucket} constructor.
     * Indexes the bucket's own entity array so {@code updateMembership} removals resolve O(1). A
     * bucket that cannot be indexed is simply left untracked (vanilla scans for that bucket only).
     */
    public static void onBucketCreated(Object bucketObj) {
        try {
            if (bucketEntitiesField == null) {
                Class<?> c = bucketObj.getClass();
                while (c != null && !"zombie.entity.EntityBucket".equals(c.getName())) {
                    c = c.getSuperclass();
                }
                if (c == null) {
                    LOGGER.error(
                            "StormEntityIndex: {} does not extend EntityBucket — not indexing",
                            bucketObj.getClass().getName());
                    return;
                }
                Field f = c.getDeclaredField("entities");
                f.setAccessible(true);
                bucketEntitiesField = f;
            }
            registerArray(bucketEntitiesField.get(bucketObj), bucketObj.getClass().getSimpleName());
        } catch (Throwable t) {
            LOGGER.error(
                    "StormEntityIndex failed to index bucket {} — its removals stay vanilla",
                    bucketObj.getClass().getName(),
                    t);
        }
    }

    /**
     * Builds and attaches an index for the given array. Package-private for tests.
     *
     * @return the attached index, or null if the array was refused (ordered, not woven, or already
     *     indexed — an already-indexed array keeps its existing index).
     */
    static EntityArrayIndex registerArray(Object arrayObj, String label) {
        if (!(arrayObj instanceof zombie.entity.util.Array<?> array)
                || !(arrayObj instanceof StormIndexedArray indexed)) {
            LOGGER.error(
                    "StormEntityIndex: {} array is not a woven zombie.entity.util.Array — not"
                            + " indexing",
                    label);
            return null;
        }
        if (array.ordered) {
            // The whole design rests on swap-with-last removeIndex; an ordered array would
            // shift instead and every fixup would be wrong. Never true today (all tracked
            // arrays are constructed with ordered=false) — refuse rather than desync.
            LOGGER.error(
                    "StormEntityIndex: {} array is ordered — refusing to index; its removals"
                            + " stay vanilla",
                    label);
            return null;
        }
        if (indexed.getStormEntityArrayIndex() != null) {
            trackedArrays++;
            return (EntityArrayIndex) indexed.getStormEntityArrayIndex();
        }
        EntityArrayIndex idx = new EntityArrayIndex(label);
        for (int i = 0; i < array.size; i++) {
            idx.map.put(array.items[i], i);
        }
        idx.epoch = ENABLE_EPOCH.get();
        // Publish only after the map is fully built — the advice helpers read this slot.
        indexed.setStormEntityArrayIndex(idx);
        trackedArrays++;
        return idx;
    }

    /**
     * Called from {@code EntityArrayAddAdvice} after every single-arg {@code Array.add}. Untracked
     * arrays cost one field read; tracked ones record {@code value -> size - 1}.
     */
    public static void onArrayAdd(Object arrayObj, Object value) {
        if (failed || !enabled) {
            return;
        }
        if (!(arrayObj instanceof StormIndexedArray indexed)) {
            return;
        }
        Object idxObj = indexed.getStormEntityArrayIndex();
        if (idxObj == null) {
            return;
        }
        try {
            EntityArrayIndex idx = (EntityArrayIndex) idxObj;
            zombie.entity.util.Array<?> array = (zombie.entity.util.Array<?>) arrayObj;
            if (idx.epoch != ENABLE_EPOCH.get()) {
                rebuild(idx, array);
                return; // rebuild scanned the array post-append; value is already indexed.
            }
            idx.map.put(value, array.size - 1);
        } catch (Throwable t) {
            failed = true;
            LOGGER.error(
                    "StormEntityIndex add-maintenance failed — reverting entity removal to"
                            + " vanilla",
                    t);
        }
    }

    /**
     * Called from {@code EntityArrayRemoveValueAdvice} on entry to {@code Array.removeValue}.
     *
     * @return 0 to run the vanilla body (untracked array, kill switch off, or failure latch); 1 =
     *     removal performed here, {@code removeValue} must return {@code true}; 2 = value not
     *     present, {@code removeValue} must return {@code false}.
     */
    public static int onRemoveValue(Object arrayObj, Object value, boolean identity) {
        if (!(arrayObj instanceof StormIndexedArray indexed)) {
            return 0;
        }
        Object idxObj = indexed.getStormEntityArrayIndex();
        if (idxObj == null) {
            return 0;
        }
        if (failed || !enabled) {
            EntityIndexMetrics.vanillaRemovals++;
            return 0;
        }
        try {
            EntityArrayIndex idx = (EntityArrayIndex) idxObj;
            zombie.entity.util.Array<?> array = (zombie.entity.util.Array<?>) arrayObj;
            if (idx.epoch != ENABLE_EPOCH.get()) {
                rebuild(idx, array);
            }
            if (identity || value == null) {
                Integer index = idx.map.remove(value);
                if (index != null) {
                    int i = index;
                    if (i >= 0 && i < array.size && array.items[i] == value) {
                        removeAtAndFixup(idx, array, i);
                        EntityIndexMetrics.fastRemovals++;
                        return 1;
                    }
                    // Self-check failed: the index desynced. Latch, clear, fall back to the
                    // vanilla linear scan permanently — slow but always correct.
                    failed = true;
                    idx.map.clear();
                    EntityIndexMetrics.mismatchRemovals++;
                    LOGGER.error(
                            "StormEntityIndex self-check MISMATCH for {} in {} array (recorded"
                                    + " index {}, array size {}) — index desynced; reverting"
                                    + " entity removal to vanilla permanently",
                            value,
                            idx.label,
                            i,
                            array.size);
                    return 0;
                }
            }
            // Index miss (or an equals-based removal, which the identity index cannot answer):
            // do the vanilla-identical linear scan HERE so the swap fixup keeps the index
            // consistent — letting the vanilla body run would silently move the last element
            // without updating its recorded index.
            int found = -1;
            if (!identity && value != null) {
                for (int i = 0, n = array.size; i < n; i++) {
                    if (value.equals(array.items[i])) {
                        found = i;
                        break;
                    }
                }
            } else {
                for (int i = 0, n = array.size; i < n; i++) {
                    if (array.items[i] == value) {
                        found = i;
                        break;
                    }
                }
            }
            if (found < 0) {
                EntityIndexMetrics.scanRemovals++;
                return 2;
            }
            idx.map.remove(array.items[found]);
            removeAtAndFixup(idx, array, found);
            EntityIndexMetrics.scanRemovals++;
            return 1;
        } catch (Throwable t) {
            failed = true;
            LOGGER.error(
                    "StormEntityIndex removal failed — reverting entity removal to vanilla", t);
            return 0;
        }
    }

    /**
     * {@code array.removeIndex(index)} (swap-with-last on this unordered array) plus the index
     * fixup for the element that got swapped into {@code index}.
     */
    private static void removeAtAndFixup(
            EntityArrayIndex idx, zombie.entity.util.Array<?> array, int index) {
        array.removeIndex(index);
        if (index < array.size) {
            idx.map.put(array.items[index], index);
        }
    }

    /** Full O(n) re-index of a tracked array. Main thread only. */
    private static void rebuild(EntityArrayIndex idx, zombie.entity.util.Array<?> array) {
        idx.map.clear();
        for (int i = 0, n = array.size; i < n; i++) {
            idx.map.put(array.items[i], i);
        }
        idx.epoch = ENABLE_EPOCH.get();
        LOGGER.info("StormEntityIndex: rebuilt {} index over {} entities", idx.label, array.size);
    }

    /** Test-only full reset: clears the failure latch and re-arms the default kill switch. */
    static void resetForTesting() {
        failed = false;
        enabled = DEFAULT_ENABLED;
        engineIndex = null;
        trackedArrays = 0;
        ENABLE_EPOCH.incrementAndGet();
    }
}
