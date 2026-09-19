package io.pzstorm.storm.bullet.collision.dispatch;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.pzstorm.storm.UnitTest;
import org.junit.jupiter.api.Test;

/**
 * btUnionFind against stock Bullet 2.82 (g++ 10.5 -O3 -DBT_USE_DOUBLE_PRECISION). EXPECTED is the
 * (m_id, m_sz) array after sortIslands for six LCG-driven random union sequences; the order of
 * equal ids comes from the unstable quickSort, so it checks the sort exactly.
 */
class btUnionFindTest implements UnitTest {

    private static final int[][] EXPECTED = {
        {
            0, 1, 1, 1, 2, 1, 3, 1, 4, 1, 5, 1, 6, 1, 7, 1, 8, 1, 9, 1, 10, 1, 11, 1, 12, 1, 13, 1,
        },
        {
            0, 1, 1, 1, 2, 1, 3, 1, 5, 1, 6, 1, 7, 1, 8, 1, 9, 1, 10, 1, 11, 1, 12, 1, 13, 1, 14, 1,
            15, 2, 15, 1, 16, 1, 17, 1, 18, 1, 19, 1,
        },
        {
            0, 1, 0, 1, 0, 1, 0, 4, 1, 1, 3, 1, 6, 1, 7, 1, 8, 1, 8, 1, 8, 3, 9, 1, 11, 1, 12, 1,
            14, 1, 15, 1, 16, 1, 17, 1, 17, 2, 19, 1, 22, 1, 23, 1, 24, 1, 25, 1, 26, 2, 26, 1, 27,
            1, 28, 1, 29, 1, 30, 1, 31, 1, 32, 1, 35, 1, 36, 5, 36, 1, 36, 1, 36, 4, 36, 2, 37, 1,
            38, 1, 39, 1, 40, 1,
        },
        {
            17, 1, 17, 1, 17, 3, 17, 2, 17, 1, 17, 1, 17, 1, 17, 45, 17, 31, 17, 9, 17, 2, 17, 1,
            17, 1, 17, 10, 17, 3, 17, 2, 17, 1, 17, 1, 17, 26, 17, 4, 17, 46, 17, 1, 17, 1, 17, 1,
            17, 7, 17, 34, 17, 2, 17, 3, 17, 2, 17, 1, 17, 1, 17, 1, 17, 1, 17, 32, 17, 4, 17, 1,
            17, 1, 17, 43, 17, 39, 17, 2, 17, 1, 17, 47, 17, 30, 17, 1, 17, 1, 17, 13, 17, 40, 23,
            1,
        },
        {
            0, 1, 2, 1, 3, 1, 4, 1, 5, 1, 6, 1, 7, 1, 7, 2, 8, 1, 9, 1, 10, 1, 10, 2, 11, 1, 11, 2,
            12, 2, 12, 1, 14, 1, 15, 1, 16, 1, 17, 1, 18, 1, 19, 1, 21, 1, 21, 2, 22, 1, 23, 1, 24,
            2, 24, 1, 24, 3, 25, 1, 26, 1, 28, 1, 29, 1, 31, 1, 33, 1, 34, 1, 34, 2, 35, 1, 37, 1,
            38, 1, 39, 1, 40, 1, 41, 1, 42, 1, 43, 1, 44, 1, 45, 1, 46, 1, 47, 1, 48, 1, 49, 1, 50,
            1, 52, 1, 53, 1,
        },
        {
            0, 1, 8, 1, 10, 12, 10, 2, 10, 1, 10, 3, 10, 6, 10, 1, 10, 2, 10, 2, 10, 1, 10, 1, 10,
            1, 10, 1, 12, 1,
        },
    };

    private int s;

    private int rnd() {
        s = s * 1103515245 + 12345;
        return s >>> 8;
    }

    @Test
    void sortIslandsMatchesCpp() {
        for (int seed = 1; seed <= 6; seed++) {
            s = seed * 7919;
            int n = 5 + Integer.remainderUnsigned(rnd(), 60);
            int u = Integer.remainderUnsigned(rnd(), n * 2);
            btUnionFind uf = new btUnionFind();
            uf.reset(n);
            for (int k = 0; k < u; k++) {
                int a = Integer.remainderUnsigned(rnd(), n);
                int b = Integer.remainderUnsigned(rnd(), n);
                uf.unite(a, b);
            }
            uf.sortIslands();
            int[] got = new int[uf.getNumElements() * 2];
            for (int i = 0; i < uf.getNumElements(); i++) {
                got[2 * i] = uf.getElement(i).m_id;
                got[2 * i + 1] = uf.getElement(i).m_sz;
            }
            assertArrayEquals(EXPECTED[seed - 1], got, "seed " + seed);
        }
    }

    @Test
    void findAndUniteUsePathCompression() {
        btUnionFind uf = new btUnionFind();
        uf.reset(4);
        uf.unite(0, 1);
        uf.unite(1, 2);
        assertEquals(2, uf.find(0));
        assertEquals(1, uf.find(0, 2));
        assertEquals(0, uf.find(0, 3));
        assertEquals(3, uf.getElement(2).m_sz);
        uf.destroy();
        assertEquals(0, uf.getNumElements());
    }
}
