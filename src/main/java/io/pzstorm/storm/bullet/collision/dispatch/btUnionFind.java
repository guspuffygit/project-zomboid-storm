// Port of BulletCollision/CollisionDispatch/btUnionFind.h/.cpp (Bullet 2.82).
// USE_PATH_COMPRESSION and STATIC_SIMULATION_ISLAND_OPTIMIZATION are defined.
package io.pzstorm.storm.bullet.collision.dispatch;

import io.pzstorm.storm.bullet.linearmath.btAlignedObjectArray;
import java.util.function.BiPredicate;

/**
 * UnionFind calculates connected subsets. Implements weighted Quick Union with path compression.
 * {@code m_elements} is a value-mode array of {@link btElement} so resize/quickSort copy values as
 * in C++.
 */
public class btUnionFind {
    /** struct btElement */
    public static class btElement {
        public int m_id;
        public int m_sz;

        public btElement() {}

        public btElement(btElement other) {
            m_id = other.m_id;
            m_sz = other.m_sz;
        }

        public void set(btElement other) {
            m_id = other.m_id;
            m_sz = other.m_sz;
        }
    }

    /** class btUnionFindElementSortPredicate */
    public static final BiPredicate<btElement, btElement> btUnionFindElementSortPredicate =
            (lhs, rhs) -> lhs.m_id < rhs.m_id;

    public final btAlignedObjectArray<btElement> m_elements =
            new btAlignedObjectArray<>(btElement::new, btElement::set);

    public btUnionFind() {}

    /** {@code ~btUnionFind()} */
    public void destroy() {
        Free();
    }

    /**
     * this is a special operation, destroying the content of btUnionFind. it sorts the elements,
     * based on island id, in order to make it easy to iterate over islands
     */
    public void sortIslands() {
        // first store the original body index, and islandId
        int numElements = m_elements.size();

        for (int i = 0; i < numElements; i++) {
            m_elements.get(i).m_id = find(i);
        }

        // Sort the vector using predicate and std::sort
        m_elements.quickSort(btUnionFindElementSortPredicate);
    }

    public void reset(int N) {
        allocate(N);

        for (int i = 0; i < N; i++) {
            m_elements.get(i).m_id = i;
            m_elements.get(i).m_sz = 1;
        }
    }

    public int getNumElements() {
        return m_elements.size();
    }

    public boolean isRoot(int x) {
        return (x == m_elements.get(x).m_id);
    }

    public btElement getElement(int index) {
        return m_elements.get(index);
    }

    public void allocate(int N) {
        m_elements.resize(N);
    }

    public void Free() {
        m_elements.clear();
    }

    /** {@code int find(int p, int q)} */
    public int find(int p, int q) {
        return (find(p) == find(q)) ? 1 : 0;
    }

    public void unite(int p, int q) {
        int i = find(p), j = find(q);
        if (i == j) return;

        // USE_PATH_COMPRESSION
        m_elements.get(i).m_id = j;
        m_elements.get(j).m_sz += m_elements.get(i).m_sz;
    }

    public int find(int x) {
        while (x != m_elements.get(x).m_id) {
            // USE_PATH_COMPRESSION
            btElement elementPtr = m_elements.get(m_elements.get(x).m_id);
            m_elements.get(x).m_id = elementPtr.m_id;
            x = elementPtr.m_id;
        }
        return x;
    }
}
