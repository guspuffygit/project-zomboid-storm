// Port of PZ glue ObjectPool<T> (template; ~ObjectPool<ChunkLevel> @0014d560).
// A std::deque<T*> of free objects: alloc pops the front (or news one), release pushes the front.
package io.pzstorm.storm.bullet.pz;

import java.util.ArrayDeque;
import java.util.function.Supplier;

public class ObjectPool<T> {

    public final ArrayDeque<T> m_pool = new ArrayDeque<>();
    private final Supplier<T> m_factory;

    public ObjectPool(Supplier<T> factory) {
        m_factory = factory;
    }

    public T alloc() {
        if (m_pool.isEmpty()) {
            return m_factory.get();
        }
        return m_pool.pollFirst();
    }

    public void release(T obj) {
        m_pool.addFirst(obj);
    }
}
