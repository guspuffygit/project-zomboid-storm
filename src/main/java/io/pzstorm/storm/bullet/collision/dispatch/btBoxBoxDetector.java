package io.pzstorm.storm.bullet.collision.dispatch;

import io.pzstorm.storm.bullet.collision.narrowphase.btDiscreteCollisionDetectorInterface;
import io.pzstorm.storm.bullet.collision.shapes.btBoxShape;
import io.pzstorm.storm.bullet.linearmath.btIDebugDraw;
import io.pzstorm.storm.bullet.linearmath.btScalar;
import io.pzstorm.storm.bullet.linearmath.btTransform;
import io.pzstorm.storm.bullet.linearmath.btVector3;

/**
 * Port of Bullet 2.82 BulletCollision/CollisionDispatch/btBoxBoxDetector.{h,cpp} (the ODE box-box
 * collider). {@code dMatrix3} is a {@code double[12]} with {@code R[4*row+col]}; C pointer
 * arguments become (array, offset) pairs. Evaluation order and float/double promotion points follow
 * the C++ exactly ({@code M__PI} is a float literal, so {@code 2*M__PI/m} is float math).
 */
public class btBoxBoxDetector extends btDiscreteCollisionDetectorInterface {
    public btBoxShape m_box1;
    public btBoxShape m_box2;

    /** {@code #define M__PI 3.14159265f}. */
    static final float M__PI = 3.14159265f;

    /** {@code #define dInfinity FLT_MAX}. */
    static final double dInfinity = (double) Float.MAX_VALUE;

    public btBoxBoxDetector(btBoxShape box1, btBoxShape box2) {
        m_box1 = box1;
        m_box2 = box2;
    }

    /** virtual ~btBoxBoxDetector() {} */
    public void destroy() {}

    // ---- dDOTpq family: (a)[0]*(b)[0] + (a)[p]*(b)[q] + (a)[2p]*(b)[2q] ----

    static double dDOTpq(double[] a, int ao, double[] b, int bo, int p, int q) {
        return a[ao] * b[bo] + a[ao + p] * b[bo + q] + a[ao + 2 * p] * b[bo + 2 * q];
    }

    static double dDOT(double[] a, int ao, double[] b, int bo) {
        return dDOTpq(a, ao, b, bo, 1, 1);
    }

    static double dDOT44(double[] a, int ao, double[] b, int bo) {
        return dDOTpq(a, ao, b, bo, 4, 4);
    }

    static double dDOT41(double[] a, int ao, double[] b, int bo) {
        return dDOTpq(a, ao, b, bo, 4, 1);
    }

    static double dDOT14(double[] a, int ao, double[] b, int bo) {
        return dDOTpq(a, ao, b, bo, 1, 4);
    }

    /** dMULTIPLY1_331(A,B,C): A[k] = dDOT41(B+k, C). */
    static void dMULTIPLY1_331(double[] A, double[] B, int bo, double[] C) {
        A[0] = dDOT41(B, bo, C, 0);
        A[1] = dDOT41(B, bo + 1, C, 0);
        A[2] = dDOT41(B, bo + 2, C, 0);
    }

    /** dMULTIPLY0_331(A,B,C): A[k] = dDOT(B+4k, C). */
    static void dMULTIPLY0_331(double[] A, double[] B, int bo, double[] C) {
        A[0] = dDOT(B, bo, C, 0);
        A[1] = dDOT(B, bo + 4, C, 0);
        A[2] = dDOT(B, bo + 8, C, 0);
    }

    /** Returns {alpha, beta} in {@code out}. Vectors are 3-element arrays (btVector3 m_floats). */
    public static void dLineClosestApproach(
            double[] pa, double[] ua, double[] pb, double[] ub, double[] out) {
        double[] p = new double[3];
        p[0] = pb[0] - pa[0];
        p[1] = pb[1] - pa[1];
        p[2] = pb[2] - pa[2];
        double uaub = dDOT(ua, 0, ub, 0);
        double q1 = dDOT(ua, 0, p, 0);
        double q2 = -dDOT(ub, 0, p, 0);
        double d = 1 - uaub * uaub;
        if (d <= (double) 0.0001f) {
            out[0] = 0;
            out[1] = 0;
        } else {
            d = (double) 1.f / d;
            out[0] = (q1 + uaub * q2) * d;
            out[1] = (uaub * q1 + q2) * d;
        }
    }

    /** btVector3 overload matching the C++ signature. */
    public static void dLineClosestApproach(
            btVector3 pa, btVector3 ua, btVector3 pb, btVector3 ub, double[] out) {
        dLineClosestApproach(v(pa), v(ua), v(pb), v(ub), out);
    }

    private static double[] v(btVector3 a) {
        return new double[] {a.get(0), a.get(1), a.get(2)};
    }

    /**
     * intersectRectQuad2: pointer ping-pong between {@code p}, {@code ret} and a local 16-element
     * buffer, with the {@code nr & 8} early exit.
     */
    public static int intersectRectQuad2(double[] h, double[] p, double[] ret) {
        int nq = 4, nr = 0;
        double[] buffer = new double[16];
        double[] q = p;
        double[] r = ret;
        done:
        for (int dir = 0; dir <= 1; dir++) {
            for (int sign = -1; sign <= 1; sign += 2) {
                int pq = 0;
                int pr = 0;
                nr = 0;
                for (int i = nq; i > 0; i--) {
                    if (sign * q[pq + dir] < h[dir]) {
                        r[pr] = q[pq];
                        r[pr + 1] = q[pq + 1];
                        pr += 2;
                        nr++;
                        if ((nr & 8) != 0) {
                            q = r;
                            break done;
                        }
                    }
                    int nextq = (i > 1) ? pq + 2 : 0;
                    if ((sign * q[pq + dir] < h[dir]) ^ (sign * q[nextq + dir] < h[dir])) {
                        r[pr + 1 - dir] =
                                q[pq + 1 - dir]
                                        + (q[nextq + 1 - dir] - q[pq + 1 - dir])
                                                / (q[nextq + dir] - q[pq + dir])
                                                * (sign * h[dir] - q[pq + dir]);
                        r[pr + dir] = sign * h[dir];
                        pr += 2;
                        nr++;
                        if ((nr & 8) != 0) {
                            q = r;
                            break done;
                        }
                    }
                    pq += 2;
                }
                q = r;
                r = (q == ret) ? buffer : ret;
                nq = nr;
            }
        }
        if (q != ret) System.arraycopy(q, 0, ret, 0, nr * 2);
        return nr;
    }

    /** cullPoints2(n, p, m, i0, iret). */
    public static void cullPoints2(int n, double[] p, int m, int i0, int[] iret) {
        int i, j;
        double a, cx, cy, q;
        if (n == 1) {
            cx = p[0];
            cy = p[1];
        } else if (n == 2) {
            cx = 0.5 * (p[0] + p[2]);
            cy = 0.5 * (p[1] + p[3]);
        } else {
            a = 0;
            cx = 0;
            cy = 0;
            for (i = 0; i < (n - 1); i++) {
                q = p[i * 2] * p[i * 2 + 3] - p[i * 2 + 2] * p[i * 2 + 1];
                a += q;
                cx += q * (p[i * 2] + p[i * 2 + 2]);
                cy += q * (p[i * 2 + 1] + p[i * 2 + 3]);
            }
            q = p[n * 2 - 2] * p[1] - p[0] * p[n * 2 - 1];
            if (btScalar.btFabs(a + q) > btScalar.SIMD_EPSILON) {
                a = (double) 1.f / (3.0 * (a + q));
            } else {
                a = btScalar.BT_LARGE_FLOAT;
            }
            cx = a * (cx + q * (p[n * 2 - 2] + p[0]));
            cy = a * (cy + q * (p[n * 2 - 1] + p[1]));
        }

        double[] A = new double[8];
        for (i = 0; i < n; i++) A[i] = btScalar.btAtan2(p[i * 2 + 1] - cy, p[i * 2] - cx);

        int[] avail = new int[8];
        for (i = 0; i < n; i++) avail[i] = 1;
        avail[i0] = 0;
        iret[0] = i0;
        int ir = 1;
        for (j = 1; j < m; j++) {
            // 2*M__PI/m is float arithmetic; btScalar(j) * (float) promotes to double.
            a = (double) j * (double) (2 * M__PI / m) + A[i0];
            if (a > (double) M__PI) a -= (double) (2 * M__PI);
            double maxdiff = 1e9, diff;
            iret[ir] = i0;
            for (i = 0; i < n; i++) {
                if (avail[i] != 0) {
                    diff = btScalar.btFabs(A[i] - a);
                    if (diff > (double) M__PI) diff = (double) (2 * M__PI) - diff;
                    if (diff < maxdiff) {
                        maxdiff = diff;
                        iret[ir] = i;
                    }
                }
            }
            avail[iret[ir]] = 0;
            ir++;
        }
    }

    /**
     * dBoxBox2. {@code normal} receives the normal; {@code depthOut[0]} the depth and {@code
     * returnCode[0]} the code (both left untouched on early exit, as in C++).
     */
    public static int dBoxBox2(
            btVector3 p1v,
            double[] R1,
            btVector3 side1,
            btVector3 p2v,
            double[] R2,
            btVector3 side2,
            btVector3 normalOut,
            double[] depthOut,
            int[] returnCode,
            int maxc,
            btDiscreteCollisionDetectorInterface.Result output) {
        final double fudge_factor = 1.05;
        double[] p1 = v(p1v);
        double[] p2 = v(p2v);
        double[] p = new double[3];
        double[] pp = new double[3];
        double[] normalC = {0., 0., 0.};
        double[] normalR = null;
        int normalRo = 0;
        double[] A = new double[3], B = new double[3];
        double R11, R12, R13, R21, R22, R23, R31, R32, R33;
        double Q11, Q12, Q13, Q21, Q22, Q23, Q31, Q32, Q33, s, s2, l;
        int i, j, invert_normal, code;

        // p = p2 - p1 (btVector3 operator-)
        p[0] = p2[0] - p1[0];
        p[1] = p2[1] - p1[1];
        p[2] = p2[2] - p1[2];
        dMULTIPLY1_331(pp, R1, 0, p);

        A[0] = side1.get(0) * 0.5;
        A[1] = side1.get(1) * 0.5;
        A[2] = side1.get(2) * 0.5;
        B[0] = side2.get(0) * 0.5;
        B[1] = side2.get(1) * 0.5;
        B[2] = side2.get(2) * 0.5;

        R11 = dDOT44(R1, 0, R2, 0);
        R12 = dDOT44(R1, 0, R2, 1);
        R13 = dDOT44(R1, 0, R2, 2);
        R21 = dDOT44(R1, 1, R2, 0);
        R22 = dDOT44(R1, 1, R2, 1);
        R23 = dDOT44(R1, 1, R2, 2);
        R31 = dDOT44(R1, 2, R2, 0);
        R32 = dDOT44(R1, 2, R2, 1);
        R33 = dDOT44(R1, 2, R2, 2);

        Q11 = btScalar.btFabs(R11);
        Q12 = btScalar.btFabs(R12);
        Q13 = btScalar.btFabs(R13);
        Q21 = btScalar.btFabs(R21);
        Q22 = btScalar.btFabs(R22);
        Q23 = btScalar.btFabs(R23);
        Q31 = btScalar.btFabs(R31);
        Q32 = btScalar.btFabs(R32);
        Q33 = btScalar.btFabs(R33);

        s = -dInfinity;
        invert_normal = 0;
        code = 0;

        // ---- face axes (TST #1) ----
        double e1, e2;
        e1 = pp[0];
        e2 = (A[0] + B[0] * Q11 + B[1] * Q12 + B[2] * Q13);
        s2 = btScalar.btFabs(e1) - e2;
        if (s2 > 0) return 0;
        if (s2 > s) {
            s = s2;
            normalR = R1;
            normalRo = 0;
            invert_normal = (e1 < 0) ? 1 : 0;
            code = 1;
        }
        e1 = pp[1];
        e2 = (A[1] + B[0] * Q21 + B[1] * Q22 + B[2] * Q23);
        s2 = btScalar.btFabs(e1) - e2;
        if (s2 > 0) return 0;
        if (s2 > s) {
            s = s2;
            normalR = R1;
            normalRo = 1;
            invert_normal = (e1 < 0) ? 1 : 0;
            code = 2;
        }
        e1 = pp[2];
        e2 = (A[2] + B[0] * Q31 + B[1] * Q32 + B[2] * Q33);
        s2 = btScalar.btFabs(e1) - e2;
        if (s2 > 0) return 0;
        if (s2 > s) {
            s = s2;
            normalR = R1;
            normalRo = 2;
            invert_normal = (e1 < 0) ? 1 : 0;
            code = 3;
        }
        e1 = dDOT41(R2, 0, p, 0);
        e2 = (A[0] * Q11 + A[1] * Q21 + A[2] * Q31 + B[0]);
        s2 = btScalar.btFabs(e1) - e2;
        if (s2 > 0) return 0;
        if (s2 > s) {
            s = s2;
            normalR = R2;
            normalRo = 0;
            invert_normal = (e1 < 0) ? 1 : 0;
            code = 4;
        }
        e1 = dDOT41(R2, 1, p, 0);
        e2 = (A[0] * Q12 + A[1] * Q22 + A[2] * Q32 + B[1]);
        s2 = btScalar.btFabs(e1) - e2;
        if (s2 > 0) return 0;
        if (s2 > s) {
            s = s2;
            normalR = R2;
            normalRo = 1;
            invert_normal = (e1 < 0) ? 1 : 0;
            code = 5;
        }
        e1 = dDOT41(R2, 2, p, 0);
        e2 = (A[0] * Q13 + A[1] * Q23 + A[2] * Q33 + B[2]);
        s2 = btScalar.btFabs(e1) - e2;
        if (s2 > 0) return 0;
        if (s2 > s) {
            s = s2;
            normalR = R2;
            normalRo = 2;
            invert_normal = (e1 < 0) ? 1 : 0;
            code = 6;
        }

        // ---- edge axes (TST #2) ----
        double fudge2 = (double) 1.0e-5f;
        Q11 += fudge2;
        Q12 += fudge2;
        Q13 += fudge2;
        Q21 += fudge2;
        Q22 += fudge2;
        Q23 += fudge2;
        Q31 += fudge2;
        Q32 += fudge2;
        Q33 += fudge2;

        double[] st = {s};
        int[] sc = {code, invert_normal};
        if (tst2(
                pp[2] * R21 - pp[1] * R31,
                (A[1] * Q31 + A[2] * Q21 + B[1] * Q13 + B[2] * Q12),
                0,
                -R31,
                R21,
                7,
                fudge_factor,
                st,
                sc,
                normalC)) return 0;
        if (tst2(
                pp[2] * R22 - pp[1] * R32,
                (A[1] * Q32 + A[2] * Q22 + B[0] * Q13 + B[2] * Q11),
                0,
                -R32,
                R22,
                8,
                fudge_factor,
                st,
                sc,
                normalC)) return 0;
        if (tst2(
                pp[2] * R23 - pp[1] * R33,
                (A[1] * Q33 + A[2] * Q23 + B[0] * Q12 + B[1] * Q11),
                0,
                -R33,
                R23,
                9,
                fudge_factor,
                st,
                sc,
                normalC)) return 0;
        if (tst2(
                pp[0] * R31 - pp[2] * R11,
                (A[0] * Q31 + A[2] * Q11 + B[1] * Q23 + B[2] * Q22),
                R31,
                0,
                -R11,
                10,
                fudge_factor,
                st,
                sc,
                normalC)) return 0;
        if (tst2(
                pp[0] * R32 - pp[2] * R12,
                (A[0] * Q32 + A[2] * Q12 + B[0] * Q23 + B[2] * Q21),
                R32,
                0,
                -R12,
                11,
                fudge_factor,
                st,
                sc,
                normalC)) return 0;
        if (tst2(
                pp[0] * R33 - pp[2] * R13,
                (A[0] * Q33 + A[2] * Q13 + B[0] * Q22 + B[1] * Q21),
                R33,
                0,
                -R13,
                12,
                fudge_factor,
                st,
                sc,
                normalC)) return 0;
        if (tst2(
                pp[1] * R11 - pp[0] * R21,
                (A[0] * Q21 + A[1] * Q11 + B[1] * Q33 + B[2] * Q32),
                -R21,
                R11,
                0,
                13,
                fudge_factor,
                st,
                sc,
                normalC)) return 0;
        if (tst2(
                pp[1] * R12 - pp[0] * R22,
                (A[0] * Q22 + A[1] * Q12 + B[0] * Q33 + B[2] * Q31),
                -R22,
                R12,
                0,
                14,
                fudge_factor,
                st,
                sc,
                normalC)) return 0;
        if (tst2(
                pp[1] * R13 - pp[0] * R23,
                (A[0] * Q23 + A[1] * Q13 + B[0] * Q32 + B[1] * Q31),
                -R23,
                R13,
                0,
                15,
                fudge_factor,
                st,
                sc,
                normalC)) return 0;
        if (sc[0] != code) {
            // an edge axis won: normalR = 0
            normalR = null;
        }
        s = st[0];
        code = sc[0];
        invert_normal = sc[1];

        if (code == 0) return 0;

        double[] normal = new double[3];
        if (normalR != null) {
            normal[0] = normalR[normalRo];
            normal[1] = normalR[normalRo + 4];
            normal[2] = normalR[normalRo + 8];
        } else {
            dMULTIPLY0_331(normal, R1, 0, normalC);
        }
        if (invert_normal != 0) {
            normal[0] = -normal[0];
            normal[1] = -normal[1];
            normal[2] = -normal[2];
        }
        normalOut.setValue(normal[0], normal[1], normal[2]);
        double depth = -s;
        depthOut[0] = depth;

        // compute contact point(s)

        if (code > 6) {
            // an edge from box 1 touches an edge from box 2.
            double[] pa = new double[3];
            double sign;
            for (i = 0; i < 3; i++) pa[i] = p1[i];
            for (j = 0; j < 3; j++) {
                sign = (dDOT14(normal, 0, R1, j) > 0) ? 1.0 : -1.0;
                for (i = 0; i < 3; i++) pa[i] += sign * A[j] * R1[i * 4 + j];
            }

            double[] pb = new double[3];
            for (i = 0; i < 3; i++) pb[i] = p2[i];
            for (j = 0; j < 3; j++) {
                sign = (dDOT14(normal, 0, R2, j) > 0) ? -1.0 : 1.0;
                for (i = 0; i < 3; i++) pb[i] += sign * B[j] * R2[i * 4 + j];
            }

            double[] ab = new double[2];
            double[] ua = new double[3], ub = new double[3];
            for (i = 0; i < 3; i++) ua[i] = R1[((code) - 7) / 3 + i * 4];
            for (i = 0; i < 3; i++) ub[i] = R2[((code) - 7) % 3 + i * 4];

            dLineClosestApproach(pa, ua, pb, ub, ab);
            double alpha = ab[0], beta = ab[1];
            for (i = 0; i < 3; i++) pa[i] += ua[i] * alpha;
            for (i = 0; i < 3; i++) pb[i] += ub[i] * beta;

            {
                btVector3 n = new btVector3(normal[0], normal[1], normal[2]);
                output.addContactPoint(n.negate(), new btVector3(pb[0], pb[1], pb[2]), -depth);
                returnCode[0] = code;
            }
            return 1;
        }

        // okay, we have a face-something intersection (because the separating
        // axis is perpendicular to a face). define face 'a' to be the reference
        // face (i.e. the normal vector is perpendicular to this) and face 'b' to be
        // the incident face (the closest face of the other box).

        double[] Ra, Rb, pa, pb, Sa, Sb;
        if (code <= 3) {
            Ra = R1;
            Rb = R2;
            pa = p1;
            pb = p2;
            Sa = A;
            Sb = B;
        } else {
            Ra = R2;
            Rb = R1;
            pa = p2;
            pb = p1;
            Sa = B;
            Sb = A;
        }

        double[] normal2 = new double[3], nr = new double[3], anr = new double[3];
        if (code <= 3) {
            normal2[0] = normal[0];
            normal2[1] = normal[1];
            normal2[2] = normal[2];
        } else {
            normal2[0] = -normal[0];
            normal2[1] = -normal[1];
            normal2[2] = -normal[2];
        }
        dMULTIPLY1_331(nr, Rb, 0, normal2);
        anr[0] = btScalar.btFabs(nr[0]);
        anr[1] = btScalar.btFabs(nr[1]);
        anr[2] = btScalar.btFabs(nr[2]);

        int lanr, a1, a2;
        if (anr[1] > anr[0]) {
            if (anr[1] > anr[2]) {
                a1 = 0;
                lanr = 1;
                a2 = 2;
            } else {
                a1 = 0;
                a2 = 1;
                lanr = 2;
            }
        } else {
            if (anr[0] > anr[2]) {
                lanr = 0;
                a1 = 1;
                a2 = 2;
            } else {
                a1 = 0;
                a2 = 1;
                lanr = 2;
            }
        }

        double[] center = new double[3];
        if (nr[lanr] < 0) {
            for (i = 0; i < 3; i++) center[i] = pb[i] - pa[i] + Sb[lanr] * Rb[i * 4 + lanr];
        } else {
            for (i = 0; i < 3; i++) center[i] = pb[i] - pa[i] - Sb[lanr] * Rb[i * 4 + lanr];
        }

        int codeN, code1, code2;
        if (code <= 3) codeN = code - 1;
        else codeN = code - 4;
        if (codeN == 0) {
            code1 = 1;
            code2 = 2;
        } else if (codeN == 1) {
            code1 = 0;
            code2 = 2;
        } else {
            code1 = 0;
            code2 = 1;
        }

        double[] quad = new double[8];
        double c1, c2, m11, m12, m21, m22;
        c1 = dDOT14(center, 0, Ra, code1);
        c2 = dDOT14(center, 0, Ra, code2);
        m11 = dDOT44(Ra, code1, Rb, a1);
        m12 = dDOT44(Ra, code1, Rb, a2);
        m21 = dDOT44(Ra, code2, Rb, a1);
        m22 = dDOT44(Ra, code2, Rb, a2);
        {
            double k1 = m11 * Sb[a1];
            double k2 = m21 * Sb[a1];
            double k3 = m12 * Sb[a2];
            double k4 = m22 * Sb[a2];
            quad[0] = c1 - k1 - k3;
            quad[1] = c2 - k2 - k4;
            quad[2] = c1 - k1 + k3;
            quad[3] = c2 - k2 + k4;
            quad[4] = c1 + k1 + k3;
            quad[5] = c2 + k2 + k4;
            quad[6] = c1 + k1 - k3;
            quad[7] = c2 + k2 - k4;
        }

        double[] rect = new double[2];
        rect[0] = Sa[code1];
        rect[1] = Sa[code2];

        double[] ret = new double[16];
        int n = intersectRectQuad2(rect, quad, ret);
        if (n < 1) return 0;

        double[] point = new double[3 * 8];
        double[] dep = new double[8];
        double det1 = (double) 1.f / (m11 * m22 - m12 * m21);
        m11 *= det1;
        m12 *= det1;
        m21 *= det1;
        m22 *= det1;
        int cnum = 0;
        for (j = 0; j < n; j++) {
            double k1 = m22 * (ret[j * 2] - c1) - m12 * (ret[j * 2 + 1] - c2);
            double k2 = -m21 * (ret[j * 2] - c1) + m11 * (ret[j * 2 + 1] - c2);
            for (i = 0; i < 3; i++)
                point[cnum * 3 + i] = center[i] + k1 * Rb[i * 4 + a1] + k2 * Rb[i * 4 + a2];
            dep[cnum] = Sa[codeN] - dDOT(normal2, 0, point, cnum * 3);
            if (dep[cnum] >= 0) {
                ret[cnum * 2] = ret[j * 2];
                ret[cnum * 2 + 1] = ret[j * 2 + 1];
                cnum++;
            }
        }
        if (cnum < 1) return 0;

        if (maxc > cnum) maxc = cnum;
        if (maxc < 1) maxc = 1;

        btVector3 normalV = new btVector3(normal[0], normal[1], normal[2]);
        if (cnum <= maxc) {
            if (code < 4) {
                for (j = 0; j < cnum; j++) {
                    btVector3 pointInWorld = new btVector3();
                    for (i = 0; i < 3; i++) pointInWorld.set(i, point[j * 3 + i] + pa[i]);
                    output.addContactPoint(normalV.negate(), pointInWorld, -dep[j]);
                }
            } else {
                for (j = 0; j < cnum; j++) {
                    btVector3 pointInWorld = new btVector3();
                    for (i = 0; i < 3; i++)
                        pointInWorld.set(i, point[j * 3 + i] + pa[i] - normal[i] * dep[j]);
                    output.addContactPoint(normalV.negate(), pointInWorld, -dep[j]);
                }
            }
        } else {
            int i1 = 0;
            double maxdepth = dep[0];
            for (i = 1; i < cnum; i++) {
                if (dep[i] > maxdepth) {
                    maxdepth = dep[i];
                    i1 = i;
                }
            }

            int[] iret = new int[8];
            cullPoints2(cnum, ret, maxc, i1, iret);

            for (j = 0; j < maxc; j++) {
                btVector3 posInWorld = new btVector3();
                for (i = 0; i < 3; i++) posInWorld.set(i, point[iret[j] * 3 + i] + pa[i]);
                if (code < 4) {
                    output.addContactPoint(normalV.negate(), posInWorld, -dep[iret[j]]);
                } else {
                    output.addContactPoint(
                            normalV.negate(),
                            posInWorld.sub(normalV.mul(dep[iret[j]])),
                            -dep[iret[j]]);
                }
            }
            cnum = maxc;
        }

        returnCode[0] = code;
        return cnum;
    }

    /**
     * Edge-axis TST macro. Returns true for "return 0". {@code st[0]} = s; {@code sc} = {code,
     * invert_normal}.
     */
    private static boolean tst2(
            double expr1,
            double expr2,
            double n1,
            double n2,
            double n3,
            int cc,
            double fudge_factor,
            double[] st,
            int[] sc,
            double[] normalC) {
        double s2 = btScalar.btFabs(expr1) - (expr2);
        if (s2 > btScalar.SIMD_EPSILON) return true;
        double l = btScalar.btSqrt((n1) * (n1) + (n2) * (n2) + (n3) * (n3));
        if (l > btScalar.SIMD_EPSILON) {
            s2 /= l;
            if (s2 * fudge_factor > st[0]) {
                st[0] = s2;
                normalC[0] = (n1) / l;
                normalC[1] = (n2) / l;
                normalC[2] = (n3) / l;
                sc[1] = ((expr1) < 0) ? 1 : 0;
                sc[0] = (cc);
            }
        }
        return false;
    }

    @Override
    public void getClosestPoints(
            ClosestPointInput input, Result output, btIDebugDraw debugDraw, boolean swapResults) {
        btTransform transformA = input.m_transformA;
        btTransform transformB = input.m_transformB;

        double[] R1 = new double[12];
        double[] R2 = new double[12];

        for (int j = 0; j < 3; j++) {
            R1[0 + 4 * j] = transformA.getBasis().getRow(j).x();
            R2[0 + 4 * j] = transformB.getBasis().getRow(j).x();

            R1[1 + 4 * j] = transformA.getBasis().getRow(j).y();
            R2[1 + 4 * j] = transformB.getBasis().getRow(j).y();

            R1[2 + 4 * j] = transformA.getBasis().getRow(j).z();
            R2[2 + 4 * j] = transformB.getBasis().getRow(j).z();
        }

        btVector3 normal = new btVector3();
        double[] depth = new double[1];
        int[] return_code = new int[1];
        int maxc = 4;

        dBoxBox2(
                transformA.getOrigin(),
                R1,
                m_box1.getHalfExtentsWithMargin().mul(2.0),
                transformB.getOrigin(),
                R2,
                m_box2.getHalfExtentsWithMargin().mul(2.0),
                normal,
                depth,
                return_code,
                maxc,
                output);
    }
}
