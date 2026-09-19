// Port of PZ PZRagdollPool<PZRagdoll> (WorldSimulation.cpp: std::map<int,PZRagdoll*> @WS+0xa0 +
// std::deque<PZRagdoll*> @WS+0xd0; dtor @0017c780) with the pool code of
// WorldSimulation::addRagdoll @0017c490 / removeRagdoll @0017c590.
package io.pzstorm.storm.bullet.pz;

import java.util.ArrayDeque;
import java.util.TreeMap;

/** The ragdoll map plus its free-list deque. */
public class PZRagdollPool {

    /** {@code std::map<int, PZRagdoll*>} */
    public final TreeMap<Integer, PZRagdoll> m_map = new TreeMap<>();

    /** {@code std::deque<PZRagdoll*>} free list (pop_front / push_front: LIFO). */
    public final ArrayDeque<PZRagdoll> m_pool = new ArrayDeque<>();

    /** {@code map.find(id) != map.end()} */
    public boolean containsKey(int id) {
        return m_map.containsKey(id);
    }

    /** {@code map.find(id)->second}, null when absent. */
    public PZRagdoll get(int id) {
        return m_map.get(id);
    }

    public int size() {
        return m_map.size();
    }

    /**
     * Pool part of WorldSimulation::addRagdoll @0017c490: pop the front of the free list and {@code
     * reset(id)} it, or {@code new PZRagdoll(id)}; then {@code map.emplace(id, p)} (no overwrite of
     * an existing key). The caller then does {@code p.addToWorld(t)}.
     */
    public PZRagdoll add(int id) {
        PZRagdoll p;
        if (m_pool.isEmpty()) {
            p = new PZRagdoll(id);
        } else {
            p = m_pool.pollFirst();
            p.reset(id);
        }
        m_map.putIfAbsent(id, p);
        return p;
    }

    /**
     * WorldSimulation::removeRagdoll @0017c590: if found, {@code removeFromWorld()}, push_front
     * onto the free list, erase the key {@code p->m_id}.
     */
    public void remove(int id) {
        PZRagdoll p = m_map.get(id);
        if (p == null) {
            return;
        }
        p.removeFromWorld();
        m_pool.addFirst(p);
        m_map.remove(p.m_id);
    }

    /** Live ragdolls then pooled ones, in the order the JNI loops visit them. */
    public void forEachAll(java.util.function.Consumer<PZRagdoll> action) {
        for (PZRagdoll p : m_map.values()) {
            action.accept(p);
        }
        for (PZRagdoll p : new ArrayDeque<>(m_pool)) {
            action.accept(p);
        }
    }
}
