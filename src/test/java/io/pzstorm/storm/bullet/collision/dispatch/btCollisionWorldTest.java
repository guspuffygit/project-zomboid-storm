package io.pzstorm.storm.bullet.collision.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.pzstorm.storm.UnitTest;
import io.pzstorm.storm.bullet.collision.broadphase.btDbvtBroadphase;
import io.pzstorm.storm.bullet.collision.narrowphase.btManifoldPoint;
import io.pzstorm.storm.bullet.collision.narrowphase.btPersistentManifold;
import io.pzstorm.storm.bullet.collision.shapes.PHY_ScalarType;
import io.pzstorm.storm.bullet.collision.shapes.btBoxShape;
import io.pzstorm.storm.bullet.collision.shapes.btBvhTriangleMeshShape;
import io.pzstorm.storm.bullet.collision.shapes.btCapsuleShape;
import io.pzstorm.storm.bullet.collision.shapes.btCollisionShape;
import io.pzstorm.storm.bullet.collision.shapes.btCompoundShape;
import io.pzstorm.storm.bullet.collision.shapes.btConvexShape;
import io.pzstorm.storm.bullet.collision.shapes.btSphereShape;
import io.pzstorm.storm.bullet.collision.shapes.btStaticPlaneShape;
import io.pzstorm.storm.bullet.collision.shapes.btStridingMeshInterface;
import io.pzstorm.storm.bullet.linearmath.btQuaternion;
import io.pzstorm.storm.bullet.linearmath.btTransform;
import io.pzstorm.storm.bullet.linearmath.btVector3;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Differential test of btCollisionWorld / btCollisionDispatcher / btDefaultCollisionConfiguration /
 * btSimulationIslandManager against stock Bullet 2.82 (libstock.so, g++ 10.5 -O3
 * -DBT_USE_DOUBLE_PRECISION). The C++ driver prints raw double bits for closest and all-hits
 * raycasts (plane, box, sphere, BVH mesh, compound, capsule), convex sweeps (sphere, box),
 * contactTest and contactPairTest callbacks, manifold bookkeeping over three discrete-collision
 * steps (order, m_index1a, contacts, lifetimes), island processing and swap-removal order. This
 * test replays the same scene and must produce the same text, line for line.
 */
class btCollisionWorldTest implements UnitTest {

    private final StringBuilder out = new StringBuilder();
    private final List<btCollisionObject> objs = new ArrayList<>();

    private int idx(Object o) {
        for (int i = 0; i < objs.size(); i++) if (objs.get(i) == o) return i;
        return -1;
    }

    private void p(String s) {
        out.append(s);
    }

    private void H(String k, double d) {
        out.append(' ').append(k).append('=').append(hex(d));
    }

    private static String hex(double d) {
        return String.format("%016x", Double.doubleToRawLongBits(d));
    }

    private void V(String k, btVector3 v) {
        out.append(' ').append(k).append('=');
        for (int i = 0; i < 3; i++) out.append(hex(v.get(i))).append(i < 2 ? "," : "");
    }

    /** One-subpart equivalent of btTriangleIndexVertexArray (not linked in PZBullet). */
    static final class IndexedMesh extends btStridingMeshInterface {
        final ByteBuffer vb, ib;
        final int numTris, numVerts;

        IndexedMesh(int numTris, int[] inds, int numVerts, double[] verts) {
            this.numTris = numTris;
            this.numVerts = numVerts;
            vb = ByteBuffer.allocate(verts.length * 8).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < verts.length; i++) vb.putDouble(i * 8, verts[i]);
            ib = ByteBuffer.allocate(inds.length * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < inds.length; i++) ib.putInt(i * 4, inds[i]);
        }

        private void fill(
                ByteBuffer[] vertexbase,
                int[] numverts,
                int[] type,
                int[] stride,
                ByteBuffer[] indexbase,
                int[] indexstride,
                int[] numfaces,
                int[] indicestype) {
            numverts[0] = numVerts;
            vertexbase[0] = vb;
            type[0] = PHY_ScalarType.PHY_DOUBLE;
            stride[0] = 3 * 8;
            numfaces[0] = numTris;
            indexbase[0] = ib;
            indexstride[0] = 3 * 4;
            indicestype[0] = PHY_ScalarType.PHY_INTEGER;
        }

        @Override
        public void getLockedVertexIndexBase(
                ByteBuffer[] vertexbase,
                int[] numverts,
                int[] type,
                int[] stride,
                ByteBuffer[] indexbase,
                int[] indexstride,
                int[] numfaces,
                int[] indicestype,
                int subpart) {
            fill(vertexbase, numverts, type, stride, indexbase, indexstride, numfaces, indicestype);
        }

        @Override
        public void getLockedReadOnlyVertexIndexBase(
                ByteBuffer[] vertexbase,
                int[] numverts,
                int[] type,
                int[] stride,
                ByteBuffer[] indexbase,
                int[] indexstride,
                int[] numfaces,
                int[] indicestype,
                int subpart) {
            fill(vertexbase, numverts, type, stride, indexbase, indexstride, numfaces, indicestype);
        }

        @Override
        public void unLockVertexBase(int subpart) {}

        @Override
        public void unLockReadOnlyVertexBase(int subpart) {}

        @Override
        public int getNumSubParts() {
            return 1;
        }

        @Override
        public void preallocateVertices(int numverts) {}

        @Override
        public void preallocateIndices(int numindices) {}
    }

    class AllHits extends btCollisionWorld.AllHitsRayResultCallback {
        AllHits(btVector3 a, btVector3 b) {
            super(a, b);
        }

        @Override
        public double addSingleResult(btCollisionWorld.LocalRayResult r, boolean ws) {
            p(
                    "  hit obj="
                            + idx(r.m_collisionObject)
                            + " part="
                            + (r.m_localShapeInfo != null ? r.m_localShapeInfo.m_shapePart : -9)
                            + " tri="
                            + (r.m_localShapeInfo != null
                                    ? r.m_localShapeInfo.m_triangleIndex
                                    : -9));
            H("f", r.m_hitFraction);
            V("nl", r.m_hitNormalLocal);
            p(" ws=" + (ws ? 1 : 0) + "\n");
            return super.addSingleResult(r, ws);
        }
    }

    class Contacts extends btCollisionWorld.ContactResultCallback {
        @Override
        public double addSingleResult(
                btManifoldPoint cp,
                btCollisionObjectWrapper w0,
                int p0,
                int i0,
                btCollisionObjectWrapper w1,
                int p1,
                int i1) {
            int a = idx(w0.getCollisionObject()), b = idx(w1.getCollisionObject());
            p("  contact a=" + a + " b=" + b);
            // stock 2.82 btManifoldResult leaves part/index ids uninitialized unless a
            // concave/compound algorithm sets them, so only those sides are compared
            p(a == 3 || a == 4 ? " p0=" + p0 + " i0=" + i0 : " p0=? i0=?");
            p(b == 3 || b == 4 ? " p1=" + p1 + " i1=" + i1 : " p1=? i1=?");
            H("d", cp.getDistance());
            V("pa", cp.m_positionWorldOnA);
            V("pb", cp.m_positionWorldOnB);
            V("n", cp.m_normalWorldOnB);
            V("la", cp.m_localPointA);
            V("lb", cp.m_localPointB);
            p("\n");
            return 0;
        }
    }

    class Isl extends btSimulationIslandManager.IslandCallback {
        @Override
        public void processIsland(
                Object[] bodies,
                int bodiesOffset,
                int numBodies,
                Object[] manifolds,
                int manifoldsOffset,
                int numManifolds,
                int islandId) {
            p("  island id=" + islandId + " bodies=");
            for (int i = 0; i < numBodies; i++) p(idx(bodies[bodiesOffset + i]) + ",");
            p(" manifolds=");
            for (int i = 0; i < numManifolds; i++) {
                btPersistentManifold m = (btPersistentManifold) manifolds[manifoldsOffset + i];
                p(idx(m.getBody0()) + "-" + idx(m.getBody1()) + ":" + m.getNumContacts() + ",");
            }
            p("\n");
        }
    }

    private static btVector3 v(double x, double y, double z) {
        return new btVector3(x, y, z);
    }

    private String run() {
        btDefaultCollisionConfiguration cfg = new btDefaultCollisionConfiguration();
        btCollisionDispatcher disp = new btCollisionDispatcher(cfg);
        btDbvtBroadphase bp = new btDbvtBroadphase();
        btCollisionWorld world = new btCollisionWorld(disp, bp, cfg);

        btStaticPlaneShape plane = new btStaticPlaneShape(v(0, 1, 0), 0);
        btBoxShape box = new btBoxShape(v(1, 0.5, 2));
        btSphereShape sphere = new btSphereShape(0.75);
        btCapsuleShape capsule = new btCapsuleShape(0.3, 1.0);
        double[] verts = new double[16 * 3];
        int[] inds = new int[9 * 2 * 3];
        for (int z = 0; z < 4; z++)
            for (int x = 0; x < 4; x++) {
                int vi = z * 4 + x;
                verts[vi * 3] = x * 1.0 - 1.5;
                verts[vi * 3 + 1] = ((x * 7 + z * 3) % 5) * 0.1;
                verts[vi * 3 + 2] = z * 1.0 - 1.5;
            }
        int t = 0;
        for (int z = 0; z < 3; z++)
            for (int x = 0; x < 3; x++) {
                int a = z * 4 + x, b = a + 1, c = a + 4, d = c + 1;
                inds[t++] = a;
                inds[t++] = c;
                inds[t++] = b;
                inds[t++] = b;
                inds[t++] = c;
                inds[t++] = d;
            }
        IndexedMesh mesh = new IndexedMesh(18, inds, 16, verts);
        btBvhTriangleMeshShape meshShape = new btBvhTriangleMeshShape(mesh, true, true);
        btCompoundShape compound = new btCompoundShape();
        {
            btTransform ct = new btTransform();
            ct.setIdentity();
            ct.setOrigin(v(0.5, 0, 0));
            compound.addChildShape(ct, box);
            ct.setIdentity();
            ct.setOrigin(v(-1, 0.5, 0.25));
            ct.setRotation(new btQuaternion(v(0, 0, 1), 0.4));
            compound.addChildShape(ct, sphere);
        }

        btCollisionShape[] shapes = {plane, box, sphere, meshShape, compound, capsule};
        btVector3[] pos = {
            v(0, 0, 0), v(0, 2, 0), v(3, 1, 0.5), v(-4, 0, 0), v(0, 1, 5), v(2, 0.5, -3)
        };
        btQuaternion[] rot = {
            btQuaternion.getIdentity(),
            new btQuaternion(v(0, 1, 0), 0.3),
            btQuaternion.getIdentity(),
            new btQuaternion(v(0, 1, 0), 0.1),
            new btQuaternion(v(1, 0, 0), 0.2),
            new btQuaternion(v(0, 0, 1), 0.5)
        };
        for (int i = 0; i < 6; i++) {
            btCollisionObject o = new btCollisionObject();
            o.setCollisionShape(shapes[i]);
            btTransform tr = new btTransform();
            tr.setIdentity();
            tr.setOrigin(pos[i]);
            tr.setRotation(rot[i]);
            o.setWorldTransform(tr);
            objs.add(o);
            world.addCollisionObject(o);
        }
        world.updateAabbs();

        btVector3[][] rays = {
            {v(0, 10, 0), v(0, -10, 0)},
            {v(-10, 1, 0.3), v(10, 1, 0.3)},
            {v(3, 5, 0.5), v(3, -5, 0.6)},
            {v(-4.2, 5, 0.3), v(-3.9, -5, -0.2)},
            {v(-5.3, 3, -1.1), v(-2.8, -1, 1.2)},
            {v(0, 1.5, 10), v(0, 0.8, -10)},
            {v(-1, 1.5, 5.2), v(-1, 1.5, 3)},
            {v(2, 5, -3), v(2.1, -2, -3.05)},
            {v(10, 10, 10), v(11, 11, 11)},
            {v(0.3, 3, 0.2), v(0.31, 3, 0.2)},
            {v(-6, 0.25, -0.5), v(6, 0.25, -0.5)},
            {v(0.5, 4, 5), v(0.5, -1, 5.1)}
        };
        int nr = rays.length;
        for (int r = 0; r < nr; r++) {
            btCollisionWorld.ClosestRayResultCallback cb =
                    new btCollisionWorld.ClosestRayResultCallback(rays[r][0], rays[r][1]);
            world.rayTest(rays[r][0], rays[r][1], cb);
            p("ray " + r + " has=" + (cb.hasHit() ? 1 : 0) + " obj=" + idx(cb.m_collisionObject));
            H("f", cb.m_closestHitFraction);
            if (cb.hasHit()) {
                V("n", cb.m_hitNormalWorld);
                V("p", cb.m_hitPointWorld);
            }
            p("\n");
        }
        for (int r = 0; r < nr; r++) {
            AllHits cb = new AllHits(rays[r][0], rays[r][1]);
            world.rayTest(rays[r][0], rays[r][1], cb);
            p("allhits " + r + " n=" + cb.m_collisionObjects.size() + "\n");
            for (int i = 0; i < cb.m_collisionObjects.size(); i++) {
                p("  r obj=" + idx(cb.m_collisionObjects.get(i)));
                H("f", cb.m_hitFractions.get(i));
                V("n", cb.m_hitNormalWorld.get(i));
                V("p", cb.m_hitPointWorld.get(i));
                p("\n");
            }
        }
        btSphereShape castS = new btSphereShape(0.2);
        btBoxShape castB = new btBoxShape(v(0.2, 0.3, 0.1));
        btConvexShape[] casts = {castS, castB};
        for (int c = 0; c < 2; c++)
            for (int r = 0; r < nr; r++) {
                btTransform f = new btTransform(), to = new btTransform();
                f.setIdentity();
                to.setIdentity();
                f.setOrigin(rays[r][0]);
                to.setOrigin(rays[r][1]);
                f.setRotation(new btQuaternion(v(0, 1, 0), 0.1 * r));
                to.setRotation(new btQuaternion(v(1, 0, 0), 0.05 * r));
                btCollisionWorld.ClosestConvexResultCallback cb =
                        new btCollisionWorld.ClosestConvexResultCallback(rays[r][0], rays[r][1]);
                world.convexSweepTest(casts[c], f, to, cb, c * 0.01);
                p(
                        "sweep "
                                + c
                                + " "
                                + r
                                + " has="
                                + (cb.hasHit() ? 1 : 0)
                                + " obj="
                                + idx(cb.m_hitCollisionObject));
                H("f", cb.m_closestHitFraction);
                if (cb.hasHit()) {
                    V("n", cb.m_hitNormalWorld);
                    V("p", cb.m_hitPointWorld);
                }
                p("\n");
            }
        btBoxShape probeBox = new btBoxShape(v(0.6, 0.6, 0.6));
        btSphereShape probeS = new btSphereShape(1.0);
        btVector3[] probes = {
            v(0, 0.3, 0),
            v(2.5, 1, 0.3),
            v(-4, 0.2, 0.2),
            v(0, 1.4, 4.8),
            v(2, 0.4, -2.6),
            v(0.8, 1.2, 1)
        };
        for (int s = 0; s < 2; s++)
            for (int pi = 0; pi < 6; pi++) {
                btCollisionObject probe = new btCollisionObject();
                probe.setCollisionShape(s == 0 ? probeBox : probeS);
                btTransform tr = new btTransform();
                tr.setIdentity();
                tr.setOrigin(probes[pi]);
                tr.setRotation(new btQuaternion(v(1, 1, 0).normalized(), 0.3 * pi));
                probe.setWorldTransform(tr);
                p("contactTest " + s + " " + pi + "\n");
                world.contactTest(probe, new Contacts());
            }
        p("pair 1-0\n");
        world.contactPairTest(objs.get(1), objs.get(0), new Contacts());

        for (int i = 1; i < 6; i++) if (i != 3) objs.get(i).setCollisionFlags(0);
        setOrigin(objs.get(2), v(0.8, 2.9, 0.2));
        setOrigin(objs.get(1), v(0, 0.4, 0));
        setOrigin(objs.get(5), v(-3.5, 0.5, 0.2));
        btSimulationIslandManager im = new btSimulationIslandManager();
        for (int step = 0; step < 3; step++) {
            world.performDiscreteCollisionDetection();
            p(
                    "step "
                            + step
                            + " manifolds="
                            + disp.getNumManifolds()
                            + " pairs="
                            + bp.getOverlappingPairCache().getNumOverlappingPairs()
                            + "\n");
            for (int m = 0; m < disp.getNumManifolds(); m++) {
                btPersistentManifold pm = disp.getManifoldByIndexInternal(m);
                p(
                        "  m "
                                + m
                                + " "
                                + idx(pm.getBody0())
                                + "-"
                                + idx(pm.getBody1())
                                + " n="
                                + pm.getNumContacts()
                                + " idx1a="
                                + pm.m_index1a
                                + "\n");
                for (int j = 0; j < pm.getNumContacts(); j++) {
                    btManifoldPoint cp = pm.getContactPoint(j);
                    p("   ");
                    H("d", cp.getDistance());
                    V("pb", cp.m_positionWorldOnB);
                    V("n", cp.m_normalWorldOnB);
                    p(" life=" + cp.getLifeTime() + "\n");
                }
            }
            im.updateActivationState(world, disp);
            im.buildAndProcessIslands(disp, world, new Isl());
            for (int i = 0; i < 6; i++)
                p(
                        "  tag "
                                + i
                                + "="
                                + objs.get(i).getIslandTag()
                                + "/"
                                + objs.get(i).getActivationState());
            p("\n");
            btTransform tr = new btTransform(objs.get(2).getWorldTransform());
            tr.setOrigin(tr.getOrigin().add(v(0, -0.2, 0)));
            objs.get(2).setWorldTransform(tr);
        }
        world.removeCollisionObject(objs.get(1));
        p("after remove: n=" + world.getNumCollisionObjects() + " order=");
        for (int i = 0; i < world.getNumCollisionObjects(); i++)
            p(idx(world.getCollisionObjectArray().get(i)) + ",");
        p("\n");
        world.performDiscreteCollisionDetection();
        p("post-remove manifolds=" + disp.getNumManifolds() + "\n");
        for (int m = 0; m < disp.getNumManifolds(); m++) {
            btPersistentManifold pm = disp.getManifoldByIndexInternal(m);
            p(
                    "  m "
                            + m
                            + " "
                            + idx(pm.getBody0())
                            + "-"
                            + idx(pm.getBody1())
                            + " n="
                            + pm.getNumContacts()
                            + " idx1a="
                            + pm.m_index1a
                            + "\n");
        }
        return out.toString();
    }

    private static void setOrigin(btCollisionObject o, btVector3 origin) {
        btTransform tr = new btTransform(o.getWorldTransform());
        tr.setOrigin(origin);
        o.setWorldTransform(tr);
    }

    @Test
    void worldQueriesMatchStockBullet() throws Exception {
        List<String> expected = new ArrayList<>();
        try (InputStream in = getClass().getResourceAsStream("collision_world_golden.txt")) {
            assertNotNull(in, "golden resource");
            BufferedReader br =
                    new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line; (line = br.readLine()) != null; ) expected.add(line);
        }
        String[] got = run().split("\n", -1);
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i), i < got.length ? got[i] : "<missing>", "line " + (i + 1));
        }
        assertEquals(expected.size(), got.length - 1, "line count");
    }
}
