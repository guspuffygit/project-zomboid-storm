package io.pzstorm.storm.entity;

/**
 * Implemented onto {@code zombie.entity.util.Array} by {@code EntityArrayRemoveFastPathPatch}
 * (load-time redefinition adds a {@code stormEntityArrayIndex} field plus this accessor pair), so
 * every {@code Array} instance can carry its own {@link StormEntityIndex} removal index.
 *
 * <p>Discrimination between indexed and plain arrays is a single field read: an array whose index
 * slot is {@code null} is untracked and the advice helpers bail immediately. Because the index
 * object is stored on the array itself, it dies with the array — no registry to leak stale entries
 * across world reloads, and no shared lookup structure for off-main-thread {@code Array} users to
 * race against (the injected field is volatile; a stale {@code null} read simply falls back to the
 * vanilla linear scan).
 *
 * <p>The accessor type is {@code Object} rather than {@code EntityArrayIndex} so the woven game
 * class only references this one Storm type.
 */
public interface StormIndexedArray {

    Object getStormEntityArrayIndex();

    void setStormEntityArrayIndex(Object index);
}
