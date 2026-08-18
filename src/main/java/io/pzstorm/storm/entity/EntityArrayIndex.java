package io.pzstorm.storm.entity;

import java.util.IdentityHashMap;

/**
 * Per-array removal index carried by a tracked {@code zombie.entity.util.Array} through its {@link
 * StormIndexedArray#setStormEntityArrayIndex(Object)} slot. All mutation happens on the server main
 * thread (the engine's entity manager and its buckets are entirely unsynchronized main-thread
 * structures), so no locking is needed.
 */
final class EntityArrayIndex {

    /** Where this array lives, for log lines ("engine", bucket class name). */
    final String label;

    /** entity (identity) -> current index in the owning array. */
    final IdentityHashMap<Object, Integer> map = new IdentityHashMap<>();

    /**
     * {@link StormEntityIndex} enable-epoch this index was last built/maintained under. A stale
     * epoch (kill switch was off in between) means the map missed adds and must be rebuilt from the
     * array before its next use.
     */
    int epoch;

    EntityArrayIndex(String label) {
        this.label = label;
    }
}
