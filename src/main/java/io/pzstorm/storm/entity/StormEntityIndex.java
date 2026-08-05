package io.pzstorm.storm.entity;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.metrics.EntityIndexMetrics;
import io.pzstorm.storm.metrics.StormPerformanceSandboxMetrics;
import java.lang.reflect.Field;
import java.util.IdentityHashMap;

/**
 * O(1) removal index for the engine's global entity array, replacing the linear identity scan in
 * {@code EngineEntityManager.removeEntityInternal}'s {@code entities.removeValue(entity, true)}
 * call (~123k entities live on the profiled server; {@code Array.removeValue} showed 3.1% self time
 * on the main thread during cell-unload bursts).
 *
 * <p><b>Why this is sound.</b> {@code EngineEntityManager.entities} is a {@code private final
 * Array<GameEntity>} constructed unordered ({@code new Array<>(false, 16)}), so {@code
 * Array.removeIndex} is swap-with-last O(1) — the whole cost of a removal is the linear index
 * search. The field is never leaked mutably: the only wrapper is {@code ImmutableArray} (read-only
 * views; {@code toArray()} copies), and the only mutation sites in the entire game source are
 * {@code addEntityInternal}'s single-arg {@code entities.add(entity)} and {@code
 * removeEntityInternal}'s {@code entities.removeValue(entity, true)}. No clear/sort/set/insert ever
 * touches it; world reload goes through {@code GameEntityManager.Init}, which constructs a
 * brand-new {@code Engine} &rarr; {@code EngineEntityManager} &rarr; empty array (the constructor
 * advice re-tracks it and resets this index). Both mutation sites run on the server main thread
 * (the vanilla manager is entirely unsynchronized), so the index needs no locking.
 *
 * <p><b>How it is maintained.</b> Three advices feed this class:
 *
 * <ul>
 *   <li>{@code EngineEntityManagerCreatedAdvice} (constructor exit) calls {@link
 *       #onManagerCreated(Object)} — reflects the {@code entities} field and makes that exact
 *       {@code Array} instance the tracked one.
 *   <li>{@code EntityArrayAddAdvice} ({@code Array.add(T)} exit) calls {@link #onArrayAdd} — for
 *       the tracked instance only, records {@code value -> size - 1} at the instant of the append.
 *       Every other {@code Array} in the JVM pays one reference compare and returns.
 *   <li>{@code EntityArrayRemoveValueAdvice} ({@code Array.removeValue} enter) calls {@link
 *       #onRemoveValue} — for the tracked instance, performs the removal itself (index lookup,
 *       identity self-check, {@code removeIndex}, swapped-element index fixup) and skips the
 *       vanilla linear scan. Untracked arrays fall through to vanilla untouched.
 * </ul>
 *
 * <p><b>Self-check.</b> Before an indexed removal the helper verifies {@code items[index] ==
 * entity}. On mismatch (which would mean the index desynced — a bug) it latches {@link #failed},
 * logs loudly, and falls back to the vanilla linear scan permanently. A wrong-entity removal is
 * therefore impossible: the index is only ever used after an identity match.
 *
 * <p><b>Kill switch.</b> {@code Storm.EntityRemoveFastPath} (boolean, default on). While disabled
 * the index is not maintained, so re-enabling schedules a full O(n) rebuild from the tracked array;
 * the rebuild runs inline on the next main-thread touch so all index mutations stay on the main
 * thread. Removals that miss the index (only possible around toggles) are handled by an inline
 * identity scan with the same swap fixup, keeping the index consistent.
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
     * Set when the index must be rebuilt from the tracked array before its next use (enable
     * toggle). Consumed on the main thread only, so index mutations never happen off-thread.
     */
    private static volatile boolean needsRebuild;

    /** The {@code EngineEntityManager.entities} Array instance; null until a manager exists. */
    private static Object tracked;

    /** entity (identity) -> current index in the tracked array. Main-thread only. */
    private static final IdentityHashMap<Object, Integer> INDEX = new IdentityHashMap<>();

    private static Field entitiesField;

    private StormEntityIndex() {}

    /**
     * Applies the {@code Storm.EntityRemoveFastPath} sandbox option and pushes the applied value to
     * the Prometheus gauge. Enabling schedules a rebuild (the index went stale while off);
     * disabling stops maintenance and consultation immediately.
     *
     * @return the applied value
     */
    public static boolean setEnabled(boolean value) {
        boolean was = enabled;
        enabled = value;
        if (value && !was) {
            needsRebuild = true;
        }
        StormPerformanceSandboxMetrics.setEntityRemoveFastPath(value);
        return value;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** Current index size; dirty-read by the scrape callback. */
    public static int indexSize() {
        return INDEX.size();
    }

    /**
     * Called from {@code EngineEntityManagerCreatedAdvice} when a new manager (new world) is
     * constructed. Tracks that manager's freshly-created, empty entity array.
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
            zombie.entity.util.Array<?> entities =
                    (zombie.entity.util.Array<?>) entitiesField.get(managerObj);
            if (entities.ordered) {
                // The whole design rests on swap-with-last removeIndex; an ordered array would
                // shift instead and every fixup would be wrong. Never true today (constructed
                // with ordered=false) — refuse to track rather than desync if that changes.
                failed = true;
                LOGGER.error(
                        "StormEntityIndex: EngineEntityManager.entities is now ordered —"
                                + " refusing to track; entity removal stays vanilla");
                return;
            }
            tracked = entities;
            INDEX.clear();
            needsRebuild = false;
            LOGGER.info("StormEntityIndex: tracking new EngineEntityManager entity array");
        } catch (Throwable t) {
            failed = true;
            tracked = null;
            LOGGER.error(
                    "StormEntityIndex failed to track EngineEntityManager — entity removal stays"
                            + " vanilla",
                    t);
        }
    }

    /**
     * Called from {@code EntityArrayAddAdvice} after every single-arg {@code Array.add}. Only the
     * tracked instance is recorded; everything else returns after one reference compare.
     */
    public static void onArrayAdd(Object arrayObj, Object value) {
        if (arrayObj != tracked || failed || !enabled) {
            return;
        }
        try {
            zombie.entity.util.Array<?> array = (zombie.entity.util.Array<?>) arrayObj;
            if (needsRebuild) {
                rebuild(array);
                return; // rebuild scanned the array post-append; value is already indexed.
            }
            INDEX.put(value, array.size - 1);
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
        if (arrayObj != tracked) {
            return 0;
        }
        if (failed || !enabled) {
            EntityIndexMetrics.vanillaRemovals++;
            return 0;
        }
        try {
            zombie.entity.util.Array<?> array = (zombie.entity.util.Array<?>) arrayObj;
            if (needsRebuild) {
                rebuild(array);
            }
            if (identity || value == null) {
                Integer index = INDEX.remove(value);
                if (index != null) {
                    int i = index;
                    if (i >= 0 && i < array.size && array.items[i] == value) {
                        removeAtAndFixup(array, i);
                        EntityIndexMetrics.fastRemovals++;
                        return 1;
                    }
                    // Self-check failed: the index desynced. Latch, clear, fall back to the
                    // vanilla linear scan permanently — slow but always correct.
                    failed = true;
                    INDEX.clear();
                    EntityIndexMetrics.mismatchRemovals++;
                    LOGGER.error(
                            "StormEntityIndex self-check MISMATCH for {} (recorded index {},"
                                    + " array size {}) — index desynced; reverting entity removal"
                                    + " to vanilla permanently",
                            value,
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
            INDEX.remove(array.items[found]);
            removeAtAndFixup(array, found);
            EntityIndexMetrics.scanRemovals++;
            return 1;
        } catch (Throwable t) {
            failed = true;
            INDEX.clear();
            LOGGER.error(
                    "StormEntityIndex removal failed — reverting entity removal to vanilla", t);
            return 0;
        }
    }

    /**
     * {@code array.removeIndex(index)} (swap-with-last on this unordered array) plus the index
     * fixup for the element that got swapped into {@code index}.
     */
    private static void removeAtAndFixup(zombie.entity.util.Array<?> array, int index) {
        array.removeIndex(index);
        if (index < array.size) {
            INDEX.put(array.items[index], index);
        }
    }

    /** Full O(n) re-index of the tracked array. Main thread only. */
    private static void rebuild(zombie.entity.util.Array<?> array) {
        INDEX.clear();
        for (int i = 0, n = array.size; i < n; i++) {
            INDEX.put(array.items[i], i);
        }
        needsRebuild = false;
        LOGGER.info("StormEntityIndex: rebuilt index over {} entities", array.size);
    }
}
