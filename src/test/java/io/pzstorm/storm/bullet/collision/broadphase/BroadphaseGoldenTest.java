package io.pzstorm.storm.bullet.collision.broadphase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.pzstorm.storm.UnitTest;
import io.pzstorm.storm.bullet.libm.GlibcRand;
import io.pzstorm.storm.bullet.linearmath.btAlignedObjectArray;
import io.pzstorm.storm.bullet.linearmath.btGlobals;
import io.pzstorm.storm.bullet.linearmath.btScalar;
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
 * Bit-exact comparison of the broadphase port against upstream Bullet 2.82 (double precision, GCC
 * 10.5 -O3, x86-64).
 *
 * <p>broadphase-golden.txt.gz is the stdout of a C++ driver linked against the stock
 * BroadphaseCollision sources. This class is a line-by-line port of that driver (same LCG, same
 * call order, same print formats); every double is compared by its raw bits. It covers btDbvt
 * insert/update/remove/optimize/collide/rayTest/clone/write, btDbvtBroadphase with the hashed and
 * the sorted pair cache (pair order, hash-table growth, stage rotation), and btQuantizedBvh
 * quantize/unQuantize/buildInternal/buildTree plus all traversal modes.
 *
 * <p>The only masked field: in the btDbvtBroadphase tree dumps a leaf's {@code dataAsInt} is the
 * low 32 bits of the proxy pointer in C++, so the id on those {@code L} lines is not compared.
 */
class BroadphaseGoldenTest implements UnitTest {

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
    private int s = 12345;

    private double rnd() {
        s = s * 1664525 + 1013904223;
        return (double) (s >>> 8) / 16777216.0;
    }

    private static String hx(double d) {
        return String.format("%016x", Double.doubleToRawLongBits(d));
    }

    private static String pv(btVector3 v) {
        return " " + hx(v.x()) + " " + hx(v.y()) + " " + hx(v.z());
    }

    private btDbvtAabbMm rvol() {
        double cx = rnd() * 20 - 10;
        double cy = rnd() * 20 - 10;
        double cz = rnd() * 20 - 10;
        double ex = rnd() * 2 + 0.1;
        double ey = rnd() * 2 + 0.1;
        double ez = rnd() * 2 + 0.1;
        return btDbvtAabbMm.FromCE(new btVector3(cx, cy, cz), new btVector3(ex, ey, ez));
    }

    private btVector3 rvec(double sc) {
        double x = rnd() * sc - sc / 2;
        double y = rnd() * sc - sc / 2;
        double z = rnd() * sc - sc / 2;
        return new btVector3(x, y, z);
    }

    private void p(String line) {
        out.add(line);
    }

    private void dump(btDbvtNode n) {
        if (n == null) {
            p("N");
            return;
        }
        if (n.isleaf()) {
            p("L " + n.dataAsInt + pv(n.volume.mi) + pv(n.volume.mx));
        } else {
            p("I" + pv(n.volume.mi) + pv(n.volume.mx));
            dump(n.childs[0]);
            dump(n.childs[1]);
        }
    }

    private final class PrintLeaf extends btDbvt.ICollide {
        final String tag;

        PrintLeaf(String t) {
            tag = t;
        }

        @Override
        public void Process(btDbvtNode n) {
            p(tag + " " + n.dataAsInt);
        }

        @Override
        public void Process(btDbvtNode a, btDbvtNode b) {
            p(tag + " " + a.dataAsInt + " " + b.dataAsInt);
        }
    }

    private void dbvtTest() {
        p("== dbvt");
        btDbvt t = new btDbvt();
        btDbvtNode[] leaves = new btDbvtNode[80];
        for (int i = 0; i < 64; i++) {
            btDbvtAabbMm v = rvol();
            leaves[i] = t.insert(v, Integer.valueOf(i));
        }
        p("T1 " + t.m_leaves + " " + btDbvt.maxdepth(t.m_root));
        dump(t.m_root);
        for (int k = 0; k < 20; k++) {
            btDbvtAabbMm v = rvol();
            t.update(leaves[(k * 7) % 64], v);
        }
        for (int k = 0; k < 10; k++) {
            btDbvtAabbMm v = rvol();
            btVector3 vel = rvec(4);
            double m = rnd() * 0.2;
            boolean r = t.update(leaves[(k * 11 + 3) % 64], v, vel, m);
            p("U " + (r ? 1 : 0));
        }
        for (int k = 0; k < 6; k++) {
            btDbvtAabbMm v = rvol();
            boolean r = t.update(leaves[(k * 13 + 1) % 64], v, 0.05);
            p("UM " + (r ? 1 : 0));
        }
        for (int k = 0; k < 12; k++) {
            t.remove(leaves[k * 5]);
            leaves[k * 5] = null;
        }
        for (int i = 0; i < 10; i++) {
            btDbvtAabbMm v = rvol();
            t.insert(v, Integer.valueOf(100 + i));
        }
        p("T2 " + t.m_leaves + " " + btDbvt.maxdepth(t.m_root));
        dump(t.m_root);
        t.optimizeIncremental(10);
        p("T3 " + Integer.toUnsignedString(t.m_opath));
        dump(t.m_root);
        {
            btDbvtAabbMm q = btDbvtAabbMm.FromCE(new btVector3(1, -2, 0.5), new btVector3(4, 5, 3));
            t.collideTV(t.m_root, q, new PrintLeaf("TV"));
        }
        btDbvt.rayTest(
                t.m_root,
                new btVector3(-12, -9, -11),
                new btVector3(11, 10, 9),
                new PrintLeaf("RAY"));
        t.collideTTpersistentStack(t.m_root, t.m_root, new PrintLeaf("TT"));
        btDbvt t2 = new btDbvt(); // leaked, as in the driver
        t.clone(
                t2,
                new btDbvt.IClone() {
                    @Override
                    public void CloneLeaf(btDbvtNode n) {
                        p("CLN " + n.dataAsInt);
                    }
                });
        p("C " + t2.m_leaves);
        dump(t2.m_root);
        t.update(leaves[7], 2);
        t.update(leaves[9]);
        p("T4");
        dump(t.m_root);
        t.optimizeTopDown();
        p("T5");
        dump(t.m_root);
        t.optimizeBottomUp();
        p("T6");
        dump(t.m_root);
        t.optimizeIncremental(-1);
        p("T7 " + Integer.toUnsignedString(t.m_opath));
        dump(t.m_root);
        t.write(
                new btDbvt.IWriter() {
                    @Override
                    public void Prepare(btDbvtNode root, int numnodes) {
                        p("W prepare " + numnodes);
                    }

                    @Override
                    public void WriteNode(btDbvtNode n, int index, int parent, int c0, int c1) {
                        p("W node " + index + " " + parent + " " + c0 + " " + c1);
                    }

                    @Override
                    public void WriteLeaf(btDbvtNode n, int index, int parent) {
                        p("W leaf " + index + " " + parent + " " + n.dataAsInt);
                    }
                });
        p("CL " + btDbvt.countLeaves(t.m_root));
        btAlignedObjectArray<btDbvtNode> ex = new btAlignedObjectArray<>();
        btDbvt.extractLeaves(t.m_root, ex);
        for (int i = 0; i < ex.size(); i++) p("EX " + ex.get(i).dataAsInt);
        ex.clear();
        t.clear();
        t.destroy();
    }

    private void dumpPairs(btOverlappingPairCache pc) {
        btAlignedObjectArray<btBroadphasePair> a = pc.getOverlappingPairArray();
        p("P " + a.size());
        for (int i = 0; i < a.size(); i++)
            p("p " + a.get(i).m_pProxy0.m_uniqueId + " " + a.get(i).m_pProxy1.m_uniqueId);
    }

    private void bpTest(boolean sorted) {
        p("== bp " + (sorted ? 1 : 0));
        btOverlappingPairCache pc = sorted ? new btSortedOverlappingPairCache() : null;
        btDbvtBroadphase bp = new btDbvtBroadphase(pc);
        btOverlappingPairCache cache = bp.getOverlappingPairCache();
        btBroadphaseProxy[] px = new btBroadphaseProxy[60];
        for (int i = 0; i < 60; i++) {
            btDbvtAabbMm v = rvol();
            px[i] =
                    bp.createProxy(
                            v.mi,
                            v.mx,
                            0,
                            Integer.valueOf(i + 1),
                            (short) 1,
                            (short) -1,
                            null,
                            null);
        }
        if (!sorted) p("H " + ((btHashedOverlappingPairCache) cache).m_hashTable.size());
        dumpPairs(cache);
        for (int f = 0; f < 6; f++) {
            for (int i = 0; i < 60; i++) {
                if (px[i] == null) continue;
                btVector3 d = rvec(1.5);
                btVector3 mn = px[i].m_aabbMin.add(d);
                btVector3 mx = px[i].m_aabbMax.add(d);
                if (i % 4 == 0) bp.setAabbForceUpdate(px[i], mn, mx, null);
                else bp.setAabb(px[i], mn, mx, null);
            }
            bp.calculateOverlappingPairs(null);
            p("F " + f + " " + bp.m_stageCurrent + " " + bp.m_fupdates + " " + bp.m_dupdates);
            if (!sorted) p("H " + ((btHashedOverlappingPairCache) cache).m_hashTable.size());
            dumpPairs(cache);
            if (f == 2)
                for (int k = 0; k < 10; k++) {
                    bp.destroyProxy(px[k * 6 + 1], null);
                    px[k * 6 + 1] = null;
                }
        }
        bp.aabbTest(
                new btVector3(-5, -5, -5),
                new btVector3(3, 4, 5),
                new btBroadphaseAabbCallback() {
                    @Override
                    public boolean process(btBroadphaseProxy proxy) {
                        p("AB " + proxy.m_uniqueId);
                        return true;
                    }
                });
        {
            btBroadphaseRayCallback cb =
                    new btBroadphaseRayCallback() {
                        @Override
                        public boolean process(btBroadphaseProxy proxy) {
                            p("RB " + proxy.m_uniqueId);
                            return true;
                        }
                    };
            btVector3 from = new btVector3(-15, -8, -12), to = new btVector3(14, 9, 11);
            btVector3 dir = (to.sub(from)).normalized();
            cb.m_rayDirectionInverse.set(
                    0, dir.get(0) == 0.0 ? btScalar.BT_LARGE_FLOAT : 1.0 / dir.get(0));
            cb.m_rayDirectionInverse.set(
                    1, dir.get(1) == 0.0 ? btScalar.BT_LARGE_FLOAT : 1.0 / dir.get(1));
            cb.m_rayDirectionInverse.set(
                    2, dir.get(2) == 0.0 ? btScalar.BT_LARGE_FLOAT : 1.0 / dir.get(2));
            cb.m_signs[0] = cb.m_rayDirectionInverse.get(0) < 0.0 ? 1 : 0;
            cb.m_signs[1] = cb.m_rayDirectionInverse.get(1) < 0.0 ? 1 : 0;
            cb.m_signs[2] = cb.m_rayDirectionInverse.get(2) < 0.0 ? 1 : 0;
            cb.m_lambda_max = dir.dot(to.sub(from));
            bp.rayTest(from, to, cb, new btVector3(-0.5, -0.5, -0.5), new btVector3(0.5, 0.5, 0.5));
        }
        btVector3 bmin = new btVector3(), bmax = new btVector3();
        bp.getBroadphaseAabb(bmin, bmax);
        p("BA" + pv(bmin) + pv(bmax));
        bp.optimize();
        p("O");
        dump(bp.m_sets[0].m_root);
        dump(bp.m_sets[1].m_root);
        btBroadphasePair fp = cache.findPair(px[2], px[3]);
        p("FP " + (fp != null ? 1 : 0));
        bp.destroy(); // stack object destructor (leaves the proxies and a caller-owned cache)
    }

    private final class PrintNode extends btNodeOverlapCallback {
        final String tag;

        PrintNode(String t) {
            tag = t;
        }

        @Override
        public void processNode(int part, int tri) {
            p(tag + " " + part + " " + tri);
        }
    }

    private static String u3(int[] a) {
        return a[0] + " " + a[1] + " " + a[2];
    }

    private void qbvhTest() {
        p(
                "== qbvh "
                        + btQuantizedBvh.SIZEOF
                        + " "
                        + btQuantizedBvhNode.SIZEOF
                        + " "
                        + btOptimizedBvhNode.SIZEOF
                        + " "
                        + btBvhSubtreeInfo.SIZEOF);
        btQuantizedBvh b = new btQuantizedBvh();
        b.setQuantizationValues(new btVector3(-50, -20, -30), new btVector3(60, 25, 35));
        p("Q" + pv(b.m_bvhAabbMin) + pv(b.m_bvhAabbMax) + pv(b.m_bvhQuantization));
        double nan = Double.longBitsToDouble(0xfff8000000000000L); // x86 0.0/0.0
        double[][] pts = {
            {-50, -20, -30},
            {60, 25, 35},
            {0, 0, 0},
            {-100, 50, 1e6},
            {-51.5, -21.5, -31.5},
            {61.5, 26.5, 36.5},
            {1.234567, -7.654321, 33.3},
            {-1e300, 1e300, nan}
        };
        for (double[] pt : pts) {
            int[] o0 = new int[3], o1 = new int[3], c0 = new int[3], c1 = new int[3];
            btVector3 v = new btVector3(pt[0], pt[1], pt[2]);
            b.quantize(o0, v, 0);
            b.quantize(o1, v, 1);
            b.quantizeWithClamp(c0, v, 0);
            b.quantizeWithClamp(c1, v, 1);
            p(
                    "q "
                            + u3(o0)
                            + " "
                            + u3(o1)
                            + " "
                            + u3(c0)
                            + " "
                            + u3(c1)
                            + pv(b.unQuantize(c0))
                            + pv(b.unQuantize(c1)));
        }
        for (int i = 0; i < 300; i++) {
            double cx = rnd() * 100 - 45;
            double cy = rnd() * 40 - 18;
            double cz = rnd() * 60 - 28;
            double ex = rnd() * 1.5;
            double ey = rnd() * 1.5;
            double ez = rnd() * 1.5;
            btQuantizedBvhNode n = new btQuantizedBvhNode();
            b.quantize(n.m_quantizedAabbMin, new btVector3(cx - ex, cy - ey, cz - ez), 0);
            b.quantize(n.m_quantizedAabbMax, new btVector3(cx + ex, cy + ey, cz + ez), 1);
            n.m_escapeIndexOrTriangleIndex =
                    ((i % 3) << (31 - btQuantizedBvh.MAX_NUM_PARTS_IN_BITS)) | i;
            b.m_quantizedLeafNodes.push_back(n);
        }
        b.buildInternal();
        p(
                "B "
                        + b.m_curNodeIndex
                        + " "
                        + b.m_SubtreeHeaders.size()
                        + " "
                        + b.m_subtreeHeaderCount
                        + " "
                        + Integer.toUnsignedString(b.calculateSerializeBufferSize()));
        for (int i = 0; i < b.m_curNodeIndex; i++) {
            btQuantizedBvhNode n = b.m_quantizedContiguousNodes.get(i);
            p(
                    "n "
                            + u3(n.m_quantizedAabbMin)
                            + " "
                            + u3(n.m_quantizedAabbMax)
                            + " "
                            + n.m_escapeIndexOrTriangleIndex);
        }
        for (int i = 0; i < b.m_SubtreeHeaders.size(); i++) {
            btBvhSubtreeInfo n = b.m_SubtreeHeaders.get(i);
            p(
                    "s "
                            + u3(n.m_quantizedAabbMin)
                            + " "
                            + u3(n.m_quantizedAabbMax)
                            + " "
                            + n.m_rootNodeIndex
                            + " "
                            + n.m_subtreeSize);
        }
        for (int mode = 0; mode < 3; mode++) {
            b.setTraversalMode(mode);
            btGlobals.maxIterations = 0;
            b.reportAabbOverlappingNodex(
                    new PrintNode("QA"), new btVector3(-10, -5, -8), new btVector3(12, 6, 9));
            p("MI " + btGlobals.maxIterations);
        }
        btGlobals.maxIterations = 0;
        b.reportRayOverlappingNodex(
                new PrintNode("QR"), new btVector3(-48, -17, -25), new btVector3(55, 20, 30));
        p("MI " + btGlobals.maxIterations);
        btGlobals.maxIterations = 0;
        b.reportBoxCastOverlappingNodex(
                new PrintNode("QB"),
                new btVector3(-40, 0, 0),
                new btVector3(50, 1, 2),
                new btVector3(-1, -2, -1.5),
                new btVector3(1, 2, 1.5));
        p("MI " + btGlobals.maxIterations);
        btGlobals.maxIterations = 0;
        b.reportRayOverlappingNodex(
                new PrintNode("QZ"), new btVector3(3, -30, 4), new btVector3(3, 30, 4));
        p("MI " + btGlobals.maxIterations);

        btQuantizedBvh c = new btQuantizedBvh();
        for (int i = 0; i < 50; i++) {
            double cx = rnd() * 40 - 20;
            double cy = rnd() * 40 - 20;
            double cz = rnd() * 40 - 20;
            double ex = rnd() * 2;
            double ey = rnd() * 2;
            double ez = rnd() * 2;
            btOptimizedBvhNode n = new btOptimizedBvhNode();
            n.m_aabbMinOrg.set(new btVector3(cx - ex, cy - ey, cz - ez));
            n.m_aabbMaxOrg.set(new btVector3(cx + ex, cy + ey, cz + ez));
            n.m_escapeIndex = -1;
            n.m_subPart = i % 4;
            n.m_triangleIndex = i;
            c.m_leafNodes.push_back(n);
        }
        c.m_contiguousNodes.resize(100);
        c.m_curNodeIndex = 0;
        c.buildTree(0, 50);
        p(
                "B2 "
                        + c.m_curNodeIndex
                        + " "
                        + Integer.toUnsignedString(c.calculateSerializeBufferSize()));
        for (int i = 0; i < c.m_curNodeIndex; i++) {
            btOptimizedBvhNode n = c.m_contiguousNodes.get(i);
            p(
                    "o"
                            + pv(n.m_aabbMinOrg)
                            + pv(n.m_aabbMaxOrg)
                            + " "
                            + n.m_escapeIndex
                            + " "
                            + n.m_subPart
                            + " "
                            + n.m_triangleIndex);
        }
        btGlobals.maxIterations = 0;
        c.reportAabbOverlappingNodex(
                new PrintNode("UA"), new btVector3(-6, -7, -5), new btVector3(8, 5, 6));
        p("MI " + btGlobals.maxIterations);
        btGlobals.maxIterations = 0;
        c.reportRayOverlappingNodex(
                new PrintNode("UR"), new btVector3(-20, -18, -19), new btVector3(19, 21, 17));
        p("MI " + btGlobals.maxIterations);
        btGlobals.maxIterations = 0;
        c.reportBoxCastOverlappingNodex(
                new PrintNode("UB"),
                new btVector3(-20, 0, 0),
                new btVector3(20, 0, 0),
                new btVector3(-3, -3, -3),
                new btVector3(3, 3, 3));
        p("MI " + btGlobals.maxIterations);
    }

    private static List<String> loadGolden() throws IOException {
        List<String> g = new ArrayList<>();
        try (InputStream raw =
                BroadphaseGoldenTest.class.getResourceAsStream("broadphase-golden.txt.gz")) {
            assertNotNull(raw, "broadphase-golden.txt.gz resource");
            BufferedReader r =
                    new BufferedReader(
                            new InputStreamReader(
                                    new GZIPInputStream(raw), StandardCharsets.UTF_8));
            for (String line; (line = r.readLine()) != null; ) g.add(line);
        }
        return g;
    }

    /** In the btDbvtBroadphase sections a leaf's dataAsInt is a pointer in C++: mask it. */
    private static List<String> normalize(List<String> in) {
        List<String> res = new ArrayList<>(in.size());
        boolean bp = false;
        for (String l : in) {
            if (l.startsWith("== ")) bp = l.startsWith("== bp");
            if (bp && l.startsWith("L ")) {
                int sp = l.indexOf(' ', 2);
                l = "L *" + l.substring(sp);
            }
            res.add(l);
        }
        return res;
    }

    @Test
    void matchesUpstream() throws IOException {
        btGlobals.resetAddresses();
        dbvtTest();
        bpTest(false);
        bpTest(true);
        qbvhTest();
        List<String> exp = normalize(loadGolden());
        List<String> act = normalize(out);
        int n = Math.min(exp.size(), act.size());
        for (int i = 0; i < n; i++) {
            assertEquals(exp.get(i), act.get(i), "golden line " + (i + 1));
        }
        assertEquals(exp.size(), act.size(), "line count");
    }
}
