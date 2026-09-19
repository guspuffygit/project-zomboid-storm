package io.pzstorm.storm.bullet.collision.broadphase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.pzstorm.storm.UnitTest;
import io.pzstorm.storm.bullet.libm.GlibcRand;
import io.pzstorm.storm.bullet.linearmath.btAlignedObjectArray;
import io.pzstorm.storm.bullet.linearmath.btGlobals;
import io.pzstorm.storm.bullet.linearmath.btVector3;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Direct add/find/remove/removeContainingProxy/cleanProxy/processAll/sort/growth coverage of
 * btHashedOverlappingPairCache and btSortedOverlappingPairCache, with and without an overlap
 * filter, compared line by line against upstream Bullet 2.82 (GCC 10.5 -O3, double precision).
 * paircache-golden.txt.gz is the stdout of the C++ driver this class mirrors (same LCG, same call
 * order).
 */
class PairCacheGoldenTest implements UnitTest {

    /**
     * The C++ goldens ran in a fresh process. Other test classes in this JVM (the PZ world sets
     * gDeactivationTime and gContactAddedCallback) leave Bullet globals changed, so restore them.
     */
    @BeforeEach
    void freshLibrary() {
        btGlobals.resetAll();
        GlibcRand.srand(1);
    }

    private final List<String> out = new ArrayList<>();
    private int s = 777;

    private int rn() {
        s = s * 1664525 + 1013904223;
        return s >>> 8;
    }

    private void p(String l) {
        out.add(l);
    }

    private static final class Filt extends btOverlapFilterCallback {
        @Override
        public boolean needBroadphaseCollision(btBroadphaseProxy a, btBroadphaseProxy b) {
            return ((a.m_uniqueId + b.m_uniqueId) % 5) != 0;
        }
    }

    private final class Proc extends btOverlapCallback {
        int n;

        @Override
        public boolean processOverlap(btBroadphasePair pr) {
            n++;
            boolean r = ((pr.m_pProxy0.m_uniqueId * 3 + pr.m_pProxy1.m_uniqueId) % 4) == 0;
            p("PR " + pr.m_pProxy0.m_uniqueId + " " + pr.m_pProxy1.m_uniqueId + " " + (r ? 1 : 0));
            return r;
        }
    }

    private void dumpPairs(btOverlappingPairCache pc, boolean hashed) {
        btAlignedObjectArray<btBroadphasePair> a = pc.getOverlappingPairArray();
        if (hashed) {
            btHashedOverlappingPairCache h = (btHashedOverlappingPairCache) pc;
            p("H " + h.m_hashTable.size() + " " + h.m_next.size());
        }
        p("P " + a.size() + " " + pc.getNumOverlappingPairs());
        for (int i = 0; i < a.size(); i++)
            p("p " + a.get(i).m_pProxy0.m_uniqueId + " " + a.get(i).m_pProxy1.m_uniqueId);
    }

    private void run(boolean hashed, boolean filt) {
        p("== pc " + (hashed ? 1 : 0) + " " + (filt ? 1 : 0));
        btOverlappingPairCache pc =
                hashed ? new btHashedOverlappingPairCache() : new btSortedOverlappingPairCache();
        if (filt) pc.setOverlapFilterCallback(new Filt());
        btBroadphaseProxy[] px = new btBroadphaseProxy[200];
        for (int i = 0; i < 200; i++) {
            px[i] =
                    new btBroadphaseProxy(
                            new btVector3(0, 0, 0),
                            new btVector3(1, 1, 1),
                            null,
                            (short) (1 << (i % 3)),
                            (short) ((i % 5) == 0 ? 3 : -1));
            px[i].m_uniqueId = (i * 7) % 200 + 1;
        }
        for (int k = 0; k < 700; k++) {
            int a = Integer.remainderUnsigned(rn(), 200), b = Integer.remainderUnsigned(rn(), 200);
            if (a == b) continue;
            btBroadphasePair pr = pc.addOverlappingPair(px[a], px[b]);
            if (k % 50 == 0) {
                p("A " + a + " " + b + " " + (pr != null ? 1 : 0));
                dumpPairs(pc, hashed);
            }
        }
        dumpPairs(pc, hashed);
        int found = 0;
        for (int k = 0; k < 300; k++) {
            int a = Integer.remainderUnsigned(rn(), 200), b = Integer.remainderUnsigned(rn(), 200);
            btBroadphasePair pr = pc.findPair(px[a], px[b]);
            if (pr != null) {
                found++;
                p(
                        "FD "
                                + a
                                + " "
                                + b
                                + " "
                                + pr.m_pProxy0.m_uniqueId
                                + " "
                                + pr.m_pProxy1.m_uniqueId);
            }
        }
        p("FC " + found);
        for (int k = 0; k < 400; k++) {
            int a = Integer.remainderUnsigned(rn(), 200), b = Integer.remainderUnsigned(rn(), 200);
            if (a == b) continue;
            pc.removeOverlappingPair(px[a], px[b], null);
        }
        dumpPairs(pc, hashed);
        for (int k = 0; k < 8; k++) pc.removeOverlappingPairsContainingProxy(px[k * 23], null);
        dumpPairs(pc, hashed);
        pc.cleanProxyFromPairs(px[5], null);
        {
            Proc pr = new Proc();
            pc.processAllOverlappingPairs(pr, null);
            p("PN " + pr.n);
        }
        dumpPairs(pc, hashed);
        pc.sortOverlappingPairs(null);
        dumpPairs(pc, hashed);
        for (int k = 0; k < 300; k++) {
            int a = Integer.remainderUnsigned(rn(), 200), b = Integer.remainderUnsigned(rn(), 200);
            if (a == b) continue;
            pc.addOverlappingPair(px[a], px[b]);
        }
        dumpPairs(pc, hashed);
    }

    @Test
    void matchesUpstream() throws IOException {
        btGlobals.resetAddresses();
        run(true, false);
        run(true, true);
        run(false, false);
        run(false, true);
        List<String> exp = new ArrayList<>();
        try (InputStream raw =
                PairCacheGoldenTest.class.getResourceAsStream("paircache-golden.txt.gz")) {
            assertNotNull(raw, "paircache-golden.txt.gz resource");
            BufferedReader r =
                    new BufferedReader(
                            new InputStreamReader(
                                    new GZIPInputStream(raw), StandardCharsets.UTF_8));
            for (String line; (line = r.readLine()) != null; ) exp.add(line);
        }
        int n = Math.min(exp.size(), out.size());
        for (int i = 0; i < n; i++) assertEquals(exp.get(i), out.get(i), "golden line " + (i + 1));
        assertEquals(exp.size(), out.size(), "line count");
    }
}
