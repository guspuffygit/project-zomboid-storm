// Port of LinearMath/btPoolAllocator.h (Bullet 2.82).
package io.pzstorm.storm.bullet.linearmath;

/**
 * Fixed-size pool. Memory is Java-managed; the pool is emulated with addresses so the free-list
 * order (LIFO), {@link #getFreeCount}, {@link #validPtr} and the address handed out by {@link
 * #allocate} match C++. {@code m_pool} is a fake base address from {@link btGlobals#btAlignedAlloc}
 * (counts as one aligned alloc, as C++). Callers map the returned address to their Java object
 * (e.g. {@code obj.addr = pool.allocate(size)}); an address can serve as a PTR-ORDER address.
 *
 * <p>Free list: C++ threads {@code next} pointers through the free elements; here {@code
 * m_nextFree[i]} holds the next free element index (-1 = null).
 */
public class btPoolAllocator {
    public final int m_elemSize;
    public final int m_maxElements;
    public int m_freeCount;

    /** Index of the first free element, -1 = null. */
    public int m_firstFree;

    /** Base address of the pool. */
    public long m_pool;

    private final int[] m_nextFree;

    public btPoolAllocator(int elemSize, int maxElements) {
        m_elemSize = elemSize;
        m_maxElements = maxElements;
        m_pool = btGlobals.btAlignedAlloc(Integer.toUnsignedLong(m_elemSize * m_maxElements), 16);
        m_nextFree = new int[Math.max(m_maxElements, 1)];
        int p = 0;
        m_firstFree = p;
        m_freeCount = m_maxElements;
        int count = m_maxElements;
        while (--count != 0) {
            m_nextFree[p] = p + 1;
            p++;
        }
        m_nextFree[p] = -1;
    }

    /** {@code ~btPoolAllocator()} */
    public void destroy() {
        btGlobals.btAlignedFree(m_pool);
    }

    public int getFreeCount() {
        return m_freeCount;
    }

    public int getUsedCount() {
        return m_maxElements - m_freeCount;
    }

    public int getMaxCount() {
        return m_maxElements;
    }

    /** Returns the element address (C++ {@code void*}); size is ignored in release builds. */
    public long allocate(int size) {
        int result = m_firstFree;
        m_firstFree = m_nextFree[m_firstFree];
        --m_freeCount;
        return m_pool + (long) result * m_elemSize;
    }

    public boolean validPtr(long ptr) {
        if (ptr != 0) {
            if (ptr >= m_pool && ptr < m_pool + (long) m_maxElements * m_elemSize) {
                return true;
            }
        }
        return false;
    }

    public void freeMemory(long ptr) {
        if (ptr != 0) {
            int idx = (int) ((ptr - m_pool) / m_elemSize);
            m_nextFree[idx] = m_firstFree;
            m_firstFree = idx;
            ++m_freeCount;
        }
    }

    public int getElementSize() {
        return m_elemSize;
    }

    public long getPoolAddress() {
        return m_pool;
    }
}
