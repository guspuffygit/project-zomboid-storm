// Port of PZ WorldSimulation ballistics storage (WorldSimulation.cpp:
// std::map<int,PZBallisticsTarget*> @WS+0x1a8
// + std::deque<PZBallisticsTarget*> free list) from decomp Java_..._addBallisticsTarget @00156ba0 /
// removeBallisticsTarget @00156dc0
package io.pzstorm.storm.bullet.pz;

import java.util.ArrayDeque;
import java.util.TreeMap;

/**
 * The {@code m_ballisticsTargets} map plus its free-list deque. C++ has no class of this name:
 * WorldSimulation holds a {@code std::map<int, PZBallisticsTarget*>} and a {@code
 * std::deque<PZBallisticsTarget*>}; this class bundles both with the exact semantics of the inlined
 * pool code of the addBallisticsTarget / removeBallisticsTarget JNI entries.
 */
public class PZBallisticsTargetPool {

    /** {@code std::map<int, PZBallisticsTarget*>} */
    public final TreeMap<Integer, PZBallisticsTarget> m_map = new TreeMap<>();

    /** {@code std::deque<PZBallisticsTarget*>} free list (pop_front / push_front: LIFO). */
    public final ArrayDeque<PZBallisticsTarget> m_pool = new ArrayDeque<>();

    /** {@code map.find(id) != map.end()} */
    public boolean containsKey(int id) {
        return m_map.containsKey(id);
    }

    /** {@code map.find(id)->second}, null when absent. */
    public PZBallisticsTarget get(int id) {
        return m_map.get(id);
    }

    public int size() {
        return m_map.size();
    }

    /**
     * {@code add path of Java_..._addBallisticsTarget @00156ba0 (also
     * updateBallisticsTarget[Skeleton])}: take the front of the free list (reset(id)) or {@code new
     * PZBallisticsTarget(id)}, then {@code map.emplace(id, p)} -- emplace does not overwrite an
     * existing key (the taken object is then leaked, as in C++).
     */
    public PZBallisticsTarget add(int id) {
        PZBallisticsTarget p;
        if (m_pool.isEmpty()) {
            p = new PZBallisticsTarget(id);
        } else {
            p = m_pool.pollFirst();
            p.reset(id);
        }
        m_map.putIfAbsent(id, p);
        return p;
    }

    /**
     * {@code Java_..._removeBallisticsTarget @00156dc0 (after removeFromWorld)}: if found,
     * push_front the object onto the free list, then erase the key {@code p->getId()}.
     */
    public void remove(int id) {
        PZBallisticsTarget p = m_map.get(id);
        if (p == null) {
            return;
        }
        m_pool.addFirst(p);
        m_map.remove(p.getId());
    }
}
