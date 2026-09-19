package io.pzstorm.storm.bullet.collision.narrowphase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.pzstorm.storm.UnitTest;
import io.pzstorm.storm.bullet.collision.shapes.btBoxShape;
import io.pzstorm.storm.bullet.collision.shapes.btCapsuleShape;
import io.pzstorm.storm.bullet.collision.shapes.btConvexHullShape;
import io.pzstorm.storm.bullet.collision.shapes.btConvexShape;
import io.pzstorm.storm.bullet.collision.shapes.btPolyhedralConvexShape;
import io.pzstorm.storm.bullet.collision.shapes.btSphereShape;
import io.pzstorm.storm.bullet.collision.shapes.btStaticPlaneShape;
import io.pzstorm.storm.bullet.libm.GlibcRand;
import io.pzstorm.storm.bullet.linearmath.btGlobals;
import io.pzstorm.storm.bullet.linearmath.btQuaternion;
import io.pzstorm.storm.bullet.linearmath.btScalar;
import io.pzstorm.storm.bullet.linearmath.btTransform;
import io.pzstorm.storm.bullet.linearmath.btVector3;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Bit-exact comparison of the narrowphase port against upstream Bullet 2.82 (double precision, GCC
 * 10.5 -O3).
 *
 * <p>narrowphase-golden.txt is the output of a C++ driver linked against the stock library; this
 * class is a line-by-line port of that driver (same LCG, same call order) and every printed double
 * is compared by its raw bits.
 */
class NarrowphaseGoldenTest implements UnitTest {

    /**
     * The C++ goldens ran in a fresh process. Other test classes in this JVM (the PZ world sets
     * gDeactivationTime and gContactAddedCallback) leave Bullet globals changed, so restore them.
     */
    @BeforeEach
    void freshLibrary() {
        btGlobals.resetAll();
        GlibcRand.srand(1);
    }

    private static Map<String, List<String>> golden;

    @BeforeAll
    static void load() throws IOException {
        golden = new HashMap<>();
        try (InputStream in =
                NarrowphaseGoldenTest.class.getResourceAsStream("narrowphase-golden.txt")) {
            assertNotNull(in, "narrowphase-golden.txt resource");
            BufferedReader r =
                    new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            List<String> cur = null;
            for (String line; (line = r.readLine()) != null; ) {
                if (line.startsWith("#section ")) {
                    cur = new ArrayList<>();
                    golden.put(line.substring(9), cur);
                } else {
                    cur.add(line);
                }
            }
        }
    }

    // ---- driver port
    // -------------------------------------------------------------------------------------

    private long s;
    private final List<String> out = new ArrayList<>();
    private StringBuilder L = new StringBuilder();

    private void seed(long v) {
        s = v;
    }

    private double rnd() {
        s = s * 6364136223846793005L + 1442695040888963407L;
        return (double) (s >>> 11) * (1.0 / 9007199254740992.0);
    }

    private double R(double lo, double hi) {
        double u = rnd();
        return lo + (hi - lo) * u;
    }

    private btVector3 RV(double lo, double hi) {
        double x = R(lo, hi);
        double y = R(lo, hi);
        double z = R(lo, hi);
        return new btVector3(x, y, z);
    }

    private btTransform RT(double range) {
        double x = R(-1, 1);
        double y = R(-1, 1);
        double z = R(-1, 1);
        double w = R(-1, 1);
        btQuaternion q = new btQuaternion(x, y, z, w);
        q.normalize();
        btVector3 o = RV(-range, range);
        return new btTransform(q, o);
    }

    private void H(double d) {
        L.append(' ').append(String.format("%016x", Double.doubleToRawLongBits(d)));
    }

    private void V(btVector3 v) {
        H(v.x);
        H(v.y);
        H(v.z);
    }

    private void I(long i) {
        L.append(' ').append(i);
    }

    private void I(boolean b) {
        I(b ? 1 : 0);
    }

    private void B(String tag) {
        L = new StringBuilder(tag);
    }

    private void E() {
        out.add(L.toString());
    }

    private btConvexShape mk(int kind) {
        switch (kind) {
            case 0:
                {
                    btVector3 h = RV(0.2, 1.5);
                    return new btBoxShape(h);
                }
            case 1:
                {
                    double r = R(0.2, 1.0);
                    return new btSphereShape(r);
                }
            case 2:
                {
                    double r = R(0.1, 0.6);
                    double h = R(0.2, 1.5);
                    return new btCapsuleShape(r, h);
                }
            default:
                {
                    btConvexHullShape hull = new btConvexHullShape();
                    for (int i = 0; i < 10; i++) {
                        btVector3 p = RV(-1, 1);
                        hull.addPoint(p);
                    }
                    return hull;
                }
        }
    }

    private class Recorder extends btDiscreteCollisionDetectorInterface.Result {
        @Override
        public void setShapeIdentifiersA(int partId0, int index0) {}

        @Override
        public void setShapeIdentifiersB(int partId1, int index1) {}

        @Override
        public void addContactPoint(btVector3 n, btVector3 p, double d) {
            B("  contact");
            V(n);
            V(p);
            H(d);
            E();
        }
    }

    private void check(String section) {
        List<String> exp = golden.get(section);
        assertNotNull(exp, "golden section " + section);
        int n = Math.min(exp.size(), out.size());
        for (int i = 0; i < n; i++) {
            assertEquals(exp.get(i), out.get(i), section + " line " + i);
        }
        assertEquals(exp.size(), out.size(), section + " line count");
    }

    // ---- sections
    // ----------------------------------------------------------------------------------------

    private void gjk(int solverKind) {
        seed(1000 + solverKind);
        int g0 = btGlobals.gNumGjkChecks, d0 = btGlobals.gNumDeepPenetrationChecks;
        for (int ka = 0; ka < 4; ka++)
            for (int kb = 0; kb < 4; kb++)
                for (int i = 0; i < 10; i++) {
                    btConvexShape a = mk(ka);
                    btConvexShape b = mk(kb);
                    btTransform ta = RT(0.5);
                    btTransform tb = RT(1.5);
                    btVoronoiSimplexSolver ss = new btVoronoiSimplexSolver();
                    btConvexPenetrationDepthSolver pd =
                            solverKind == 0
                                    ? new btGjkEpaPenetrationDepthSolver()
                                    : new btMinkowskiPenetrationDepthSolver();
                    btGjkPairDetector det = new btGjkPairDetector(a, b, ss, pd);
                    btDiscreteCollisionDetectorInterface.ClosestPointInput in =
                            new btDiscreteCollisionDetectorInterface.ClosestPointInput();
                    in.m_transformA.set(ta);
                    in.m_transformB.set(tb);
                    btPointCollector pc = new btPointCollector();
                    det.getClosestPoints(in, pc, null);
                    B("gjk");
                    I(ka);
                    I(kb);
                    I(i);
                    I(pc.m_hasResult);
                    if (pc.m_hasResult) {
                        V(pc.m_normalOnBInWorld);
                        V(pc.m_pointInWorld);
                        H(pc.m_distance);
                    }
                    I(det.m_lastUsedMethod);
                    I(det.m_degenerateSimplex);
                    I(det.m_curIter);
                    V(det.getCachedSeparatingAxis());
                    H(det.getCachedSeparatingDistance());
                    E();
                }
        B("counters");
        I(btGlobals.gNumGjkChecks - g0);
        I(btGlobals.gNumDeepPenetrationChecks - d0);
        E();
    }

    @Test
    void gjkPairDetectorWithEpaSolver() {
        gjk(0);
        check("gjk-epa");
    }

    @Test
    void gjkPairDetectorWithMinkowskiSolver() {
        gjk(1);
        check("gjk-minkowski");
    }

    private void printRes(btGjkEpaSolver2.sResults r) {
        V(r.witnesses[0]);
        V(r.witnesses[1]);
        V(r.normal);
        H(r.distance);
    }

    @Test
    void gjkEpaSolver2() {
        seed(2000);
        for (int ka = 0; ka < 4; ka++)
            for (int kb = 0; kb < 4; kb++)
                for (int i = 0; i < 10; i++) {
                    btConvexShape a = mk(ka);
                    btConvexShape b = mk(kb);
                    btTransform ta = RT(0.5);
                    btTransform tb = RT(1.5);
                    btVector3 guess = RV(-1, 1);
                    btGjkEpaSolver2.sResults r = new btGjkEpaSolver2.sResults();
                    boolean p = btGjkEpaSolver2.Penetration(a, ta, b, tb, guess, r);
                    B("pen");
                    I(ka);
                    I(kb);
                    I(i);
                    I(p);
                    I(r.status);
                    if (p) printRes(r);
                    E();
                    btGjkEpaSolver2.sResults r2 = new btGjkEpaSolver2.sResults();
                    boolean d = btGjkEpaSolver2.Distance(a, ta, b, tb, guess, r2);
                    B("dist");
                    I(d);
                    I(r2.status);
                    if (d) printRes(r2);
                    E();
                    btGjkEpaSolver2.sResults r3 = new btGjkEpaSolver2.sResults();
                    boolean sd = btGjkEpaSolver2.SignedDistance(a, ta, b, tb, guess, r3);
                    B("sdist");
                    I(sd);
                    I(r3.status);
                    if (sd) printRes(r3);
                    E();
                    btVector3 pos = RV(-1.5, 1.5);
                    double margin = R(0.0, 0.3);
                    btGjkEpaSolver2.sResults r4 = new btGjkEpaSolver2.sResults();
                    double sdv = btGjkEpaSolver2.SignedDistance(pos, margin, a, ta, r4);
                    B("sdpt");
                    H(sdv);
                    I(r4.status);
                    if (sdv < btScalar.SIMD_INFINITY) {
                        // this overload never writes results.distance (uninitialised in the C++
                        // driver)
                        V(r4.witnesses[0]);
                        V(r4.witnesses[1]);
                        V(r4.normal);
                    }
                    E();
                }
        check("epa");
    }

    @Test
    void voronoiSimplexSolver() {
        seed(3000);
        for (int seq = 0; seq < 200; seq++) {
            btVoronoiSimplexSolver ss = new btVoronoiSimplexSolver();
            ss.reset();
            btVector3 first = new btVector3(0, 0, 0);
            for (int k = 0; k < 6; k++) {
                btVector3 p = RV(-2, 2);
                btVector3 q = RV(-2, 2);
                btVector3 w = p.sub(q);
                if (k == 0) first.set(w);
                if (k == 3 && seq % 3 == 0) w.set(first);
                if (k == 2 && seq % 5 == 0) {
                    double sc = R(0.1, 2.0);
                    w.set(first.mul(sc));
                }
                boolean in = ss.inSimplex(w);
                B("sx");
                I(seq);
                I(k);
                I(in);
                if (in) {
                    E();
                    break;
                }
                ss.addVertex(w, p, q);
                btVector3 v = new btVector3();
                boolean ok = ss.closest(v);
                I(ok);
                I(ss.numVertices());
                I(ss.fullSimplex());
                if (ok) {
                    V(v);
                    btVector3 p1 = new btVector3(), p2 = new btVector3();
                    ss.compute_points(p1, p2);
                    V(p1);
                    V(p2);
                    H(ss.maxVertex());
                }
                E();
                if (!ok || ss.fullSimplex()) break;
            }
        }
        check("simplex");
    }

    @Test
    void convexCasts() {
        seed(4000);
        for (int ka = 0; ka < 4; ka++)
            for (int kb = 0; kb < 4; kb++)
                for (int i = 0; i < 8; i++) {
                    btConvexShape a = mk(ka);
                    btConvexShape b = mk(kb);
                    btTransform fromA = RT(3);
                    btTransform toA = RT(3);
                    btTransform fromB = RT(0.5);
                    btTransform toB = new btTransform(fromB);
                    for (int alg = 0; alg < 3; alg++) {
                        btVoronoiSimplexSolver ss = new btVoronoiSimplexSolver();
                        btGjkEpaPenetrationDepthSolver pd = new btGjkEpaPenetrationDepthSolver();
                        btConvexCast.CastResult res = new btConvexCast.CastResult();
                        boolean ok;
                        if (alg == 0) {
                            ok =
                                    new btGjkConvexCast(a, b, ss)
                                            .calcTimeOfImpact(fromA, toA, fromB, toB, res);
                        } else if (alg == 1) {
                            ok =
                                    new btSubsimplexConvexCast(a, b, ss)
                                            .calcTimeOfImpact(fromA, toA, fromB, toB, res);
                        } else {
                            ok =
                                    new btContinuousConvexCollision(a, b, ss, pd)
                                            .calcTimeOfImpact(fromA, toA, fromB, toB, res);
                        }
                        B("cast");
                        I(ka);
                        I(kb);
                        I(i);
                        I(alg);
                        I(ok);
                        H(res.m_fraction);
                        if (ok) {
                            V(res.m_normal);
                            V(res.m_hitPoint);
                        }
                        E();
                    }
                }
        check("casts");
    }

    @Test
    void polyhedralContactClipping() {
        seed(5000);
        int e0 = btGlobals.gExpectedNbTests, a0 = btGlobals.gActualNbTests;
        int[] kinds = {0, 3};
        for (int ka = 0; ka < 2; ka++)
            for (int kb = 0; kb < 2; kb++)
                for (int i = 0; i < 15; i++) {
                    btPolyhedralConvexShape a = (btPolyhedralConvexShape) mk(kinds[ka]);
                    btPolyhedralConvexShape b = (btPolyhedralConvexShape) mk(kinds[kb]);
                    a.initializePolyhedralFeatures();
                    b.initializePolyhedralFeatures();
                    btTransform ta = RT(0.5);
                    btTransform tb = RT(1.5);
                    btVector3 sep = new btVector3(0, 0, 0);
                    Recorder rec = new Recorder();
                    B("sat");
                    I(ka);
                    I(kb);
                    I(i);
                    E();
                    boolean found =
                            btPolyhedralContactClipping.findSeparatingAxis(
                                    a.getConvexPolyhedron(),
                                    b.getConvexPolyhedron(),
                                    ta,
                                    tb,
                                    sep,
                                    rec);
                    B("sep");
                    I(found);
                    if (found) V(sep);
                    E();
                    if (found) {
                        btPolyhedralContactClipping.clipHullAgainstHull(
                                sep,
                                a.getConvexPolyhedron(),
                                b.getConvexPolyhedron(),
                                ta,
                                tb,
                                (double) (-1e30f),
                                0.02,
                                rec);
                    }
                }
        B("counters");
        I(btGlobals.gExpectedNbTests - e0);
        I(btGlobals.gActualNbTests - a0);
        E();
        check("clip");
    }

    private void dumpManifold(btPersistentManifold m) {
        B("  mf");
        I(m.getNumContacts());
        E();
        for (int j = 0; j < m.getNumContacts(); j++) {
            btManifoldPoint p = m.getContactPoint(j);
            B("   pt");
            V(p.m_localPointA);
            V(p.m_localPointB);
            V(p.m_positionWorldOnA);
            V(p.m_positionWorldOnB);
            V(p.m_normalWorldOnB);
            H(p.m_distance1);
            I(p.m_lifeTime);
            H(p.m_appliedImpulse);
            E();
        }
    }

    @Test
    void persistentManifoldAddReplaceRefresh() {
        seed(6000);
        for (int seq = 0; seq < 12; seq++) {
            btPersistentManifold m = new btPersistentManifold(null, null, 0, 0.02, 0.02);
            btTransform trA = RT(0.5);
            btTransform trB = RT(0.5);
            for (int step = 0; step < 14; step++) {
                btVector3 pA = RV(-1, 1);
                btVector3 pB = RV(-1, 1);
                btVector3 n = RV(-1, 1);
                n.normalize();
                double dist = R(-0.05, 0.01);
                btManifoldPoint pt = new btManifoldPoint(pA, pB, n, dist);
                if (step % 3 == 2 && m.getNumContacts() > 0) {
                    btVector3 off = RV(-0.001, 0.001);
                    pt.m_localPointA.set(m.getContactPoint(0).m_localPointA.add(off));
                }
                pt.m_positionWorldOnA.set(trA.mul(pt.m_localPointA));
                pt.m_positionWorldOnB.set(trB.mul(pt.m_localPointB));
                pt.m_appliedImpulse = R(0, 1);
                int idx = m.getCacheEntry(pt);
                B("add");
                I(seq);
                I(step);
                I(idx);
                if (idx >= 0) m.replaceContactPoint(pt, idx);
                else idx = m.addManifoldPoint(pt);
                I(idx);
                E();
                dumpManifold(m);
                if (step % 4 == 3) {
                    btVector3 d = RV(-0.02, 0.02);
                    trA.getOrigin().addLocal(d);
                    m.refreshContactPoints(trA, trB);
                    B("refresh");
                    E();
                    dumpManifold(m);
                }
            }
        }
        check("manifold");
    }

    private class RayCb extends btTriangleRaycastCallback {
        RayCb(btVector3 f, btVector3 t, int fl) {
            super(f, t, fl);
        }

        @Override
        public double reportHit(btVector3 n, double frac, int partId, int triIdx) {
            B("  hit");
            V(n);
            H(frac);
            I(partId);
            I(triIdx);
            E();
            return frac;
        }
    }

    private class CastCb extends btTriangleConvexcastCallback {
        CastCb(btConvexShape s, btTransform f, btTransform t, btTransform w, double m) {
            super(s, f, t, w, m);
        }

        @Override
        public double reportHit(btVector3 n, btVector3 p, double frac, int partId, int triIdx) {
            B("  chit");
            V(n);
            V(p);
            H(frac);
            I(partId);
            I(triIdx);
            E();
            return frac;
        }
    }

    private btVector3[] triangle() {
        btVector3[] tri = new btVector3[3];
        tri[0] = RV(-1, 1);
        tri[1] = RV(-1, 1);
        tri[2] = RV(-1, 1);
        return tri;
    }

    @Test
    void triangleRaycastAndConvexcastCallbacks() {
        seed(7000);
        for (int i = 0; i < 80; i++) {
            btVector3 from = RV(-2, 2);
            btVector3 toOff = RV(-0.5, 0.5);
            btVector3 to = toOff.sub(from);
            RayCb cb = new RayCb(from, to, i % 4);
            for (int t = 0; t < 2; t++) {
                cb.processTriangle(triangle(), t, i);
            }
            B("ray");
            I(i);
            H(cb.m_hitFraction);
            E();
        }
        for (int k = 0; k < 3; k++)
            for (int i = 0; i < 20; i++) {
                btConvexShape sh = mk(k);
                btTransform from = RT(2);
                btTransform to = RT(2);
                btTransform w = RT(0.3);
                double margin = R(0.0, 0.05);
                CastCb cb = new CastCb(sh, from, to, w, margin);
                for (int t = 0; t < 2; t++) {
                    cb.processTriangle(triangle(), t, i);
                }
                B("ccast");
                I(k);
                I(i);
                H(cb.m_hitFraction);
                E();
            }
        check("triangles");
    }

    @Test
    void continuousConvexCollisionAgainstStaticPlane() {
        seed(8000);
        for (int ka = 0; ka < 4; ka++)
            for (int i = 0; i < 20; i++) {
                btConvexShape a = mk(ka);
                btVector3 n = RV(-1, 1);
                n.normalize();
                double c = R(-0.5, 0.5);
                btStaticPlaneShape plane = new btStaticPlaneShape(n, c);
                btTransform fromA = RT(3);
                btTransform toA = RT(3);
                btTransform fromB = RT(0.5);
                btTransform toB = new btTransform(fromB);
                btConvexCast.CastResult res = new btConvexCast.CastResult();
                btContinuousConvexCollision cc = new btContinuousConvexCollision(a, plane);
                boolean ok = cc.calcTimeOfImpact(fromA, toA, fromB, toB, res);
                B("pcast");
                I(ka);
                I(i);
                I(ok);
                H(res.m_fraction);
                if (ok) {
                    V(res.m_normal);
                    V(res.m_hitPoint);
                }
                E();
            }
        check("planecast");
    }
}
