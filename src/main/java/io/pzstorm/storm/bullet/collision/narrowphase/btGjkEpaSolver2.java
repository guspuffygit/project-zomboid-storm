// Port of BulletCollision/NarrowPhaseCollision/btGjkEpa2.{h,cpp} (Bullet 2.82)
// GJK-EPA collision solver by Nathanael Presson, 2008 (zlib).
//
// The C++ namespace gjkepa2_impl (MinkowskiDiff, GJK, EPA, Initialize) is mirrored as nested static
// classes of btGjkEpaSolver2. Pointers (sSV*, sFace*) become object references; the fixed stores
// (m_store[4], m_sv_store[64], m_fc_store[128]) are pre-allocated so that the free-list / stock
// ordering is identical to the C++ (it decides which slot is reused, not which value is computed,
// but is kept exact anyway).
package io.pzstorm.storm.bullet.collision.narrowphase;

import io.pzstorm.storm.bullet.collision.shapes.btConvexShape;
import io.pzstorm.storm.bullet.collision.shapes.btSphereShape;
import io.pzstorm.storm.bullet.linearmath.btMatrix3x3;
import io.pzstorm.storm.bullet.linearmath.btMinMax;
import io.pzstorm.storm.bullet.linearmath.btQuaternion;
import io.pzstorm.storm.bullet.linearmath.btScalar;
import io.pzstorm.storm.bullet.linearmath.btTransform;
import io.pzstorm.storm.bullet.linearmath.btVector3;

/** btGjkEpaSolver contributed under zlib by Nathanael Presson */
public final class btGjkEpaSolver2 {
    private btGjkEpaSolver2() {}

    public static final class sResults {
        // enum eStatus
        public static final int Separated = 0; /* Shapes doesnt penetrate */
        public static final int Penetrating = 1; /* Shapes are penetrating */
        public static final int GJK_Failed =
                2; /* GJK phase fail, shapes are probably just 'touching' */
        public static final int EPA_Failed = 3; /* EPA phase fail, bigger problem */

        public int status;
        public final btVector3[] witnesses = {new btVector3(), new btVector3()};
        public final btVector3 normal = new btVector3();
        public double distance;
    }

    // ------------------------------------------------------------------ gjkepa2_impl

    /* GJK */
    static final int GJK_MAX_ITERATIONS = 128;
    static final double GJK_ACCURARY = 0.0001;
    static final double GJK_MIN_DISTANCE = 0.0001;
    static final double GJK_DUPLICATED_EPS = 0.0001;
    static final double GJK_SIMPLEX2_EPS = 0.0;
    static final double GJK_SIMPLEX3_EPS = 0.0;
    static final double GJK_SIMPLEX4_EPS = 0.0;

    /* EPA */
    static final int EPA_MAX_VERTICES = 64;
    static final int EPA_MAX_FACES = (EPA_MAX_VERTICES * 2);
    static final int EPA_MAX_ITERATIONS = 255;
    static final double EPA_ACCURACY = 0.0001;
    static final double EPA_FALLBACK = (10 * EPA_ACCURACY);
    static final double EPA_PLANE_EPS = 0.00001;
    static final double EPA_INSIDE_EPS = 0.01;

    /** MinkowskiDiff (non-SPU build: Ls is a pointer-to-member selecting the margin variant). */
    static final class MinkowskiDiff {
        final btConvexShape[] m_shapes = new btConvexShape[2];
        final btMatrix3x3 m_toshape1 = new btMatrix3x3();
        final btTransform m_toshape0 = new btTransform();

        /** Ls == &btConvexShape::localGetSupportVertexNonVirtual */
        boolean m_lsWithMargin;

        MinkowskiDiff() {}

        /** struct copy (GJK::Evaluate does m_shape = shapearg) */
        void assign(MinkowskiDiff o) {
            m_shapes[0] = o.m_shapes[0];
            m_shapes[1] = o.m_shapes[1];
            m_toshape1.set(o.m_toshape1);
            m_toshape0.set(o.m_toshape0);
            m_lsWithMargin = o.m_lsWithMargin;
        }

        void EnableMargin(boolean enable) {
            m_lsWithMargin = enable;
        }

        private btVector3 Ls(btConvexShape s, btVector3 d) {
            if (m_lsWithMargin) return s.localGetSupportVertexNonVirtual(d);
            else return s.localGetSupportVertexWithoutMarginNonVirtual(d);
        }

        btVector3 Support0(btVector3 d) {
            return Ls(m_shapes[0], d);
        }

        btVector3 Support1(btVector3 d) {
            return m_toshape0.mul(Ls(m_shapes[1], m_toshape1.mul(d)));
        }

        btVector3 Support(btVector3 d) {
            return Support0(d).sub(Support1(d.negate()));
        }

        btVector3 Support(btVector3 d, int index) {
            if (index != 0) return Support1(d);
            else return Support0(d);
        }
    }

    /** GJK */
    static final class GJK {
        static final class sSV {
            final btVector3 d = new btVector3();
            final btVector3 w = new btVector3();
        }

        static final class sSimplex {
            final sSV[] c = new sSV[4];
            final double[] p = new double[4];
            int rank;
        }

        // struct eStatus
        static final int Valid = 0;
        static final int Inside = 1;
        static final int Failed = 2;

        /* Fields */
        final MinkowskiDiff m_shape = new MinkowskiDiff();
        final btVector3 m_ray = new btVector3();
        double m_distance;
        final sSimplex[] m_simplices = {new sSimplex(), new sSimplex()};
        final sSV[] m_store = {new sSV(), new sSV(), new sSV(), new sSV()};
        final sSV[] m_free = new sSV[4];
        int m_nfree;
        int m_current;
        sSimplex m_simplex;
        int m_status;

        GJK() {
            Initialize();
        }

        void Initialize() {
            m_ray.set(new btVector3(0, 0, 0));
            m_nfree = 0;
            m_status = Failed;
            m_current = 0;
            m_distance = 0;
        }

        int Evaluate(MinkowskiDiff shapearg, btVector3 guess) {
            int iterations = 0;
            double sqdist = 0;
            double alpha = 0;
            btVector3[] lastw = {
                new btVector3(), new btVector3(), new btVector3(), new btVector3()
            };
            int clastw = 0;
            /* Initialize solver */
            m_free[0] = m_store[0];
            m_free[1] = m_store[1];
            m_free[2] = m_store[2];
            m_free[3] = m_store[3];
            m_nfree = 4;
            m_current = 0;
            m_status = Valid;
            m_shape.assign(shapearg);
            m_distance = 0;
            /* Initialize simplex */
            m_simplices[0].rank = 0;
            m_ray.set(guess);
            final double sqrl = m_ray.length2();
            appendvertice(m_simplices[0], sqrl > 0 ? m_ray.negate() : new btVector3(1, 0, 0));
            m_simplices[0].p[0] = 1;
            m_ray.set(m_simplices[0].c[0].w);
            sqdist = sqrl;
            lastw[3].set(m_ray);
            lastw[2].set(lastw[3]);
            lastw[1].set(lastw[2]);
            lastw[0].set(lastw[1]);
            /* Loop */
            do {
                final int next = 1 - m_current;
                sSimplex cs = m_simplices[m_current];
                sSimplex ns = m_simplices[next];
                /* Check zero */
                final double rl = m_ray.length();
                if (rl < GJK_MIN_DISTANCE) {
                    /* Touching or inside */
                    m_status = Inside;
                    break;
                }
                /* Append new vertice in -'v' direction */
                appendvertice(cs, m_ray.negate());
                final btVector3 w = cs.c[cs.rank - 1].w;
                boolean found = false;
                for (int i = 0; i < 4; ++i) {
                    if ((w.sub(lastw[i])).length2() < GJK_DUPLICATED_EPS) {
                        found = true;
                        break;
                    }
                }
                if (found) {
                    /* Return old simplex */
                    removevertice(m_simplices[m_current]);
                    break;
                } else {
                    /* Update lastw */
                    lastw[clastw = (clastw + 1) & 3].set(w);
                }
                /* Check for termination */
                final double omega = btVector3.btDot(m_ray, w) / rl;
                alpha = btMinMax.btMax(omega, alpha);
                if (((rl - alpha) - (GJK_ACCURARY * rl)) <= 0) {
                    /* Return old simplex */
                    removevertice(m_simplices[m_current]);
                    break;
                }
                /* Reduce simplex */
                // btScalar weights[4]; (uninitialised in C++; every projectorigin path that returns
                // >= 0
                // writes all rank entries)
                double[] weights = new double[4];
                int[] mask = {0};
                switch (cs.rank) {
                    case 2:
                        sqdist = projectorigin(cs.c[0].w, cs.c[1].w, weights, mask);
                        break;
                    case 3:
                        sqdist = projectorigin(cs.c[0].w, cs.c[1].w, cs.c[2].w, weights, mask);
                        break;
                    case 4:
                        sqdist =
                                projectorigin(
                                        cs.c[0].w, cs.c[1].w, cs.c[2].w, cs.c[3].w, weights, mask);
                        break;
                }
                if (sqdist >= 0) {
                    /* Valid */
                    ns.rank = 0;
                    m_ray.set(new btVector3(0, 0, 0));
                    m_current = next;
                    for (int i = 0, ni = cs.rank; i < ni; ++i) {
                        if ((mask[0] & (1 << i)) != 0) {
                            ns.c[ns.rank] = cs.c[i];
                            ns.p[ns.rank++] = weights[i];
                            m_ray.addLocal(cs.c[i].w.mul(weights[i]));
                        } else {
                            m_free[m_nfree++] = cs.c[i];
                        }
                    }
                    if (mask[0] == 15) m_status = Inside;
                } else {
                    /* Return old simplex */
                    removevertice(m_simplices[m_current]);
                    break;
                }
                m_status = ((++iterations) < GJK_MAX_ITERATIONS) ? m_status : Failed;
            } while (m_status == Valid);
            m_simplex = m_simplices[m_current];
            switch (m_status) {
                case Valid:
                    m_distance = m_ray.length();
                    break;
                case Inside:
                    m_distance = 0;
                    break;
                default:
                    {
                    }
            }
            return (m_status);
        }

        boolean EncloseOrigin() {
            switch (m_simplex.rank) {
                case 1:
                    {
                        for (int i = 0; i < 3; ++i) {
                            btVector3 axis = new btVector3(0, 0, 0);
                            axis.set(i, 1);
                            appendvertice(m_simplex, axis);
                            if (EncloseOrigin()) return (true);
                            removevertice(m_simplex);
                            appendvertice(m_simplex, axis.negate());
                            if (EncloseOrigin()) return (true);
                            removevertice(m_simplex);
                        }
                    }
                    break;
                case 2:
                    {
                        final btVector3 d = m_simplex.c[1].w.sub(m_simplex.c[0].w);
                        for (int i = 0; i < 3; ++i) {
                            btVector3 axis = new btVector3(0, 0, 0);
                            axis.set(i, 1);
                            final btVector3 p = btVector3.btCross(d, axis);
                            if (p.length2() > 0) {
                                appendvertice(m_simplex, p);
                                if (EncloseOrigin()) return (true);
                                removevertice(m_simplex);
                                appendvertice(m_simplex, p.negate());
                                if (EncloseOrigin()) return (true);
                                removevertice(m_simplex);
                            }
                        }
                    }
                    break;
                case 3:
                    {
                        final btVector3 n =
                                btVector3.btCross(
                                        m_simplex.c[1].w.sub(m_simplex.c[0].w),
                                        m_simplex.c[2].w.sub(m_simplex.c[0].w));
                        if (n.length2() > 0) {
                            appendvertice(m_simplex, n);
                            if (EncloseOrigin()) return (true);
                            removevertice(m_simplex);
                            appendvertice(m_simplex, n.negate());
                            if (EncloseOrigin()) return (true);
                            removevertice(m_simplex);
                        }
                    }
                    break;
                case 4:
                    {
                        if (btScalar.btFabs(
                                        det(
                                                m_simplex.c[0].w.sub(m_simplex.c[3].w),
                                                m_simplex.c[1].w.sub(m_simplex.c[3].w),
                                                m_simplex.c[2].w.sub(m_simplex.c[3].w)))
                                > 0) return (true);
                    }
                    break;
            }
            return (false);
        }

        /* Internals */
        void getsupport(btVector3 d, sSV sv) {
            sv.d.set(d.div(d.length()));
            sv.w.set(m_shape.Support(sv.d));
        }

        void removevertice(sSimplex simplex) {
            m_free[m_nfree++] = simplex.c[--simplex.rank];
        }

        void appendvertice(sSimplex simplex, btVector3 v) {
            simplex.p[simplex.rank] = 0;
            simplex.c[simplex.rank] = m_free[--m_nfree];
            getsupport(v, simplex.c[simplex.rank++]);
        }

        static double det(btVector3 a, btVector3 b, btVector3 c) {
            return (a.y * b.z * c.x
                    + a.z * b.x * c.y
                    - a.x * b.z * c.y
                    - a.y * b.x * c.z
                    + a.x * b.y * c.z
                    - a.z * b.y * c.x);
        }

        static double projectorigin(btVector3 a, btVector3 b, double[] w, int[] m) {
            final btVector3 d = b.sub(a);
            final double l = d.length2();
            if (l > GJK_SIMPLEX2_EPS) {
                final double t = (l > 0 ? -btVector3.btDot(a, d) / l : 0);
                if (t >= 1) {
                    w[0] = 0;
                    w[1] = 1;
                    m[0] = 2;
                    return (b.length2());
                } else if (t <= 0) {
                    w[0] = 1;
                    w[1] = 0;
                    m[0] = 1;
                    return (a.length2());
                } else {
                    w[0] = 1 - (w[1] = t);
                    m[0] = 3;
                    return ((a.add(d.mul(t))).length2());
                }
            }
            return (-1);
        }

        private static final int[] imd3 = {1, 2, 0};

        static double projectorigin(btVector3 a, btVector3 b, btVector3 c, double[] w, int[] m) {
            final btVector3[] vt = {a, b, c};
            final btVector3[] dl = {a.sub(b), b.sub(c), c.sub(a)};
            final btVector3 n = btVector3.btCross(dl[0], dl[1]);
            final double l = n.length2();
            if (l > GJK_SIMPLEX3_EPS) {
                double mindist = -1;
                double[] subw = {(double) 0.f, (double) 0.f};
                int[] subm = {0};
                for (int i = 0; i < 3; ++i) {
                    if (btVector3.btDot(vt[i], btVector3.btCross(dl[i], n)) > 0) {
                        final int j = imd3[i];
                        final double subd = projectorigin(vt[i], vt[j], subw, subm);
                        if ((mindist < 0) || (subd < mindist)) {
                            mindist = subd;
                            m[0] =
                                    (((subm[0] & 1) != 0 ? 1 << i : 0)
                                            + ((subm[0] & 2) != 0 ? 1 << j : 0));
                            w[i] = subw[0];
                            w[j] = subw[1];
                            w[imd3[j]] = 0;
                        }
                    }
                }
                if (mindist < 0) {
                    final double d = btVector3.btDot(a, n);
                    final double s = btScalar.btSqrt(l);
                    final btVector3 p = n.mul(d / l);
                    mindist = p.length2();
                    m[0] = 7;
                    w[0] = (btVector3.btCross(dl[1], b.sub(p))).length() / s;
                    w[1] = (btVector3.btCross(dl[2], c.sub(p))).length() / s;
                    w[2] = 1 - (w[0] + w[1]);
                }
                return (mindist);
            }
            return (-1);
        }

        static double projectorigin(
                btVector3 a, btVector3 b, btVector3 c, btVector3 d, double[] w, int[] m) {
            final btVector3[] vt = {a, b, c, d};
            final btVector3[] dl = {a.sub(d), b.sub(d), c.sub(d)};
            final double vl = det(dl[0], dl[1], dl[2]);
            final boolean ng =
                    (vl * btVector3.btDot(a, btVector3.btCross(b.sub(c), a.sub(b)))) <= 0;
            if (ng && (btScalar.btFabs(vl) > GJK_SIMPLEX4_EPS)) {
                double mindist = -1;
                double[] subw = {(double) 0.f, (double) 0.f, (double) 0.f};
                int[] subm = {0};
                for (int i = 0; i < 3; ++i) {
                    final int j = imd3[i];
                    final double s = vl * btVector3.btDot(d, btVector3.btCross(dl[i], dl[j]));
                    if (s > 0) {
                        final double subd = projectorigin(vt[i], vt[j], d, subw, subm);
                        if ((mindist < 0) || (subd < mindist)) {
                            mindist = subd;
                            m[0] =
                                    (((subm[0] & 1) != 0 ? 1 << i : 0)
                                            + ((subm[0] & 2) != 0 ? 1 << j : 0)
                                            + ((subm[0] & 4) != 0 ? 8 : 0));
                            w[i] = subw[0];
                            w[j] = subw[1];
                            w[imd3[j]] = 0;
                            w[3] = subw[2];
                        }
                    }
                }
                if (mindist < 0) {
                    mindist = 0;
                    m[0] = 15;
                    w[0] = det(c, b, d) / vl;
                    w[1] = det(a, c, d) / vl;
                    w[2] = det(b, a, d) / vl;
                    w[3] = 1 - (w[0] + w[1] + w[2]);
                }
                return (mindist);
            }
            return (-1);
        }
    }

    /** EPA */
    static final class EPA {
        static final class sFace {
            final btVector3 n = new btVector3();
            double d;
            final GJK.sSV[] c = new GJK.sSV[3];
            final sFace[] f = new sFace[3];
            final sFace[] l = new sFace[2];

            /** U1 */
            final int[] e = new int[3];

            /** U1 */
            int pass;

            /** struct copy (sFace outer = *best) */
            void assign(sFace o) {
                n.set(o.n);
                d = o.d;
                c[0] = o.c[0];
                c[1] = o.c[1];
                c[2] = o.c[2];
                f[0] = o.f[0];
                f[1] = o.f[1];
                f[2] = o.f[2];
                l[0] = o.l[0];
                l[1] = o.l[1];
                e[0] = o.e[0];
                e[1] = o.e[1];
                e[2] = o.e[2];
                pass = o.pass;
            }
        }

        static final class sList {
            sFace root;
            int count;

            sList() {
                root = null;
                count = 0;
            }
        }

        static final class sHorizon {
            sFace cf;
            sFace ff;
            int nf;

            sHorizon() {
                cf = null;
                ff = null;
                nf = 0;
            }
        }

        // struct eStatus
        static final int Valid = 0;
        static final int Touching = 1;
        static final int Degenerated = 2;
        static final int NonConvex = 3;
        static final int InvalidHull = 4;
        static final int OutOfFaces = 5;
        static final int OutOfVertices = 6;
        static final int AccuraryReached = 7;
        static final int FallBack = 8;
        static final int Failed = 9;

        /* Fields */
        int m_status;
        final GJK.sSimplex m_result = new GJK.sSimplex();
        final btVector3 m_normal = new btVector3();
        double m_depth;
        final GJK.sSV[] m_sv_store = new GJK.sSV[EPA_MAX_VERTICES];
        final sFace[] m_fc_store = new sFace[EPA_MAX_FACES];
        int m_nextsv;
        final sList m_hull = new sList();
        final sList m_stock = new sList();

        EPA() {
            for (int i = 0; i < EPA_MAX_VERTICES; ++i) m_sv_store[i] = new GJK.sSV();
            for (int i = 0; i < EPA_MAX_FACES; ++i) m_fc_store[i] = new sFace();
            Initialize();
        }

        static void bind(sFace fa, int ea, sFace fb, int eb) {
            fa.e[ea] = eb & 0xFF;
            fa.f[ea] = fb;
            fb.e[eb] = ea & 0xFF;
            fb.f[eb] = fa;
        }

        static void append(sList list, sFace face) {
            face.l[0] = null;
            face.l[1] = list.root;
            if (list.root != null) list.root.l[0] = face;
            list.root = face;
            ++list.count;
        }

        static void remove(sList list, sFace face) {
            if (face.l[1] != null) face.l[1].l[0] = face.l[0];
            if (face.l[0] != null) face.l[0].l[1] = face.l[1];
            if (face == list.root) list.root = face.l[1];
            --list.count;
        }

        void Initialize() {
            m_status = Failed;
            m_normal.set(new btVector3(0, 0, 0));
            m_depth = 0;
            m_nextsv = 0;
            for (int i = 0; i < EPA_MAX_FACES; ++i) {
                append(m_stock, m_fc_store[EPA_MAX_FACES - i - 1]);
            }
        }

        int Evaluate(GJK gjk, btVector3 guess) {
            GJK.sSimplex simplex = gjk.m_simplex;
            if ((simplex.rank > 1) && gjk.EncloseOrigin()) {

                /* Clean up */
                while (m_hull.root != null) {
                    sFace f = m_hull.root;
                    remove(m_hull, f);
                    append(m_stock, f);
                }
                m_status = Valid;
                m_nextsv = 0;
                /* Orient simplex */
                if (GJK.det(
                                simplex.c[0].w.sub(simplex.c[3].w),
                                simplex.c[1].w.sub(simplex.c[3].w),
                                simplex.c[2].w.sub(simplex.c[3].w))
                        < 0) {
                    GJK.sSV tc = simplex.c[0];
                    simplex.c[0] = simplex.c[1];
                    simplex.c[1] = tc;
                    double tp = simplex.p[0];
                    simplex.p[0] = simplex.p[1];
                    simplex.p[1] = tp;
                }
                /* Build initial hull */
                sFace[] tetra = {
                    newface(simplex.c[0], simplex.c[1], simplex.c[2], true),
                    newface(simplex.c[1], simplex.c[0], simplex.c[3], true),
                    newface(simplex.c[2], simplex.c[1], simplex.c[3], true),
                    newface(simplex.c[0], simplex.c[2], simplex.c[3], true)
                };
                if (m_hull.count == 4) {
                    sFace best = findbest();
                    sFace outer = new sFace();
                    outer.assign(best);
                    int pass = 0;
                    int iterations = 0;
                    bind(tetra[0], 0, tetra[1], 0);
                    bind(tetra[0], 1, tetra[2], 0);
                    bind(tetra[0], 2, tetra[3], 0);
                    bind(tetra[1], 1, tetra[3], 2);
                    bind(tetra[1], 2, tetra[2], 1);
                    bind(tetra[2], 2, tetra[3], 1);
                    m_status = Valid;
                    for (; iterations < EPA_MAX_ITERATIONS; ++iterations) {
                        if (m_nextsv < EPA_MAX_VERTICES) {
                            sHorizon horizon = new sHorizon();
                            GJK.sSV w = m_sv_store[m_nextsv++];
                            boolean valid = true;
                            best.pass = (++pass) & 0xFF;
                            gjk.getsupport(best.n, w);
                            final double wdist = btVector3.btDot(best.n, w.w) - best.d;
                            if (wdist > EPA_ACCURACY) {
                                for (int j = 0; (j < 3) && valid; ++j) {
                                    valid &= expand(pass, w, best.f[j], best.e[j], horizon);
                                }
                                if (valid && (horizon.nf >= 3)) {
                                    bind(horizon.cf, 1, horizon.ff, 2);
                                    remove(m_hull, best);
                                    append(m_stock, best);
                                    best = findbest();
                                    outer.assign(best);
                                } else {
                                    m_status = InvalidHull;
                                    break;
                                }
                            } else {
                                m_status = AccuraryReached;
                                break;
                            }
                        } else {
                            m_status = OutOfVertices;
                            break;
                        }
                    }
                    final btVector3 projection = outer.n.mul(outer.d);
                    m_normal.set(outer.n);
                    m_depth = outer.d;
                    m_result.rank = 3;
                    m_result.c[0] = outer.c[0];
                    m_result.c[1] = outer.c[1];
                    m_result.c[2] = outer.c[2];
                    m_result.p[0] =
                            btVector3
                                    .btCross(
                                            outer.c[1].w.sub(projection),
                                            outer.c[2].w.sub(projection))
                                    .length();
                    m_result.p[1] =
                            btVector3
                                    .btCross(
                                            outer.c[2].w.sub(projection),
                                            outer.c[0].w.sub(projection))
                                    .length();
                    m_result.p[2] =
                            btVector3
                                    .btCross(
                                            outer.c[0].w.sub(projection),
                                            outer.c[1].w.sub(projection))
                                    .length();
                    final double sum = m_result.p[0] + m_result.p[1] + m_result.p[2];
                    m_result.p[0] /= sum;
                    m_result.p[1] /= sum;
                    m_result.p[2] /= sum;
                    return (m_status);
                }
            }
            /* Fallback */
            m_status = FallBack;
            m_normal.set(guess.negate());
            final double nl = m_normal.length();
            if (nl > 0) m_normal.set(m_normal.div(nl));
            else m_normal.set(new btVector3(1, 0, 0));
            m_depth = 0;
            m_result.rank = 1;
            m_result.c[0] = simplex.c[0];
            m_result.p[0] = 1;
            return (m_status);
        }

        /** dist is written through face.d (the only caller passes face->d). */
        boolean getedgedist(sFace face, GJK.sSV a, GJK.sSV b) {
            final btVector3 ba = b.w.sub(a.w);
            final btVector3 n_ab =
                    btVector3.btCross(ba, face.n); // Outward facing edge normal direction
            final double a_dot_nab = btVector3.btDot(a.w, n_ab); // Only care about the sign

            if (a_dot_nab < 0) {
                // Outside of edge a->b

                final double ba_l2 = ba.length2();
                final double a_dot_ba = btVector3.btDot(a.w, ba);
                final double b_dot_ba = btVector3.btDot(b.w, ba);

                if (a_dot_ba > 0) {
                    // Pick distance vertex a
                    face.d = a.w.length();
                } else if (b_dot_ba < 0) {
                    // Pick distance vertex b
                    face.d = b.w.length();
                } else {
                    // Pick distance to edge a->b
                    final double a_dot_b = btVector3.btDot(a.w, b.w);
                    face.d =
                            btScalar.btSqrt(
                                    btMinMax.btMax(
                                            (a.w.length2() * b.w.length2() - a_dot_b * a_dot_b)
                                                    / ba_l2,
                                            (double) 0));
                }

                return true;
            }

            return false;
        }

        sFace newface(GJK.sSV a, GJK.sSV b, GJK.sSV c, boolean forced) {
            if (m_stock.root != null) {
                sFace face = m_stock.root;
                remove(m_stock, face);
                append(m_hull, face);
                face.pass = 0;
                face.c[0] = a;
                face.c[1] = b;
                face.c[2] = c;
                face.n.set(btVector3.btCross(b.w.sub(a.w), c.w.sub(a.w)));
                final double l = face.n.length();
                final boolean v = l > EPA_ACCURACY;

                if (v) {
                    if (!(getedgedist(face, a, b)
                            || getedgedist(face, b, c)
                            || getedgedist(face, c, a))) {
                        // Origin projects to the interior of the triangle
                        // Use distance to triangle plane
                        face.d = btVector3.btDot(a.w, face.n) / l;
                    }

                    face.n.divLocal(l);
                    if (forced || (face.d >= -EPA_PLANE_EPS)) {
                        return face;
                    } else m_status = NonConvex;
                } else m_status = Degenerated;

                remove(m_hull, face);
                append(m_stock, face);
                return null;
            }
            m_status = m_stock.root != null ? OutOfVertices : OutOfFaces;
            return null;
        }

        sFace findbest() {
            sFace minf = m_hull.root;
            double mind = minf.d * minf.d;
            for (sFace f = minf.l[1]; f != null; f = f.l[1]) {
                final double sqd = f.d * f.d;
                if (sqd < mind) {
                    minf = f;
                    mind = sqd;
                }
            }
            return (minf);
        }

        private static final int[] i1m3 = {1, 2, 0};
        private static final int[] i2m3 = {2, 0, 1};

        boolean expand(int pass, GJK.sSV w, sFace f, int e, sHorizon horizon) {
            if (f.pass != pass) {
                final int e1 = i1m3[e];
                if ((btVector3.btDot(f.n, w.w) - f.d) < -EPA_PLANE_EPS) {
                    sFace nf = newface(f.c[e1], f.c[e], w, false);
                    if (nf != null) {
                        bind(nf, 0, f, e);
                        if (horizon.cf != null) bind(horizon.cf, 1, nf, 2);
                        else horizon.ff = nf;
                        horizon.cf = nf;
                        ++horizon.nf;
                        return (true);
                    }
                } else {
                    final int e2 = i2m3[e];
                    f.pass = pass & 0xFF;
                    if (expand(pass, w, f.f[e1], f.e[e1], horizon)
                            && expand(pass, w, f.f[e2], f.e[e2], horizon)) {
                        remove(m_hull, f);
                        append(m_stock, f);
                        return (true);
                    }
                }
            }
            return (false);
        }
    }

    static void Initialize(
            btConvexShape shape0,
            btTransform wtrs0,
            btConvexShape shape1,
            btTransform wtrs1,
            sResults results,
            MinkowskiDiff shape,
            boolean withmargins) {
        /* Results */
        results.witnesses[1].set(new btVector3(0, 0, 0));
        results.witnesses[0].set(results.witnesses[1]);
        results.status = sResults.Separated;
        /* Shape */
        shape.m_shapes[0] = shape0;
        shape.m_shapes[1] = shape1;
        shape.m_toshape1.set(wtrs1.getBasis().transposeTimes(wtrs0.getBasis()));
        shape.m_toshape0.set(wtrs0.inverseTimes(wtrs1));
        shape.EnableMargin(withmargins);
    }

    // ------------------------------------------------------------------ Api

    /**
     * sizeof(GJK)+sizeof(EPA) of the x86-64 GCC double-precision build (see
     * docs/re-bullet/narrowphase.md; measured with a g++ -O3 -DBT_USE_DOUBLE_PRECISION driver).
     */
    public static int StackSizeRequirement() {
        return STACK_SIZE_REQUIREMENT;
    }

    static final int STACK_SIZE_REQUIREMENT = 19344;

    public static boolean Distance(
            btConvexShape shape0,
            btTransform wtrs0,
            btConvexShape shape1,
            btTransform wtrs1,
            btVector3 guess,
            sResults results) {
        MinkowskiDiff shape = new MinkowskiDiff();
        Initialize(shape0, wtrs0, shape1, wtrs1, results, shape, false);
        GJK gjk = new GJK();
        int gjk_status = gjk.Evaluate(shape, guess);
        if (gjk_status == GJK.Valid) {
            btVector3 w0 = new btVector3(0, 0, 0);
            btVector3 w1 = new btVector3(0, 0, 0);
            for (int i = 0; i < gjk.m_simplex.rank; ++i) {
                final double p = gjk.m_simplex.p[i];
                w0.addLocal(shape.Support(gjk.m_simplex.c[i].d, 0).mul(p));
                w1.addLocal(shape.Support(gjk.m_simplex.c[i].d.negate(), 1).mul(p));
            }
            results.witnesses[0].set(wtrs0.mul(w0));
            results.witnesses[1].set(wtrs0.mul(w1));
            results.normal.set(w0.sub(w1));
            results.distance = results.normal.length();
            results.normal.divLocal(results.distance > GJK_MIN_DISTANCE ? results.distance : 1);
            return (true);
        } else {
            results.status = gjk_status == GJK.Inside ? sResults.Penetrating : sResults.GJK_Failed;
            return (false);
        }
    }

    public static boolean Penetration(
            btConvexShape shape0,
            btTransform wtrs0,
            btConvexShape shape1,
            btTransform wtrs1,
            btVector3 guess,
            sResults results) {
        return Penetration(shape0, wtrs0, shape1, wtrs1, guess, results, true);
    }

    public static boolean Penetration(
            btConvexShape shape0,
            btTransform wtrs0,
            btConvexShape shape1,
            btTransform wtrs1,
            btVector3 guess,
            sResults results,
            boolean usemargins) {
        MinkowskiDiff shape = new MinkowskiDiff();
        Initialize(shape0, wtrs0, shape1, wtrs1, results, shape, usemargins);
        GJK gjk = new GJK();
        int gjk_status = gjk.Evaluate(shape, guess.negate());
        switch (gjk_status) {
            case GJK.Inside:
                {
                    EPA epa = new EPA();
                    int epa_status = epa.Evaluate(gjk, guess.negate());
                    if (epa_status != EPA.Failed) {
                        btVector3 w0 = new btVector3(0, 0, 0);
                        for (int i = 0; i < epa.m_result.rank; ++i) {
                            w0.addLocal(
                                    shape.Support(epa.m_result.c[i].d, 0).mul(epa.m_result.p[i]));
                        }
                        results.status = sResults.Penetrating;
                        results.witnesses[0].set(wtrs0.mul(w0));
                        results.witnesses[1].set(wtrs0.mul(w0.sub(epa.m_normal.mul(epa.m_depth))));
                        results.normal.set(epa.m_normal.negate());
                        results.distance = -epa.m_depth;
                        return (true);
                    } else results.status = sResults.EPA_Failed;
                }
                break;
            case GJK.Failed:
                results.status = sResults.GJK_Failed;
                break;
            default:
                {
                }
        }
        return (false);
    }

    public static double SignedDistance(
            btVector3 position,
            double margin,
            btConvexShape shape0,
            btTransform wtrs0,
            sResults results) {
        MinkowskiDiff shape = new MinkowskiDiff();
        btSphereShape shape1 = new btSphereShape(margin);
        btTransform wtrs1 = new btTransform(new btQuaternion(0, 0, 0, 1), position);
        Initialize(shape0, wtrs0, shape1, wtrs1, results, shape, false);
        GJK gjk = new GJK();
        int gjk_status = gjk.Evaluate(shape, new btVector3(1, 1, 1));
        if (gjk_status == GJK.Valid) {
            btVector3 w0 = new btVector3(0, 0, 0);
            btVector3 w1 = new btVector3(0, 0, 0);
            for (int i = 0; i < gjk.m_simplex.rank; ++i) {
                final double p = gjk.m_simplex.p[i];
                w0.addLocal(shape.Support(gjk.m_simplex.c[i].d, 0).mul(p));
                w1.addLocal(shape.Support(gjk.m_simplex.c[i].d.negate(), 1).mul(p));
            }
            results.witnesses[0].set(wtrs0.mul(w0));
            results.witnesses[1].set(wtrs0.mul(w1));
            final btVector3 delta = results.witnesses[1].sub(results.witnesses[0]);
            final double margin2 = shape0.getMarginNonVirtual() + shape1.getMarginNonVirtual();
            final double length = delta.length();
            results.normal.set(delta.div(length));
            results.witnesses[0].addLocal(results.normal.mul(margin2));
            return (length - margin2);
        } else {
            if (gjk_status == GJK.Inside) {
                if (Penetration(shape0, wtrs0, shape1, wtrs1, gjk.m_ray, results)) {
                    final btVector3 delta = results.witnesses[0].sub(results.witnesses[1]);
                    final double length = delta.length();
                    if (length >= btScalar.SIMD_EPSILON) results.normal.set(delta.div(length));
                    return (-length);
                }
            }
        }
        return (btScalar.SIMD_INFINITY);
    }

    public static boolean SignedDistance(
            btConvexShape shape0,
            btTransform wtrs0,
            btConvexShape shape1,
            btTransform wtrs1,
            btVector3 guess,
            sResults results) {
        if (!Distance(shape0, wtrs0, shape1, wtrs1, guess, results))
            return (Penetration(shape0, wtrs0, shape1, wtrs1, guess, results, false));
        else return (true);
    }
}
