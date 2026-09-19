package io.pzstorm.storm.bullet.linearmath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import io.pzstorm.storm.bullet.linearmath.btGrahamScan2dConvexHull.GrahamVector3;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** btConvexHullComputer / btConvexHullInternal and btGrahamScan2dConvexHull. */
class ConvexHullTest implements UnitTest {

    private static btConvexHullComputer hull(double[][] pts, double shrink, double clamp) {
        double[] flat = new double[pts.length * 3];
        for (int i = 0; i < pts.length; i++) {
            flat[i * 3] = pts[i][0];
            flat[i * 3 + 1] = pts[i][1];
            flat[i * 3 + 2] = pts[i][2];
        }
        btConvexHullComputer c = new btConvexHullComputer();
        c.compute(flat, 3 * 8, pts.length, shrink, clamp);
        return c;
    }

    /** Structural invariants of the half-edge output. */
    private static void checkTopology(btConvexHullComputer c) {
        int v = c.vertices.size();
        int e = c.edges.size();
        int f = c.faces.size();
        assertEquals(0, e % 2);
        assertEquals(2, v - e / 2 + f, "Euler characteristic");
        int faceEdgeSum = 0;
        for (int i = 0; i < e; i++) {
            btConvexHullComputer.Edge edge = c.edges.get(i);
            assertSame(edge, edge.getReverseEdge().getReverseEdge());
            assertEquals(edge.getSourceVertex(), edge.getReverseEdge().getTargetVertex());
        }
        for (int i = 0; i < f; i++) {
            btConvexHullComputer.Edge first = c.edges.get(c.faces.get(i));
            btConvexHullComputer.Edge edge = first;
            int n = 0;
            do {
                btConvexHullComputer.Edge next = edge.getNextEdgeOfFace();
                assertEquals(edge.getTargetVertex(), next.getSourceVertex());
                edge = next;
                n++;
                assertTrue(n <= e);
            } while (edge != first);
            faceEdgeSum += n;
        }
        assertEquals(e, faceEdgeSum, "every half-edge on exactly one face");
    }

    /** Every face normal points away from the centroid and all input points lie inside. */
    private static void checkConvexContains(btConvexHullComputer c, double[][] pts, double tol) {
        for (int i = 0; i < c.faces.size(); i++) {
            btConvexHullComputer.Edge e0 = c.edges.get(c.faces.get(i));
            btConvexHullComputer.Edge e1 = e0.getNextEdgeOfFace();
            btVector3 a = c.vertices.get(e0.getSourceVertex());
            btVector3 b = c.vertices.get(e0.getTargetVertex());
            btVector3 d = c.vertices.get(e1.getTargetVertex());
            btVector3 n = b.sub(a).cross(d.sub(a)).normalized();
            for (double[] p : pts) {
                double dist = n.dot(new btVector3(p[0], p[1], p[2]).sub(a));
                assertTrue(dist <= tol, "point outside face " + i + ": " + dist);
            }
        }
    }

    @Test
    void cubeWithInteriorPoints() {
        double[][] pts = {
            {-1, -1, -1}, {1, -1, -1}, {1, 1, -1}, {-1, 1, -1},
            {-1, -1, 1}, {1, -1, 1}, {1, 1, 1}, {-1, 1, 1},
            {0, 0, 0}, {0.5, 0.2, -0.3}, {1, 0, 0}
        };
        btConvexHullComputer c = hull(pts, 0, 0);
        assertEquals(8, c.vertices.size());
        assertEquals(24, c.edges.size());
        assertEquals(6, c.faces.size());
        checkTopology(c);
        checkConvexContains(c, pts, 1e-9);
        for (int i = 0; i < c.vertices.size(); i++) {
            btVector3 v = c.vertices.get(i);
            assertEquals(1.0, Math.abs(v.x), 0);
            assertEquals(1.0, Math.abs(v.y), 0);
            assertEquals(1.0, Math.abs(v.z), 0);
        }
    }

    @Test
    void tetrahedron() {
        double[][] pts = {{0, 0, 0}, {1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
        btConvexHullComputer c = hull(pts, 0, 0);
        assertEquals(4, c.vertices.size());
        assertEquals(12, c.edges.size());
        assertEquals(4, c.faces.size());
        checkTopology(c);
        checkConvexContains(c, pts, 1e-9);
    }

    @Test
    void randomCloudIsDeterministic() {
        Random r = new Random(1234);
        double[][] pts = new double[200][3];
        for (double[] p : pts) {
            p[0] = r.nextGaussian();
            p[1] = r.nextGaussian() * 2;
            p[2] = r.nextGaussian() * 0.5;
        }
        btConvexHullComputer a = hull(pts, 0, 0);
        btConvexHullComputer b = hull(pts, 0, 0);
        checkTopology(a);
        // Input is quantised to a +-5108 integer grid along each axis (extent / 10216 per step), so
        // input points may sit up to a few grid steps outside the output hull.
        double maxExtent = 0;
        for (int k = 0; k < 3; k++) {
            double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
            for (double[] p : pts) {
                lo = Math.min(lo, p[k]);
                hi = Math.max(hi, p[k]);
            }
            maxExtent = Math.max(maxExtent, hi - lo);
        }
        checkConvexContains(a, pts, 4 * maxExtent / 10216);
        assertEquals(a.vertices.size(), b.vertices.size());
        assertEquals(a.edges.size(), b.edges.size());
        for (int i = 0; i < a.vertices.size(); i++) {
            assertTrue(a.vertices.get(i).equalsValue(b.vertices.get(i)));
        }
        for (int i = 0; i < a.faces.size(); i++) {
            assertEquals(a.faces.get(i), b.faces.get(i));
        }
    }

    @Test
    void floatInputAndVectorInputAgree() {
        float[] f = {0, 0, 0, 0, 2, 0, 0, 0, 0, 3, 0, 0, 0, 0, 0.5f, 0, 1, 1, 1, 0};
        btConvexHullComputer cf = new btConvexHullComputer();
        cf.compute(f, 4 * 4, 5, 0, 0);
        btVector3[] v = {
            new btVector3(0, 0, 0),
            new btVector3(2, 0, 0),
            new btVector3(0, 3, 0),
            new btVector3(0, 0, 0.5),
            new btVector3(1, 1, 1)
        };
        btConvexHullComputer cv = new btConvexHullComputer();
        cv.compute(v, 5, 0, 0);
        assertEquals(cf.vertices.size(), cv.vertices.size());
        for (int i = 0; i < cf.vertices.size(); i++) {
            assertTrue(cf.vertices.get(i).equalsValue(cv.vertices.get(i)));
        }
        checkTopology(cv);
    }

    @Test
    void degenerateInputs() {
        btConvexHullComputer c = hull(new double[][] {{1, 2, 3}}, 0, 0);
        assertEquals(1, c.vertices.size());
        assertEquals(0, c.edges.size());
        c = hull(new double[][] {{1, 2, 3}, {1, 2, 3}}, 0, 0);
        assertEquals(1, c.vertices.size());
        c = hull(new double[][] {{0, 0, 0}, {1, 0, 0}}, 0, 0);
        assertEquals(2, c.vertices.size());
        assertEquals(2, c.edges.size());
        // planar square: a flat hull has two faces
        c = hull(new double[][] {{0, 0, 0}, {1, 0, 0}, {1, 1, 0}, {0, 1, 0}}, 0, 0);
        assertEquals(4, c.vertices.size());
        assertEquals(2, c.faces.size());
        checkTopology(c);
        c = hull(new double[0][], 0, 0);
        assertEquals(0, c.vertices.size());
    }

    @Test
    void shrinkMovesFacesInward() {
        double[][] pts = {
            {-1, -1, -1}, {1, -1, -1}, {1, 1, -1}, {-1, 1, -1},
            {-1, -1, 1}, {1, -1, 1}, {1, 1, 1}, {-1, 1, 1}
        };
        btConvexHullComputer c = hull(pts, 0.25, 0);
        assertEquals(8, c.vertices.size());
        checkTopology(c);
        for (int i = 0; i < c.vertices.size(); i++) {
            btVector3 v = c.vertices.get(i);
            assertEquals(0.75, Math.abs(v.x), 1e-3);
            assertEquals(0.75, Math.abs(v.y), 1e-3);
            assertEquals(0.75, Math.abs(v.z), 1e-3);
        }
        // shrink clamped to 0.5 * inner radius (1.0)
        btConvexHullComputer d = hull(pts, 10, 0.5);
        for (int i = 0; i < d.vertices.size(); i++) {
            assertEquals(0.5, Math.abs(d.vertices.get(i).x), 1e-3);
        }
    }

    @Test
    void int128Arithmetic() {
        btConvexHullInternal.Int128 p =
                btConvexHullInternal.Int128.mul(-3_000_000_000L, 5_000_000_000L);
        assertEquals(-1.5e19, p.toScalar(), 0);
        assertEquals(-1, p.getSign());
        btConvexHullInternal.Int128 u = btConvexHullInternal.Int128.mulUnsigned(-1L, -1L);
        // (2^64-1)^2 = 2^128 - 2^65 + 1
        assertEquals(1L, u.low);
        assertEquals(-2L, u.high);
        btConvexHullInternal.Int128 s = p.add(p.negate());
        assertEquals(0, s.getSign());
        btConvexHullInternal.Rational64 half = new btConvexHullInternal.Rational64(1, 2);
        btConvexHullInternal.Rational64 third = new btConvexHullInternal.Rational64(-1, -3);
        assertEquals(1, half.compare(third));
        assertEquals(-1, third.compare(half));
        btConvexHullInternal.Rational128 r =
                new btConvexHullInternal.Rational128(
                        btConvexHullInternal.Int128.fromSigned(7),
                        btConvexHullInternal.Int128.fromSigned(-2));
        assertEquals(-1, r.compare(-3));
        assertEquals(1, r.compare(-4));
        assertEquals(-3.5, r.toScalar(), 0);
    }

    // ---------------------------------------------------------------- Graham scan

    @Test
    void grahamScanSquareWithInteriorPoint() {
        btAlignedObjectArray<GrahamVector3> in = btGrahamScan2dConvexHull.newArray();
        double[][] xy = {{0.5, 0.5}, {1, 1}, {0, 0}, {1, 0}, {0.2, 0.7}, {0, 1}};
        for (int i = 0; i < xy.length; i++) {
            in.push_back(new GrahamVector3(new btVector3(xy[i][0], xy[i][1], 0), i));
        }
        btAlignedObjectArray<GrahamVector3> out = btGrahamScan2dConvexHull.newArray();
        btGrahamScan2dConvexHull.GrahamScanConvexHull2D(in, out, new btVector3(0, 0, 1));
        assertEquals(4, out.size());
        int[] seen = new int[6];
        for (int i = 0; i < out.size(); i++) {
            seen[out.get(i).m_orgIndex]++;
        }
        assertEquals(0, seen[0]);
        assertEquals(0, seen[4]);
        assertEquals(1, seen[1] + seen[2] + seen[3] + seen[5] - 3);
        // consecutive hull points turn consistently (convex polygon)
        int sign = 0;
        for (int i = 0; i < out.size(); i++) {
            btVector3 a = out.get(i);
            btVector3 b = out.get((i + 1) % out.size());
            btVector3 c = out.get((i + 2) % out.size());
            double z = b.sub(a).cross(c.sub(b)).z;
            int s = (int) Math.signum(z);
            assertTrue(s != 0);
            if (sign == 0) {
                sign = s;
            }
            assertEquals(sign, s);
        }
    }

    @Test
    void grahamScanSinglePoint() {
        btAlignedObjectArray<GrahamVector3> in = btGrahamScan2dConvexHull.newArray();
        in.push_back(new GrahamVector3(new btVector3(3, 4, 0), 7));
        btAlignedObjectArray<GrahamVector3> out = btGrahamScan2dConvexHull.newArray();
        btGrahamScan2dConvexHull.GrahamScanConvexHull2D(in, out, new btVector3(0, 0, 1));
        assertEquals(1, out.size());
        assertEquals(7, out.get(0).m_orgIndex);
    }
}
