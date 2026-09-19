package io.pzstorm.storm.bullet.collision.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.pzstorm.storm.UnitTest;
import io.pzstorm.storm.bullet.collision.broadphase.btCollisionAlgorithm;
import io.pzstorm.storm.bullet.collision.broadphase.btDbvtBroadphase;
import io.pzstorm.storm.bullet.collision.narrowphase.btManifoldPoint;
import io.pzstorm.storm.bullet.collision.narrowphase.btPersistentManifold;
import io.pzstorm.storm.bullet.collision.shapes.btBoxShape;
import io.pzstorm.storm.bullet.collision.shapes.btBvhTriangleMeshShape;
import io.pzstorm.storm.bullet.collision.shapes.btCapsuleShape;
import io.pzstorm.storm.bullet.collision.shapes.btCollisionShape;
import io.pzstorm.storm.bullet.collision.shapes.btCompoundShape;
import io.pzstorm.storm.bullet.collision.shapes.btConvexHullShape;
import io.pzstorm.storm.bullet.collision.shapes.btSphereShape;
import io.pzstorm.storm.bullet.collision.shapes.btStaticPlaneShape;
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
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;

/**
 * Whole-narrowphase differential test: a btCollisionWorld holding every shape pairing that reaches
 * the dispatch algorithms (sphere-sphere, box-box, capsule-capsule, polyhedral hull-hull,
 * hull-triangle, sphere-triangle, convex-plane, convex-convex GJK, compound, compound-compound) is
 * stepped through 240 scripted poses in three configurations (default; 3/3 multipoint perturbation;
 * SAT). Every step's manifolds and contact points are hashed and compared with stock Bullet 2.82
 * (gcc 10.5 -O3, double). Every 20 steps calculateTimeOfImpact is run for every pair. The C++
 * driver is a line-for-line mirror of this file.
 */
class DispatchAlgorithmsWorldTest implements UnitTest {

    private long h;

    private void hreset() {
        h = 1469598103934665603L;
    }

    private void hb(long b) {
        for (int i = 0; i < 8; i++) {
            h ^= (b >>> (8 * i)) & 0xff;
            h *= 1099511628211L;
        }
    }

    private void hd(double d) {
        hb(Double.doubleToRawLongBits(d));
    }

    private void hi(long v) {
        hb(v);
    }

    private void hv(btVector3 v) {
        hd(v.x());
        hd(v.y());
        hd(v.z());
    }

    private int rng;

    private double rr() {
        rng = rng * 1103515245 + 12345;
        return ((rng >>> 8) & 0xffff) / 32768.0 - 1.0;
    }

    private static final class Scene {
        btDefaultCollisionConfiguration cfg;
        btCollisionDispatcher disp;
        btDbvtBroadphase bp;
        btCollisionWorld world;
        final btCollisionObject[] obj = new btCollisionObject[16];
        int n;
    }

    private static double frac(double v) {
        return v - (long) v;
    }

    private static btTransform poseAt(int k, int step) {
        double s = step * 0.05 + k * 0.7;
        btQuaternion q =
                new btQuaternion(
                        0.3 + 0.2 * k * s - 0.01 * s * s,
                        0.5 - 0.03 * s * k,
                        0.1 * s + 0.2,
                        1.0 + 0.02 * s * s);
        q.normalize();
        double t = step * 0.025;
        double ph = k * 0.37;
        double x = 0.8 * ((k % 4) - 1.5) + 1.6 * (frac(t + ph) - 0.5) * ((k & 1) != 0 ? 1 : -1);
        double z = 0.8 * ((k / 4) - 1.0) + 1.4 * (frac(t * 1.3 + ph) - 0.5);
        double y = 0.25 + 0.9 * (frac(t * 0.7 + ph));
        if (k == 12) {
            btTransform o = poseAt(11, step);
            double f = frac(t * 0.9);
            x = o.getOrigin().x() + 1.4 * (f - 0.5);
            y = o.getOrigin().y() + 0.3 * f;
            z = o.getOrigin().z() + 0.6 * (0.5 - f);
        }
        return new btTransform(q, new btVector3(x, y, z));
    }

    private static btTransform tr(double x, double y, double z) {
        return new btTransform(new btQuaternion(0, 0, 0, 1), new btVector3(x, y, z));
    }

    private btCollisionObject add(Scene s, btCollisionShape sh, int flags) {
        btCollisionObject o = new btCollisionObject();
        o.setCollisionShape(sh);
        o.setCollisionFlags(flags);
        o.setUserIndex(s.n);
        o.setFriction(0.3 + 0.05 * s.n);
        o.setRestitution(0.1 * (s.n % 3));
        s.obj[s.n++] = o;
        s.world.addCollisionObject(o);
        return o;
    }

    private Scene build(int variant) {
        rng = 12345;
        Scene s = new Scene();
        s.cfg = new btDefaultCollisionConfiguration();
        if (variant == 1) {
            s.cfg.setConvexConvexMultipointIterations(3, 3);
            s.cfg.setPlaneConvexMultipointIterations(3, 3);
        }
        s.disp = new btCollisionDispatcher(s.cfg);
        s.bp = new btDbvtBroadphase();
        s.world = new btCollisionWorld(s.disp, s.bp, s.cfg);
        if (variant == 2) s.world.getDispatchInfo().m_enableSatConvex = true;
        s.n = 0;
        add(
                s,
                new btStaticPlaneShape(new btVector3(0, 1, 0), 0),
                btCollisionObject.CF_STATIC_OBJECT);
        TestTriangleMesh mesh = new TestTriangleMesh();
        for (int i = 0; i < 8; i++)
            for (int j = 0; j < 8; j++) {
                double x0 = -4 + i, z0 = -4 + j;
                btVector3 a = new btVector3(x0, 0.3 * (rr() + 1), z0);
                btVector3 b = new btVector3(x0 + 1, 0.3 * (rr() + 1), z0);
                btVector3 c = new btVector3(x0, 0.3 * (rr() + 1), z0 + 1);
                btVector3 d = new btVector3(x0 + 1, 0.3 * (rr() + 1), z0 + 1);
                mesh.addTriangle(a, b, c);
                mesh.addTriangle(b, d, c);
            }
        {
            btCollisionObject o =
                    add(
                            s,
                            new btBvhTriangleMeshShape(mesh, true),
                            btCollisionObject.CF_STATIC_OBJECT);
            o.setWorldTransform(tr(0.5, 0.0, 0.3));
        }
        add(s, new btSphereShape(0.5), 0);
        add(s, new btSphereShape(0.7), 0);
        add(s, new btBoxShape(new btVector3(0.5, 0.4, 0.6)), 0);
        add(s, new btBoxShape(new btVector3(0.3, 0.8, 0.5)), 0);
        add(s, new btCapsuleShape(0.3, 1.0), 0);
        add(s, new btCapsuleShape(0.4, 0.6), 0);
        {
            btConvexHullShape hs = new btConvexHullShape();
            for (int i = 0; i < 14; i++) {
                double px = 0.6 * rr();
                double py = 0.5 * rr();
                double pz = 0.6 * rr();
                hs.addPoint(new btVector3(px, py, pz));
            }
            hs.initializePolyhedralFeatures();
            add(s, hs, 0);
        }
        {
            btConvexHullShape hs = new btConvexHullShape();
            for (int i = 0; i < 8; i++)
                hs.addPoint(
                        new btVector3(
                                ((i & 1) != 0 ? 0.5 : -0.5) + 0.1 * ((i & 2) != 0 ? 1 : 0),
                                ((i & 2) != 0 ? 0.35 : -0.35),
                                ((i & 4) != 0 ? 0.45 : -0.45)));
            hs.initializePolyhedralFeatures();
            add(s, hs, 0);
        }
        {
            btConvexHullShape hs = new btConvexHullShape();
            for (int i = 0; i < 10; i++) {
                double px = 0.5 * rr();
                double py = 0.5 * rr();
                double pz = 0.5 * rr();
                hs.addPoint(new btVector3(px, py, pz));
            }
            add(s, hs, 0);
        }
        {
            btCompoundShape c = new btCompoundShape();
            c.addChildShape(tr(0.4, 0, 0), new btBoxShape(new btVector3(0.3, 0.3, 0.3)));
            c.addChildShape(tr(-0.4, 0.1, 0), new btSphereShape(0.35));
            add(s, c, 0);
        }
        {
            btCompoundShape c = new btCompoundShape();
            c.addChildShape(tr(0, 0, 0.5), new btBoxShape(new btVector3(0.2, 0.4, 0.2)));
            c.addChildShape(tr(0, 0, -0.5), new btBoxShape(new btVector3(0.3, 0.2, 0.2)));
            c.addChildShape(tr(0.3, 0.2, 0), new btCapsuleShape(0.15, 0.5));
            add(s, c, 0);
        }
        return s;
    }

    private static boolean setsIds(btCollisionShape sh) {
        return sh.isCompound() || sh instanceof btBvhTriangleMeshShape;
    }

    private String dumpStep(Scene s, String tag, int step) {
        hreset();
        int nm = s.disp.getNumManifolds();
        int total = 0;
        hi(nm);
        for (int m = 0; m < nm; m++) {
            btPersistentManifold pm = s.disp.getManifoldByIndexInternal(m);
            hi(pm.getBody0().getUserIndex());
            hi(pm.getBody1().getUserIndex());
            int nc = pm.getNumContacts();
            hi(nc);
            total += nc;
            boolean ids0 = setsIds(pm.getBody0().getCollisionShape());
            boolean ids1 = setsIds(pm.getBody1().getCollisionShape());
            for (int c = 0; c < nc; c++) {
                btManifoldPoint p = pm.getContactPoint(c);
                hv(p.m_localPointA);
                hv(p.m_localPointB);
                hv(p.m_positionWorldOnB);
                hv(p.m_positionWorldOnA);
                hv(p.m_normalWorldOnB);
                hd(p.m_distance1);
                hd(p.m_combinedFriction);
                hd(p.m_combinedRestitution);
                // btManifoldResult part/index ids are uninitialised in C++ unless the algorithm
                // sets them (only the compound and triangle-mesh sides are): hash only those.
                if (ids0) {
                    hi(p.m_partId0);
                    hi(p.m_index0);
                }
                if (ids1) {
                    hi(p.m_partId1);
                    hi(p.m_index1);
                }
                hi(p.m_lifeTime);
            }
        }
        return String.format("%s %d %d %d %016x", tag, step, nm, total, h);
    }

    private String toi(Scene s, String tag, int step) {
        hreset();
        for (int a = 2; a < s.n; a++) {
            btCollisionObject o = s.obj[a];
            o.setInterpolationWorldTransform(poseAt(a, step + 9));
            o.setCcdMotionThreshold(0.01);
            o.setCcdSweptSphereRadius(0.2 + 0.01 * a);
            o.setHitFraction(1.0);
        }
        for (int a = 2; a < s.n; a++)
            for (int b = 0; b < s.n; b++) {
                if (a == b) continue;
                btCollisionObject oa = s.obj[a];
                btCollisionObject ob = s.obj[b];
                // compound vs non-compound TOI is btAssert(0) + undefined behaviour upstream
                if (oa.getCollisionShape().isCompound() != ob.getCollisionShape().isCompound())
                    continue;
                btCollisionObjectWrapper wa =
                        new btCollisionObjectWrapper(
                                null, oa.getCollisionShape(), oa, oa.getWorldTransform(), -1, -1);
                btCollisionObjectWrapper wb =
                        new btCollisionObjectWrapper(
                                null, ob.getCollisionShape(), ob, ob.getWorldTransform(), -1, -1);
                btCollisionAlgorithm algo = s.disp.findAlgorithm(wa, wb);
                btManifoldResult res = new btManifoldResult(wa, wb);
                double f = algo.calculateTimeOfImpact(oa, ob, s.world.getDispatchInfo(), res);
                hd(f);
                hd(oa.getHitFraction());
                hd(ob.getHitFraction());
                algo.destroy();
                s.disp.freeCollisionAlgorithm(algo.m_allocAddress);
            }
        for (int a = 2; a < s.n; a++) s.obj[a].setHitFraction(1.0);
        return String.format("%s %d %016x", tag, step, h);
    }

    @Test
    void collisionWorldNarrowphaseMatchesUpstream() throws IOException {
        List<String> expected = new ArrayList<>();
        try (InputStream is =
                        DispatchAlgorithmsWorldTest.class.getResourceAsStream(
                                "dispatch-world.txt.gz");
                BufferedReader r =
                        new BufferedReader(
                                new InputStreamReader(
                                        new GZIPInputStream(is), StandardCharsets.US_ASCII))) {
            String line;
            while ((line = r.readLine()) != null) expected.add(line);
        }
        int li = 0;
        String[] tags = {"W0", "W1", "W2"};
        for (int v = 0; v < 3; v++) {
            Scene s = build(v);
            for (int step = 0; step < 240; step++) {
                for (int k = 2; k < s.n; k++) s.obj[k].setWorldTransform(poseAt(k, step));
                s.world.performDiscreteCollisionDetection();
                assertEquals(expected.get(li++), dumpStep(s, tags[v], step), "variant " + v);
                if (step % 20 == 0)
                    assertEquals(expected.get(li++), toi(s, "T", step), "toi v" + v);
            }
        }
        assertEquals(expected.size(), li);
    }
}
