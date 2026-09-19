package io.pzstorm.storm.bullet.collision.shapes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.pzstorm.storm.UnitTest;
import io.pzstorm.storm.bullet.libm.GlibcRand;
import io.pzstorm.storm.bullet.linearmath.btGlobals;
import io.pzstorm.storm.bullet.linearmath.btQuaternion;
import io.pzstorm.storm.bullet.linearmath.btTransform;
import io.pzstorm.storm.bullet.linearmath.btVector3;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Bit-exact differential test against upstream Bullet 2.82 (g++ 10.5 -O3 -DBT_USE_DOUBLE_PRECISION,
 * x86-64). shapes-golden.txt is the output of a C++ driver that runs exactly the calls below and
 * prints every double as its raw 64-bit pattern; this test replays the same calls on the Java port
 * and compares line by line.
 */
class ShapesGoldenTest implements UnitTest {

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

    /**
     * Raw bits, except any NaN prints as "nan": the default-NaN sign is a CPU property (x86 SSE
     * produces 0xfff8..., arm64 0x7ff8...) and Java does not specify NaN payloads.
     */
    private static String h(double d) {
        return Double.isNaN(d) ? " nan" : String.format(" %016x", Double.doubleToRawLongBits(d));
    }

    private static String canonNaN(String line) {
        String[] toks = line.split(" ", -1);
        for (int i = 0; i < toks.length; i++) {
            if (toks[i].matches("[0-9a-f]{16}")
                    && Double.isNaN(Double.longBitsToDouble(Long.parseUnsignedLong(toks[i], 16)))) {
                toks[i] = "nan";
            }
        }
        return String.join(" ", toks);
    }

    private void PV(String t, btVector3 v) {
        out.add(t + h(v.x()) + h(v.y()) + h(v.z()));
    }

    private void PD(String t, double d) {
        out.add(t + h(d));
    }

    private void PI(String t, long i) {
        out.add(t + " " + i);
    }

    private static btVector3[] dirs() {
        return new btVector3[] {
            new btVector3(1, 0, 0),
            new btVector3(0, -1, 0),
            new btVector3(0, 0, 1),
            new btVector3(0.3, -0.7, 0.2),
            new btVector3(-0.577, 0.577, -0.577),
            new btVector3(0, 0, 0),
            new btVector3(1e-9, -2e-9, 0),
            new btVector3(-5, 2.5, 0.1),
            new btVector3(0.1, 0.2, -3)
        };
    }

    private static final int ND = 9;

    private static btTransform T1() {
        btQuaternion q = new btQuaternion(0.1, 0.3, -0.2, 0.9);
        q.normalize();
        return new btTransform(q, new btVector3(1.5, -2, 0.25));
    }

    private static btTransform T2() {
        btQuaternion q = new btQuaternion(-0.4, 0.1, 0.5, 0.7);
        q.normalize();
        return new btTransform(q, new btVector3(-0.5, 3, 1));
    }

    private void convexCommon(btConvexShape s) {
        btVector3[] dirs = dirs();
        btTransform t = T1();
        btVector3 mn = new btVector3(), mx = new btVector3();
        s.getAabb(t, mn, mx);
        PV("aabb.min", mn);
        PV("aabb.max", mx);
        s.getAabbNonVirtual(t, mn, mx);
        PV("aabbnv.min", mn);
        PV("aabbnv.max", mx);
        s.getAabbSlow(t, mn, mx);
        PV("aabbslow.min", mn);
        PV("aabbslow.max", mx);
        for (int i = 0; i < ND; i++) {
            PV("sup", s.localGetSupportingVertex(dirs[i]));
            PV("supwm", s.localGetSupportingVertexWithoutMargin(dirs[i]));
            PV("supnv", s.localGetSupportVertexNonVirtual(dirs[i]));
            PV("supwmnv", s.localGetSupportVertexWithoutMarginNonVirtual(dirs[i]));
        }
        btVector3[] unit = new btVector3[ND];
        btVector3[] o = new btVector3[ND];
        for (int i = 0; i < ND; i++) {
            unit[i] = new btVector3(dirs[i]);
            if (unit[i].length2() > 1e-12) {
                unit[i].normalize();
            }
            o[i] = new btVector3(0, 0, 0);
        }
        s.batchedUnitVectorGetSupportingVertexWithoutMargin(unit, o, ND);
        for (int i = 0; i < ND; i++) {
            PV("batch", o[i]);
        }
        btVector3 in = new btVector3();
        s.calculateLocalInertia(2.5, in);
        PV("inertia", in);
        PD("margin", s.getMargin());
        PD("marginnv", s.getMarginNonVirtual());
        btVector3 c = new btVector3();
        double[] r = new double[1];
        s.getBoundingSphere(c, r);
        PV("bs.c", c);
        PD("bs.r", r[0]);
        PD("amd", s.getAngularMotionDisc());
        PD("cbt", s.getContactBreakingThreshold(0.02));
        btTransform t2 = T2();
        s.calculateTemporalAabb(
                t2, new btVector3(1, -2, 0.5), new btVector3(0.3, 0.1, -0.9), 0.016, mn, mx);
        PV("tmp.min", mn);
        PV("tmp.max", mx);
    }

    private void poly(btPolyhedralConvexShape s) {
        PI("nv", s.getNumVertices());
        for (int i = 0; i < s.getNumVertices(); i++) {
            btVector3 v = new btVector3();
            s.getVertex(i, v);
            PV("v", v);
        }
        PI("ne", s.getNumEdges());
        for (int i = 0; i < s.getNumEdges(); i++) {
            btVector3 a = new btVector3(), b = new btVector3();
            s.getEdge(i, a, b);
            PV("ea", a);
            PV("eb", b);
        }
        PI("np", s.getNumPlanes());
        for (int i = 0; i < s.getNumPlanes(); i++) {
            btVector3 n = new btVector3(), p = new btVector3();
            s.getPlane(n, p, i);
            PV("pn", n);
            PV("ps", p);
        }
        btVector3[] pts = {
            new btVector3(0.1, 0.2, 0.3), new btVector3(0.99, 1.9, 2.9), new btVector3(5, 0, 0)
        };
        for (int i = 0; i < 3; i++) {
            PI("inside", s.isInside(pts[i], 0.01) ? 1 : 0);
        }
        for (int sh = 0; sh < 2; sh++) {
            s.initializePolyhedralFeatures(sh);
            btConvexPolyhedron p = s.getConvexPolyhedron();
            PI("pv", p.m_vertices.size());
            for (int i = 0; i < p.m_vertices.size(); i++) {
                PV("pv", p.m_vertices.get(i));
            }
            PI("pf", p.m_faces.size());
            for (int i = 0; i < p.m_faces.size(); i++) {
                StringBuilder sb = new StringBuilder("fi");
                btFace f = p.m_faces.get(i);
                for (int j = 0; j < f.m_indices.size(); j++) {
                    sb.append(' ').append(f.m_indices.get(j));
                }
                out.add(sb.toString());
                out.add(
                        "fp"
                                + h(f.m_plane[0])
                                + h(f.m_plane[1])
                                + h(f.m_plane[2])
                                + h(f.m_plane[3]));
            }
            PI("pue", p.m_uniqueEdges.size());
            for (int i = 0; i < p.m_uniqueEdges.size(); i++) {
                PV("pue", p.m_uniqueEdges.get(i));
            }
            PV("plc", p.m_localCenter);
            PV("pext", p.m_extents);
            PD("prad", p.m_radius);
            PV("pmC", p.mC);
            PV("pmE", p.mE);
        }
    }

    private void run() {
        out.add("#box");
        {
            btBoxShape b = new btBoxShape(new btVector3(1, 2, 3));
            convexCommon(b);
            poly(b);
            b.setLocalScaling(new btVector3(2, 0.5, 1.5));
            b.setMargin(0.07);
            convexCommon(b);
        }
        out.add("#sphere");
        {
            btSphereShape s = new btSphereShape(0.7);
            convexCommon(s);
            s.setLocalScaling(new btVector3(2, 3, 4));
            convexCommon(s);
        }
        out.add("#capsule");
        {
            btCapsuleShape c = new btCapsuleShape(0.5, 2);
            convexCommon(c);
            c.setLocalScaling(new btVector3(2, 3, 4));
            convexCommon(c);
        }
        out.add("#capsulex");
        convexCommon(new btCapsuleShapeX(0.3, 1.2));
        out.add("#capsulez");
        convexCommon(new btCapsuleShapeZ(0.25, 0.9));
        out.add("#hull");
        {
            double[] pts = {
                0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 1, 1, 1, 0, 0.5, 0.5, -0.3, 0, -0.2,
                0.7, 0.4, 0, 0.3, -0.4, 0.8, 0, 0.9, 0.1, 0.5, 0, 0.2, 0.2, 0.2, 0, 1.1, 0.9, 0.2,
                0, -0.1, -0.1, 0.6, 0
            };
            btConvexHullShape hs = new btConvexHullShape(pts, 12, 32);
            convexCommon(hs);
            poly(hs);
            hs.setLocalScaling(new btVector3(1, 2, 0.5));
            convexCommon(hs);
            hs.addPoint(new btVector3(2, 2, 2));
            convexCommon(hs);
            hs.setMargin(0.1);
            convexCommon(hs);
        }
        out.add("#triangle");
        {
            btTriangleShape t =
                    new btTriangleShape(
                            new btVector3(0, 0, 0),
                            new btVector3(1, 0.2, 0),
                            new btVector3(0.3, 1, 0.5));
            convexCommon(t);
            poly(t);
            btVector3 n = new btVector3(), p = new btVector3();
            t.getPlaneEquation(0, n, p);
            PV("peq.n", n);
            PV("peq.p", p);
        }
        out.add("#compound");
        {
            btBoxShape b = new btBoxShape(new btVector3(0.5, 0.25, 1));
            btSphereShape s = new btSphereShape(0.4);
            btCapsuleShape c = new btCapsuleShape(0.2, 0.8);
            for (int tree = 0; tree < 2; tree++) {
                btCompoundShape cs = new btCompoundShape(tree != 0);
                cs.addChildShape(T1(), b);
                cs.addChildShape(T2(), s);
                btTransform t3 = new btTransform();
                t3.setIdentity();
                t3.setOrigin(new btVector3(0, 1, 0));
                cs.addChildShape(t3, c);
                btVector3 mn = new btVector3(), mx = new btVector3();
                cs.getAabb(T2(), mn, mx);
                PV("aabb.min", mn);
                PV("aabb.max", mx);
                btTransform id = new btTransform();
                id.setIdentity();
                cs.getAabb(id, mn, mx);
                PV("lmin", mn);
                PV("lmax", mx);
                double[] masses = {1, 2.5, 0.7};
                btTransform pr = new btTransform();
                btVector3 in = new btVector3();
                cs.calculatePrincipalAxisTransform(masses, pr, in);
                for (int i = 0; i < 3; i++) {
                    PV("pr.b", pr.getBasis().get(i));
                }
                PV("pr.o", pr.getOrigin());
                PV("pr.i", in);
                cs.calculateLocalInertia(3, in);
                PV("inertia", in);
                cs.setLocalScaling(new btVector3(1.5, 0.5, 2));
                cs.getAabb(T1(), mn, mx);
                PV("aabb.min", mn);
                PV("aabb.max", mx);
                for (int i = 0; i < 3; i++) {
                    PV("ct.o", cs.getChildTransform(i).getOrigin());
                    PV("ct.b0", cs.getChildTransform(i).getBasis().get(0));
                }
                cs.updateChildTransform(1, T1(), true);
                cs.getAabb(T1(), mn, mx);
                PV("aabb.min", mn);
                PV("aabb.max", mx);
                cs.removeChildShapeByIndex(0);
                PI("n", cs.getNumChildShapes());
                PI("rev", cs.getUpdateRevision());
                cs.getAabb(T1(), mn, mx);
                PV("aabb.min", mn);
                PV("aabb.max", mx);
                PI("t0", cs.getChildShape(0).getShapeType());
                cs.removeChildShape(c);
                PI("n", cs.getNumChildShapes());
                cs.getAabb(T1(), mn, mx);
                PV("aabb.min", mn);
                PV("aabb.max", mx);
                cs.removeChildShape(s);
                PI("n", cs.getNumChildShapes());
                cs.getAabb(T1(), mn, mx);
                PV("aabb.min", mn);
                PV("aabb.max", mx);
            }
        }
    }

    static List<String> golden(String name) throws IOException {
        List<String> lines = new ArrayList<>();
        try (InputStream in = ShapesGoldenTest.class.getResourceAsStream(name)) {
            assertNotNull(in, name);
            BufferedReader r =
                    new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String l;
            while ((l = r.readLine()) != null) {
                lines.add(canonNaN(l));
            }
        }
        return lines;
    }

    static void compare(List<String> expected, List<String> actual) {
        String section = "";
        StringBuilder diffs = new StringBuilder();
        int bad = 0;
        int n = Math.min(expected.size(), actual.size());
        for (int i = 0; i < n; i++) {
            if (expected.get(i).startsWith("#")) {
                section = expected.get(i);
            }
            if (!expected.get(i).equals(actual.get(i))) {
                if (bad++ < 25) {
                    diffs.append(
                            String.format(
                                    "%n%s line %d%n  C++ : %s%n  Java: %s",
                                    section, i + 1, expected.get(i), actual.get(i)));
                }
            }
        }
        assertEquals(0, bad, bad + " mismatching lines:" + diffs);
        assertEquals(expected.size(), actual.size(), "line count");
    }

    @Test
    void matchesUpstreamBitExact() throws IOException {
        run();
        compare(golden("shapes-golden.txt"), out);
    }
}
