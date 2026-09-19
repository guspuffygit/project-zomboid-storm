// Port of PZ WorldSimulation ballistics storage (WorldSimulation.cpp: std::map<int,PZBallistics*>
// @WS+0x128
// + std::deque<PZBallistics*> free list) from decomp @0014717c / Java_..._updateBallistics
// @001552e0
package io.pzstorm.storm.bullet.pz;

import java.util.ArrayDeque;
import java.util.TreeMap;

/**
 * The {@code m_ballistics} map plus its free-list deque. C++ has no class of this name:
 * WorldSimulation holds a {@code std::map<int, PZBallistics*>} and a {@code
 * std::deque<PZBallistics*>}; this class bundles both with the exact semantics of {@code
 * WorldSimulation::addBallistics / removeBallistics}.
 */
public class PZBallisticsPool {

    /** {@code std::map<int, PZBallistics*>} */
    public final TreeMap<Integer, PZBallistics> m_map = new TreeMap<>();

    /** {@code std::deque<PZBallistics*>} free list (pop_front / push_front: LIFO). */
    public final ArrayDeque<PZBallistics> m_pool = new ArrayDeque<>();

    /** {@code map.find(id) != map.end()} */
    public boolean containsKey(int id) {
        return m_map.containsKey(id);
    }

    /** {@code map.find(id)->second}, null when absent. */
    public PZBallistics get(int id) {
        return m_map.get(id);
    }

    public int size() {
        return m_map.size();
    }

    /**
     * {@code WorldSimulation::addBallistics(int)}: take the front of the free list (reset(id)) or
     * {@code new PZBallistics(id)}, then {@code map.emplace(id, p)} -- emplace does not overwrite
     * an existing key (the taken object is then leaked, as in C++).
     */
    public PZBallistics add(int id) {
        PZBallistics p;
        if (m_pool.isEmpty()) {
            p = new PZBallistics(id);
        } else {
            p = m_pool.pollFirst();
            p.reset(id);
        }
        m_map.putIfAbsent(id, p);
        return p;
    }

    /**
     * {@code WorldSimulation::removeBallistics(int)}: if found, push_front the object onto the free
     * list, then erase the key {@code p->getId()}.
     */
    public void remove(int id) {
        PZBallistics p = m_map.get(id);
        if (p == null) {
            return;
        }
        m_pool.addFirst(p);
        m_map.remove(p.getId());
    }
}
