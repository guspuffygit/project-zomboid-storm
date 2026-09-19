package io.pzstorm.storm.bullet.linearmath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import org.junit.jupiter.api.Test;

/** Matrix, quaternion, transform, container and hashmap behaviour of the linearmath port. */
class LinearMathTest implements UnitTest {

    private static final double EPS = 1e-12;

    private static void assertVec(double x, double y, double z, btVector3 v, double eps) {
        assertEquals(x, v.x, eps, "x of " + v);
        assertEquals(y, v.y, eps, "y of " + v);
        assertEquals(z, v.z, eps, "z of " + v);
    }

    @Test
    void vectorOperatorsFollowCppSemantics() {
        btVector3 a = new btVector3(1, 2, 3);
        btVector3 b = new btVector3(4, 5, 6);
        btVector3 sum = a.add(b);
        assertVec(5, 7, 9, sum, 0);
        assertVec(1, 2, 3, a, 0);
        assertSame(a, a.addLocal(b));
        assertVec(5, 7, 9, a, 0);
        assertVec(-3, 6, -3, new btVector3(1, 2, 3).cross(b), 0);
        assertEquals(32.0, new btVector3(1, 2, 3).dot(b), 0);
        btVector3 n = new btVector3(3, 0, 4);
        btVector3 nn = n.normalized();
        assertVec(3, 0, 4, n, 0);
        assertVec(0.6, 0, 0.8, nn, EPS);
        assertTrue(new btVector3(1, 2, 3).equalsValue(new btVector3(1, 2, 3)));
        // equals() is identity: value types must not be used as hash keys by accident.
        assertFalse(new btVector3(1, 2, 3).equals(new btVector3(1, 2, 3)));
    }

    @Test
    void matrixInverseTransposeAndDeterminant() {
        btMatrix3x3 m = new btMatrix3x3(2, 0, 0, 0, 3, 0, 1, 0, 4);
        assertEquals(24.0, m.determinant(), 0);
        btMatrix3x3 prod = m.mul(m.inverse());
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                assertEquals(i == j ? 1.0 : 0.0, prod.get(i, j), EPS);
            }
        }
        btMatrix3x3 t = m.transpose();
        assertEquals(1.0, t.get(0, 2), 0);
        assertEquals(0.0, t.get(2, 0), 0);
        assertSame(m.m_el0, m.getRow(0));
        btVector3 col = m.getColumn(0);
        assertVec(2, 0, 1, col, 0);
        assertNotSame(m.m_el0, col);
        // transposeTimes(m) == transpose() * m
        assertTrue(m.transposeTimes(m).equalsValue(m.transpose().mul(m)));
        assertEquals(m.tdotx(new btVector3(1, 1, 1)), 3.0, 0);

        double[] gl = new double[12];
        m.getOpenGLSubMatrix(gl, 0);
        btMatrix3x3 back = new btMatrix3x3();
        back.setFromOpenGLSubMatrix(gl, 0);
        assertTrue(back.equalsValue(m));
    }

    @Test
    void matrixEulerRoundTrip() {
        btMatrix3x3 m = new btMatrix3x3();
        m.setEulerZYX(0.3, -0.2, 0.9);
        double[] ypr = new double[3];
        m.getEulerZYX(ypr);
        // getEulerZYX returns (yaw=z, pitch=y, roll=x)
        assertEquals(0.9, ypr[0], 1e-12);
        assertEquals(-0.2, ypr[1], 1e-12);
        assertEquals(0.3, ypr[2], 1e-12);
        assertEquals(1.0, m.determinant(), 1e-12);
    }

    @Test
    void matrixDiagonalizeSymmetric() {
        btMatrix3x3 m = new btMatrix3x3(4, 1, 0, 1, 3, 0, 0, 0, 2);
        btMatrix3x3 rot = new btMatrix3x3();
        btMatrix3x3 d = new btMatrix3x3(m);
        d.diagonalize(rot, 1e-12, 50);
        assertEquals(0.0, d.get(0, 1), 1e-9);
        assertEquals(9.0, d.get(0, 0) + d.get(1, 1) + d.get(2, 2), 1e-9);
        // rot * d * rot^T == m
        btMatrix3x3 rebuilt = rot.mul(d).timesTranspose(rot);
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                assertEquals(m.get(i, j), rebuilt.get(i, j), 1e-9);
            }
        }
    }

    @Test
    void quaternionRotationMatchesMatrix() {
        btQuaternion q = new btQuaternion(new btVector3(0, 0, 1), btScalar.SIMD_HALF_PI);
        btVector3 r = btQuaternion.quatRotate(q, new btVector3(1, 0, 0));
        assertVec(0, 1, 0, r, 1e-15);
        btMatrix3x3 m = new btMatrix3x3(q);
        assertVec(0, 1, 0, m.mul(new btVector3(1, 0, 0)), 1e-15);
        btQuaternion back = new btQuaternion();
        m.getRotation(back);
        assertEquals(q.x, back.x, 1e-15);
        assertEquals(q.w, back.w, 1e-15);
        assertEquals(1.0, q.length(), 1e-15);
        assertEquals(btScalar.SIMD_HALF_PI, q.getAngle(), 1e-15);

        btQuaternion id = btQuaternion.getIdentity();
        btQuaternion half = btQuaternion.slerp(id, q, 0.5);
        assertEquals(btScalar.SIMD_HALF_PI / 2, half.getAngle(), 1e-12);

        btQuaternion arc =
                btQuaternion.shortestArcQuat(new btVector3(1, 0, 0), new btVector3(0, 1, 0));
        assertVec(0, 1, 0, btQuaternion.quatRotate(arc, new btVector3(1, 0, 0)), 1e-15);

        btQuaternion prod = q.mul(q.inverse());
        assertEquals(1.0, prod.w, 1e-15);
    }

    @Test
    void transformComposeAndInverse() {
        btTransform a = new btTransform();
        a.setIdentity();
        a.setRotation(new btQuaternion(new btVector3(0, 1, 0), 0.7));
        a.setOrigin(new btVector3(1, 2, 3));
        btTransform b =
                new btTransform(
                        new btQuaternion(new btVector3(1, 0, 0), -0.4), new btVector3(-5, 0, 2));

        btVector3 p = new btVector3(0.25, -1.5, 3.0);
        btVector3 viaCompose = a.mul(b).transform(p);
        btVector3 viaSteps = a.transform(b.transform(p));
        assertVec(viaSteps.x, viaSteps.y, viaSteps.z, viaCompose, 1e-12);

        btVector3 round = a.inverse().transform(a.transform(p));
        assertVec(p.x, p.y, p.z, round, 1e-12);
        btVector3 inv = a.invXform(a.transform(p));
        assertVec(p.x, p.y, p.z, inv, 1e-12);

        btTransform it = a.inverseTimes(b);
        btVector3 q1 = it.transform(p);
        btVector3 q2 = a.inverse().mul(b).transform(p);
        assertVec(q2.x, q2.y, q2.z, q1, 1e-12);

        btTransform c = new btTransform(a);
        c.mulLocal(b);
        assertTrue(c.equalsValue(a.mul(b)));
        btTransform d = new btTransform();
        d.mult(a, b);
        assertTrue(d.equalsValue(a.mul(b)));

        double[] gl = new double[16];
        a.getOpenGLMatrix(gl);
        assertEquals(1.0, gl[15], 0);
        btTransform e = new btTransform();
        e.setFromOpenGLMatrix(gl);
        assertTrue(e.equalsValue(a));
    }

    @Test
    void transformUtilIntegrateIsIdentityForZeroVelocity() {
        btTransform t = new btTransform(new btQuaternion(0.1, 0.2, 0.3), new btVector3(1, 2, 3));
        btTransform out = new btTransform();
        btTransformUtil.integrateTransform(
                t, new btVector3(0, 0, 0), new btVector3(0, 0, 0), 1.0 / 60, out);
        assertVec(1, 2, 3, out.getOrigin(), 0);
        btQuaternion q0 = t.getRotation();
        btQuaternion q1 = out.getRotation();
        assertEquals(Math.abs(q0.dot(q1)), 1.0, 1e-12);
    }

    // ---------------------------------------------------------------- containers

    @Test
    void alignedArrayRemoveSwapsLastIntoHole() {
        btAlignedObjectArray<String> a = new btAlignedObjectArray<>();
        String s0 = "a", s1 = "b", s2 = "c", s3 = "d";
        a.push_back(s0);
        a.push_back(s1);
        a.push_back(s2);
        a.push_back(s3);
        assertEquals(4, a.capacity());
        a.remove(s1);
        assertEquals(3, a.size());
        assertSame(s0, a.get(0));
        assertSame(s3, a.get(1));
        assertSame(s2, a.get(2));
        assertEquals(1, a.findLinearSearch(s3));
        assertEquals(3, a.findLinearSearch("zz"));
    }

    @Test
    void alignedArrayCapacityDoubles() {
        btAlignedObjectArray<Object> a = new btAlignedObjectArray<>();
        int[] caps = new int[9];
        for (int i = 0; i < 9; i++) {
            a.push_back(new Object());
            caps[i] = a.capacity();
        }
        assertEquals("[1, 2, 4, 4, 8, 8, 8, 8, 16]", java.util.Arrays.toString(caps));
    }

    @Test
    void alignedArrayQuickSortValueMode() {
        btAlignedObjectArray<btVector3> a = btAlignedObjectArray.ofVector3();
        double[] xs = {5, 3, 9, 1, 3, 7, 0, 2};
        for (int i = 0; i < xs.length; i++) {
            a.push_back(new btVector3(xs[i], i, 0));
        }
        btVector3 slot0 = a.get(0);
        a.quickSort((p, q) -> p.x < q.x);
        for (int i = 1; i < a.size(); i++) {
            assertTrue(a.get(i - 1).x <= a.get(i).x);
        }
        // value mode: slot objects stay put, contents are copied (C++ swap semantics)
        assertSame(slot0, a.get(0));
        assertEquals(0.0, a.get(0).x, 0);
        assertEquals(6.0, a.get(0).y, 0);
        // Unstable order of the two x==3 entries must match the verbatim C++ quickSortInternal.
        double[] ys = new double[xs.length];
        double[] xr = xs.clone();
        for (int i = 0; i < ys.length; i++) {
            ys[i] = i;
        }
        referenceQuickSort(xr, ys, 0, xr.length - 1);
        for (int i = 0; i < a.size(); i++) {
            assertEquals(xr[i], a.get(i).x, 0);
            assertEquals(ys[i], a.get(i).y, 0, "slot " + i);
        }
    }

    /** Transcription of btAlignedObjectArray::quickSortInternal (2.82) on parallel arrays. */
    private static void referenceQuickSort(double[] x, double[] y, int lo, int hi) {
        int i = lo, j = hi;
        double px = x[(lo + hi) / 2];
        do {
            while (x[i] < px) {
                i++;
            }
            while (px < x[j]) {
                j--;
            }
            if (i <= j) {
                double t = x[i];
                x[i] = x[j];
                x[j] = t;
                t = y[i];
                y[i] = y[j];
                y[j] = t;
                i++;
                j--;
            }
        } while (i <= j);
        if (lo < j) {
            referenceQuickSort(x, y, lo, j);
        }
        if (i < hi) {
            referenceQuickSort(x, y, i, hi);
        }
    }

    @Test
    void intArraySortAndHeapSort() {
        btIntArray a = new btIntArray();
        int[] vals = {9, -1, 4, 4, 0, 12, 3};
        for (int v : vals) {
            a.push_back(v);
        }
        btIntArray b = new btIntArray(a);
        a.quickSort(btIntArray.less);
        b.heapSort(btIntArray.less);
        int[] sorted = vals.clone();
        java.util.Arrays.sort(sorted);
        for (int i = 0; i < sorted.length; i++) {
            assertEquals(sorted[i], a.get(i));
            assertEquals(sorted[i], b.get(i));
        }
    }

    @Test
    void hashMapIterationOrderAfterRemove() {
        btHashMap<btHashInt, String> m = new btHashMap<>();
        for (int i = 1; i <= 5; i++) {
            m.insert(new btHashInt(i * 10), "v" + i);
        }
        assertEquals(5, m.size());
        m.remove(new btHashInt(20));
        // last pair moved into the removed slot
        assertEquals("v1", m.getAtIndex(0));
        assertEquals("v5", m.getAtIndex(1));
        assertEquals("v3", m.getAtIndex(2));
        assertEquals("v4", m.getAtIndex(3));
        assertEquals(50, m.getKeyAtIndex(1).getUid1());
        assertEquals("v5", m.find(new btHashInt(50)));
        assertEquals(null, m.find(new btHashInt(20)));
        // overwrite keeps position
        m.insert(new btHashInt(30), "x");
        assertEquals("x", m.getAtIndex(2));
        assertEquals(4, m.size());
    }

    @Test
    void hashStringMatchesBulletHash() {
        // btHashString: FNV-1a 32-bit over the bytes
        assertEquals(0x811C9DC5, new btHashString("").getHash());
        assertEquals(0xE40C292C, new btHashString("a").getHash());
    }

    @Test
    void profilerIsNoOpByDefault() {
        CProfileManager.Reset();
        try (CProfileSample s = new CProfileSample("x")) {
            // nothing
        }
        CProfileIterator it = CProfileManager.Get_Iterator();
        assertTrue(it.Is_Done());
    }
}
