// Port of PZ glue WorldSimulation (WorldSimulation.cpp, libPZBulletNoOpenGL64 1.0.0.28) plus the
// file-static tickCallback @00172c20 / collisionCallback @00172b60.
// ctor @00179040, dtor @00178e?? (see docs/re-bullet/pz-world.md for the full address table).
// C++ layout notes (NoGL): +0 m_isServer, +8 m_dynamicsWorld, +0x28..+0x3c cell bounds,
// +0x40/+0x44 offsets, +0x48..+0x60 chunkMaps[4], +0x68 m_vehicles, +0xa0 m_ragdolls,
// +0x120 m_ballistics, +0x1a0 m_ballisticsTargets, +0x230 physicsObjects, +0x268 constraints,
// +0x290 wallN pools[8] (0x50 each), ... +0xd30 floorPools, +0xd60 stairs pool, +0xdb0 floor
// shapes, +0xde8 floorKey, +0xe28 wallN shapes[8], +0xe68 wallW, +0xea8 wallS, +0xee8 wallE,
// +0xf28 ground-cell shape, +0xf30 physics object shape, +0xf38 thrown object shape,
// +0xf60 serverCells, +0xf78.. stairs dims.
package io.pzstorm.storm.bullet.pz;

import io.pzstorm.storm.bullet.BulletUpcalls;
import io.pzstorm.storm.bullet.collision.broadphase.btDbvtBroadphase;
import io.pzstorm.storm.bullet.collision.dispatch.ContactAddedCallback;
import io.pzstorm.storm.bullet.collision.dispatch.btCollisionDispatcher;
import io.pzstorm.storm.bullet.collision.dispatch.btCollisionObject;
import io.pzstorm.storm.bullet.collision.dispatch.btCollisionObjectWrapper;
import io.pzstorm.storm.bullet.collision.dispatch.btDefaultCollisionConfiguration;
import io.pzstorm.storm.bullet.collision.narrowphase.btManifoldPoint;
import io.pzstorm.storm.bullet.collision.shapes.btBoxShape;
import io.pzstorm.storm.bullet.collision.shapes.btCollisionShape;
import io.pzstorm.storm.bullet.collision.shapes.btCompoundShape;
import io.pzstorm.storm.bullet.collision.shapes.btConvexHullShape;
import io.pzstorm.storm.bullet.dynamics.btDiscreteDynamicsWorld;
import io.pzstorm.storm.bullet.dynamics.btDynamicsWorld;
import io.pzstorm.storm.bullet.dynamics.btRigidBody;
import io.pzstorm.storm.bullet.dynamics.constraintsolver.btSequentialImpulseConstraintSolver;
import io.pzstorm.storm.bullet.dynamics.constraintsolver.btTypedConstraint;
import io.pzstorm.storm.bullet.linearmath.btDefaultMotionState;
import io.pzstorm.storm.bullet.linearmath.btGlobals;
import io.pzstorm.storm.bullet.linearmath.btScalar;
import io.pzstorm.storm.bullet.linearmath.btTransform;
import io.pzstorm.storm.bullet.linearmath.btVector3;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.TreeMap;

public class WorldSimulation {

    /** Height of one PZ level in Bullet units (2.449490010738373 / 3). */
    private static final double LEVEL_SCALE = 0.8164966702461243;

    public static WorldSimulation instance;

    /**
     * Which libPZBullet build this port emulates. zombie.core.physics.Bullet.init loads the
     * NoOpenGL lib only when {@code GameServer.server && OSValidator.isUnix()}; every other case
     * runs the GL lib, whose ctor registers {@link #m_debugDrawer}. Set by WorldNatives.initWorld.
     */
    public static boolean glBuild = false;

    /** +0 */
    public boolean m_isServer;

    /** +8 (PZDiscreteDynamicsWorld*) */
    public btDiscreteDynamicsWorld m_dynamicsWorld;

    public btDefaultCollisionConfiguration m_collisionConfiguration;
    public btCollisionDispatcher m_dispatcher;
    public btDbvtBroadphase m_broadphase;
    public btSequentialImpulseConstraintSolver m_solver;

    public int m_minCellX;
    public int m_minCellY;
    public int m_maxCellX;
    public int m_maxCellY;

    /** +0x40 */
    public int m_offsetX;

    /** +0x44 */
    public int m_offsetY;

    /** +0x48 ChunkMap*[4] */
    public final ChunkMap[] m_chunkMaps = new ChunkMap[4];

    /** GL build: embedded PZGLDebugDrawer (constructed first, registered only in the GL build). */
    public final PZGLDebugDrawer m_debugDrawer;

    public final TreeMap<Integer, PZVehicle> m_vehicles = new TreeMap<>();
    public final PZRagdollPool m_ragdolls = new PZRagdollPool();
    public final PZBallisticsPool m_ballistics = new PZBallisticsPool();
    public final PZBallisticsTargetPool m_ballisticsTargets = new PZBallisticsTargetPool();

    /** +0x230 std::map<int, btRigidBody*> */
    public final TreeMap<Integer, btRigidBody> m_physicsObjects = new TreeMap<>();

    /** +0x250 counter shared by addPhysicsObject / throwPhysicsObject. */
    public int m_physicsObjectCounter;

    /** +0x268 std::map<int, Constraint> */
    public final TreeMap<Integer, Constraint> m_constraints = new TreeMap<>();

    /** +0x258 constraint id counter; ctor zeroes it, first id is 1. */
    public int m_constraintCounter = 0; // WS+0x258 (the offset, not the value)

    @SuppressWarnings("unchecked")
    public final ArrayDeque<btRigidBody>[] m_wallNPool = new ArrayDeque[8];

    @SuppressWarnings("unchecked")
    public final ArrayDeque<btRigidBody>[] m_wallWPool = new ArrayDeque[8];

    @SuppressWarnings("unchecked")
    public final ArrayDeque<btRigidBody>[] m_wallSPool = new ArrayDeque[8];

    @SuppressWarnings("unchecked")
    public final ArrayDeque<btRigidBody>[] m_wallEPool = new ArrayDeque[8];

    public final ArrayDeque<btRigidBody> m_solidPool = new ArrayDeque<>();
    public final ArrayDeque<btRigidBody> m_treePool = new ArrayDeque<>();

    /** +0xd30 std::map<int, FloorPool> keyed by h<<16|w. */
    public final TreeMap<Integer, FloorPool> m_floorPools = new TreeMap<>();

    /** +0xd60 std::deque<btCollisionObject*> */
    public final ArrayDeque<btCollisionObject> m_stairsPool = new ArrayDeque<>();

    /** +0xdb0 std::map<int, btBoxShape*> keyed by h<<16|w. */
    public final TreeMap<Integer, btBoxShape> m_floorShapes = new TreeMap<>();

    /**
     * +0xde8 std::map<btCollisionObject*, int>: ordered by address in C++, but only find/insert are
     * used (never iterated), so identity lookup is equivalent (PTR-ORDER note in pointer-order.md).
     */
    public final IdentityHashMap<btCollisionObject, Integer> m_floorKey = new IdentityHashMap<>();

    public final btBoxShape[] m_wallNShapes = new btBoxShape[8];
    public final btBoxShape[] m_wallWShapes = new btBoxShape[8];
    public final btBoxShape[] m_wallSShapes = new btBoxShape[8];
    public final btBoxShape[] m_wallEShapes = new btBoxShape[8];

    /** +0xe10: shared by createGroundBody and createMapGroundBody (first creator's size wins). */
    public btBoxShape m_groundShape;

    /** +0xe18 */
    public btBoxShape m_solidShape;

    /** +0xe20 */
    public btBoxShape m_treeShape;

    /** +0xf58 */
    public btCompoundShape m_stairsShape;

    /** +0xf28 */
    public btBoxShape m_serverCellShape;

    /** +0xf30 */
    public btBoxShape m_physicsObjectShape;

    /** +0xf38 */
    public btBoxShape m_thrownObjectShape;

    /** std::vector<btCollisionShape*> physics meshes (definePhysicsMesh). */
    public final ArrayList<btCollisionShape> m_meshes = new ArrayList<>();

    /** +0xf60 std::vector<ServerCell*> */
    public final ArrayList<ServerCell> m_serverCells = new ArrayList<>();

    /** +0xf78.. stairs dimensions */
    public int m_stairsCount1;

    public int m_stairsCount2;
    public double m_stairs1X;
    public double m_stairs1Y;
    public double m_stairs1Z;
    public double m_stairs2X;
    public double m_stairs2Y;
    public double m_stairs2Z;

    public PZVehicleRaycaster m_vehicleRaycaster;

    /** Emulated heap addresses of objects this class btAlignedAlloc'ed (for btAlignedFree). */
    private final IdentityHashMap<Object, Long> m_addr = new IdentityHashMap<>();

    public static class Constraint {
        public PZVehicle vehA;
        public PZVehicle vehB;
        public PZRagdoll ragdollA;
        public PZRagdoll ragdollB;
        public btTypedConstraint c;

        public Constraint(
                PZVehicle vehA,
                PZVehicle vehB,
                PZRagdoll ragdollA,
                PZRagdoll ragdollB,
                btTypedConstraint c) {
            this.vehA = vehA;
            this.vehB = vehB;
            this.ragdollA = ragdollA;
            this.ragdollB = ragdollB;
            this.c = c;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // file statics

    /** tickCallback @00172c20: installed as the world's internal PRE-tick callback. */
    public static final btDynamicsWorld.btInternalTickCallback tickCallback =
            WorldSimulation::tickCallback;

    /** collisionCallback @00172b60: gContactAddedCallback. */
    public static final ContactAddedCallback collisionCallback = WorldSimulation::collisionCallback;

    public static void tickCallback(btDynamicsWorld world, double timeStep) {
        for (PZVehicle v : instance.m_vehicles.values()) {
            btRigidBody b = v.m_carChassis;
            btVector3 lv = b.m_linearVelocity;
            btVector3 av = b.m_angularVelocity;
            double x = lv.x;
            double y = lv.y;
            double z = lv.z;
            double w = lv.w;
            double ax = av.x;
            double ay = av.y;
            double az = av.z;
            double aw = av.w;
            btVector3 L = v.m_lastLinearVelocity;
            btVector3 A = v.m_lastAngularVelocity;
            if (!v.m_isStatic) {
                double len = Math.sqrt(x * x + y * y + z * z);
                if (len > 0.10000000149011612) {
                    double lenLast = Math.sqrt(L.y * L.y + L.x * L.x + L.z * L.z);
                    if (len - lenLast > 3.5) {
                        double dy = y - L.y;
                        double dx = x - L.x;
                        double dz = z - L.z;
                        double s = 3.5 / Math.sqrt(dx * dx + dy * dy + dz * dz);
                        z = dz * s + L.z;
                        y = dy * s + L.y;
                        x = dx * s + L.x;
                        b.m_updateRevision++;
                        lv.z = z;
                        lv.y = y;
                        lv.x = x;
                        lv.w = 0;
                        w = 0;
                    }
                }
                L.x = x;
                L.y = y;
                L.z = z;
                L.w = w;
                if (!v.m_isStatic) {
                    double alen = Math.sqrt(ax * ax + ay * ay + az * az);
                    if (alen > 0.10000000149011612) {
                        double alenLast = Math.sqrt(A.y * A.y + A.x * A.x + A.z * A.z);
                        if (alen - alenLast > 3.0) {
                            double dy = ay - A.y;
                            double dx = ax - A.x;
                            double dz = az - A.z;
                            double s = 3.0 / Math.sqrt(dx * dx + dy * dy + dz * dz);
                            az = dz * s + A.z;
                            ay = dy * s + A.y;
                            ax = dx * s + A.x;
                            b.m_updateRevision++;
                            av.w = 0;
                            av.y = ay;
                            av.x = ax;
                            av.z = az;
                            aw = 0;
                        }
                    }
                }
            } else {
                L.x = x;
                L.y = y;
                L.z = z;
                L.w = w;
            }
            A.x = ax;
            A.y = ay;
            A.z = az;
            A.w = aw;
            double speed = Math.sqrt(x * x + y * y + z * z);
            if (speed > v.m_maxSpeed) {
                double m = v.m_velocityMultiplier;
                b.m_updateRevision++;
                lv.w = w;
                lv.x = x * m;
                lv.y = y * m;
                lv.z = m * z;
            }
        }
    }

    /**
     * Returns true only on the vehicle (type 1) path (asm: al = both-non-null); Bullet 2.82's
     * btManifoldResult ignores the result. partId/index args are never read.
     */
    public static boolean collisionCallback(
            btManifoldPoint cp,
            btCollisionObjectWrapper colObj0Wrap,
            int partId0,
            int index0,
            btCollisionObjectWrapper colObj1Wrap,
            int partId1,
            int index1) {
        btCollisionObject o0 = colObj0Wrap.m_collisionObject;
        Object p0 = o0.m_userObjectPointer;
        Object p1 = colObj1Wrap.m_collisionObject.m_userObjectPointer;
        if (p0 == null || p1 == null) {
            return false;
        }
        BulletObject u0 = (BulletObject) p0;
        BulletObject u1 = (BulletObject) p1;
        if (u0.type == 1) {
            PZVehicle target;
            if (u1.type == 1) {
                u0.vehicle.m_collided = true;
                target = u1.vehicle;
            } else {
                u1.collidedVehicleId = u0.id;
                target = u0.vehicle;
            }
            double f = o0.m_friction * 0.8999999761581421;
            target.m_collided = true;
            double r;
            if (-10.0 > f) {
                r = -10.0;
            } else {
                // minsd(10, f): NaN f passes through
                r = 10.0 < f ? 10.0 : f;
            }
            cp.m_combinedFriction = r;
            cp.m_combinedRestitution = o0.m_restitution * 0.1;
            return true;
        }
        return false;
    }

    // ---------------------------------------------------------------------------------------------
    // ctor / dtor

    public WorldSimulation(
            int minCellX,
            int minCellY,
            int maxCellX,
            int maxCellY,
            int offsetX,
            int offsetY,
            boolean isServer) {
        m_isServer = isServer;
        m_minCellX = minCellX;
        m_minCellY = minCellY;
        m_maxCellX = maxCellX;
        m_maxCellY = maxCellY;
        m_offsetX = offsetX;
        m_offsetY = offsetY;
        m_debugDrawer = new PZGLDebugDrawer();
        for (int i = 0; i < 8; i++) {
            m_wallNPool[i] = new ArrayDeque<>();
            m_wallWPool[i] = new ArrayDeque<>();
            m_wallSPool[i] = new ArrayDeque<>();
            m_wallEPool[i] = new ArrayDeque<>();
        }
        m_stairsCount1 = 12;
        m_stairsCount2 = 12;
        m_stairs1X = 1.0;
        m_stairs1Y = (double) 0.05f;
        m_stairs1Z = (double) 0.2f;
        m_stairs2X = 1.0;
        m_stairs2Y = (double) 0.2f;
        m_stairs2Z = (double) 0.05f;

        btDefaultCollisionConfiguration.btDefaultCollisionConstructionInfo info =
                new btDefaultCollisionConfiguration.btDefaultCollisionConstructionInfo();
        m_collisionConfiguration = new btDefaultCollisionConfiguration(info);
        m_dispatcher = new btCollisionDispatcher(m_collisionConfiguration);
        m_broadphase = new btDbvtBroadphase(null);
        long solverAddr = btGlobals.btAlignedAlloc(0x138, 16);
        m_solver = new btSequentialImpulseConstraintSolver();
        m_addr.put(m_solver, solverAddr);
        long worldAddr = btGlobals.btAlignedAlloc(0x240, 16);
        PZDiscreteDynamicsWorld world =
                new PZDiscreteDynamicsWorld(
                        m_dispatcher, m_broadphase, m_solver, m_collisionConfiguration);
        m_addr.put(world, worldAddr);
        m_dynamicsWorld = world;
        btGlobals.gDeactivationTime = 3.0;
        world.setGravity(new btVector3(0.0, -10.0, 0.0));
        btGlobals.gDynamicsWorld = world;
        world.m_internalPreTickCallback = tickCallback;
        world.m_dispatchInfo.m_allowedCcdPenetration = 0.0;
        world.m_worldUserInfo = this;
        m_vehicleRaycaster = new PZVehicleRaycaster(world);
        if (glBuild) {
            m_debugDrawer.setDebugMode(0x801);
            world.setDebugDrawer(m_debugDrawer);
        }
        btGlobals.gContactAddedCallback = collisionCallback;
    }

    /**
     * ~WorldSimulation @00175220. Vehicles leave the world and are deleted, chunk maps are deleted,
     * then every remaining collision object (last to first) goes through
     * removeRigidBody(btRigidBody::upcast(o)) and, if it is a rigid body, its motion state and the
     * body are deleted. A non-rigid object still in the world (stairs of a server cell: server
     * cells are never destroyed here) upcasts to null and the native dereferences null in
     * removeCollisionObject; Java throws NullPointerException there (gap). Then the cached shapes,
     * meshes, world, raycaster, solver, broadphase, dispatcher and configuration are deleted,
     * PZVehicleScript::m_scripts is cleared and gDynamicsWorld / instance are nulled. Only the
     * btAlignedFree calls are modelled (btGlobals keeps no free list, so only the count matters).
     */
    public void destroy() {
        for (PZVehicle v : m_vehicles.values()) {
            v.removeFromWorld();
        }
        for (int i = 0; i < 4; i++) {
            if (m_chunkMaps[i] != null) {
                m_chunkMaps[i].destroy();
            }
        }
        btDiscreteDynamicsWorld world = m_dynamicsWorld;
        for (int i = world.m_collisionObjects.size() - 1; i >= 0; i--) {
            btCollisionObject o = world.m_collisionObjects.get(i);
            if ((o.m_internalType & 2) == 0) {
                world.removeRigidBody(null);
            } else {
                btRigidBody body = (btRigidBody) o;
                if (body.m_optionalMotionState != null) {
                    freeTracked(body.m_optionalMotionState);
                }
                world.removeRigidBody(body);
                freeTracked(body);
            }
        }
        for (int i = 0; i < 8; i++) {
            freeTracked(m_wallNShapes[i]);
            freeTracked(m_wallWShapes[i]);
            freeTracked(m_wallSShapes[i]);
            freeTracked(m_wallEShapes[i]);
        }
        for (btBoxShape s : m_floorShapes.values()) {
            freeTracked(s);
        }
        freeTracked(m_groundShape);
        freeTracked(m_solidShape);
        freeTracked(m_treeShape);
        freeTracked(m_stairsShape);
        freeTracked(m_serverCellShape);
        freeTracked(m_physicsObjectShape);
        freeTracked(m_thrownObjectShape);
        for (btCollisionShape s : m_meshes) {
            freeTracked(s);
        }
        m_meshes.clear();
        freeTracked(world);
        freeTracked(m_solver);
        PZVehicleScript.m_scripts.clear();
        btGlobals.gDynamicsWorld = null;
        instance = null;
    }

    /** Deleting-dtor bookkeeping for an object of an aligned class; null is a no-op. */
    private void freeTracked(Object o) {
        if (o == null) {
            return;
        }
        // member destructors run before the deleting dtor's btAlignedFree(this)
        if (o instanceof btCompoundShape c) {
            c.destroy();
            c.m_children.clear();
        } else if (o instanceof btConvexHullShape h) {
            h.m_unscaledPoints.clear();
        } else if (o instanceof btRigidBody b) {
            b.m_constraintRefs.clear();
        }
        Long a = m_addr.remove(o);
        btGlobals.btAlignedFree(a == null ? 1L : a);
    }

    // ---------------------------------------------------------------------------------------------
    // chunk maps

    public void activateChunkMap(int pi, int wx, int wy, int width) {
        ChunkMap m = new ChunkMap(pi, wx, wy, width);
        m_chunkMaps[pi] = m;
        for (int k = 0; k < 4; k++) {
            ChunkMap o;
            if (pi == k || (o = m_chunkMaps[k]) == null) {
                continue;
            }
            int ow = o.width;
            if (ow <= 0) {
                continue;
            }
            // Native bug kept: loops over the OTHER map's width around the NEW map's origin.
            int yy = wy;
            while (true) {
                int n = 0;
                int xx = wx;
                do {
                    if (o.wx <= xx && xx < o.wx + ow && o.wy <= yy && yy < o.wy + ow) {
                        Chunk c = o.chunks[xx - o.wx][yy - o.wy];
                        if (c != null) {
                            m.adoptChunk(c);
                            ow = o.width;
                        }
                    }
                    n++;
                    xx++;
                } while (n < ow);
                if (!((1 - wy) + yy < ow)) {
                    break;
                }
                yy++;
                if (!(0 < ow)) {
                    break;
                }
            }
        }
        if (0 < width) {
            int y = 0;
            int cy = wy;
            boolean more;
            do {
                int x = 0;
                int cx = wx;
                while (true) {
                    if (cx < m.wx
                            || m.wx + m.width <= cx
                            || cy < m.wy
                            || m.width + m.wy <= cy
                            || m.chunks[cx - m.wx][cy - m.wy] == null) {
                        m.createChunk(x, y);
                    }
                    if (width == x + 1) {
                        break;
                    }
                    x++;
                    cx++;
                }
                cy++;
                more = x != y;
                y++;
            } while (more);
        }
    }

    public void deactivateChunkMap(int pi) {
        if (m_chunkMaps[pi] != null) {
            m_chunkMaps[pi].destroy();
        }
        m_chunkMaps[pi] = null;
    }

    public void scrollGroundLeft(int i) {
        m_chunkMaps[i].scrollLeft();
    }

    public void scrollGroundRight(int i) {
        m_chunkMaps[i].scrollRight();
    }

    public void scrollGroundUp(int i) {
        m_chunkMaps[i].scrollUp();
    }

    public void scrollGroundDown(int i) {
        m_chunkMaps[i].scrollDown();
    }

    public Chunk getChunk(int pi, int x, int y) {
        if (!m_isServer) {
            ChunkMap m = m_chunkMaps[pi];
            if (m != null && m.wx <= x && x < m.wx + m.width && m.wy <= y && y < m.width + m.wy) {
                return m.chunks[x - m.wx][y - m.wy];
            }
            return null;
        }
        for (int i = 0; i < m_serverCells.size(); i++) {
            Chunk c = m_serverCells.get(i).getChunk(x, y);
            if (c != null) {
                return c;
            }
        }
        return null;
    }

    public Chunk getChunkForAnyPlayer(int x, int y) {
        if (!m_isServer) {
            for (int i = 0; i < 4; i++) {
                ChunkMap m = m_chunkMaps[i];
                if (m != null
                        && m.wx <= x
                        && x < m.width + m.wx
                        && m.wy <= y
                        && y < m.width + m.wy) {
                    return m.chunks[x - m.wx][y - m.wy];
                }
            }
            return null;
        }
        for (int i = 0; i < m_serverCells.size(); i++) {
            Chunk c = m_serverCells.get(i).getChunk(x, y);
            if (c != null) {
                return c;
            }
        }
        return null;
    }

    public boolean isValidChunk(int wx, int wy) {
        return m_minCellX << 5 <= wx
                && m_minCellY << 5 <= wy
                && wx < (m_maxCellX + 1) * 32
                && wy < (m_maxCellY + 1) * 32;
    }

    public boolean isValidChunkX(int wx) {
        return m_minCellX << 5 <= wx && wx < (m_maxCellX + 1) * 32;
    }

    public boolean isValidChunkY(int wy) {
        return m_minCellY << 5 <= wy && wy < (m_maxCellY + 1) * 32;
    }

    public void setChunkMinMaxLevel(int wx, int wy, int minLevel, int maxLevel) {
        for (int pi = 0; pi < 4; pi++) {
            Chunk c = getChunk(pi, wx, wy);
            if (c != null) {
                c.setMinMaxLevel(minLevel, maxLevel);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // server cells

    public int findServerCell(int cx, int cy) {
        for (int i = 0; i < m_serverCells.size(); i++) {
            ServerCell c = m_serverCells.get(i);
            if (c.cellX == cx && c.cellY == cy) {
                return i;
            }
        }
        return -1;
    }

    public void createServerCell(int cx, int cy) {
        if (findServerCell(cx, cy) != -1) {
            return;
        }
        if (m_serverCellShape == null) {
            long a = btGlobals.btAlignedAlloc(0x70, 16);
            m_serverCellShape = new btBoxShape(new btVector3(30.0, 1.0, 30.0));
            m_addr.put(m_serverCellShape, a);
        }
        btRigidBody.btRigidBodyConstructionInfo ci =
                new btRigidBody.btRigidBodyConstructionInfo(
                        0.0, null, m_serverCellShape, new btVector3(0.0, 0.0, 0.0));
        ci.m_startWorldTransform.m_origin.x = (double) ((cx * 40 + 20) - m_offsetX);
        ci.m_startWorldTransform.m_origin.y = -1.0;
        ci.m_startWorldTransform.m_origin.z = (double) ((cy * 40 + 20) - m_offsetY);
        ci.m_startWorldTransform.m_origin.w = 0.0;
        long a = btGlobals.btAlignedAlloc(btRigidBody.SIZEOF, 16);
        btRigidBody body = new btRigidBody(ci);
        m_addr.put(body, a);
        ServerCell cell = new ServerCell(cx, cy);
        cell.groundBody = body;
        m_serverCells.add(cell);
        m_dynamicsWorld.addRigidBody(body, (short) 1, (short) 0xe);
    }

    public void removeServerCell(int cx, int cy) {
        int i = findServerCell(cx, cy);
        if (i != -1) {
            m_serverCells.get(i).destroy();
            m_serverCells.remove(i);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // body creation helpers

    private btRigidBody newStaticBody(btCollisionShape shape, double ox, double oy, double oz) {
        btRigidBody.btRigidBodyConstructionInfo ci =
                new btRigidBody.btRigidBodyConstructionInfo(
                        0.0, null, shape, new btVector3(0.0, 0.0, 0.0));
        ci.m_startWorldTransform.m_origin.x = ox;
        ci.m_startWorldTransform.m_origin.y = oy;
        ci.m_startWorldTransform.m_origin.z = oz;
        ci.m_startWorldTransform.m_origin.w = 0.0;
        long a = btGlobals.btAlignedAlloc(btRigidBody.SIZEOF, 16);
        btRigidBody body = new btRigidBody(ci);
        m_addr.put(body, a);
        return body;
    }

    /** Pooled body reuse: setWorldTransform(identity basis, origin). */
    private static btRigidBody reuse(btRigidBody b, double ox, double oy, double oz) {
        b.m_updateRevision++;
        b.m_worldTransform.m_basis.setIdentity();
        b.m_worldTransform.m_origin.x = ox;
        b.m_worldTransform.m_origin.y = oy;
        b.m_worldTransform.m_origin.z = oz;
        b.m_worldTransform.m_origin.w = 0.0;
        return b;
    }

    private btBoxShape newBox(double x, double y, double z) {
        long a = btGlobals.btAlignedAlloc(0x70, 16);
        btBoxShape s = new btBoxShape(new btVector3(x, y, z));
        m_addr.put(s, a);
        return s;
    }

    private void deleteBody(btRigidBody b) {
        freeTracked(b);
    }

    public btRigidBody createGroundBody(int cx, int cy) {
        if (m_groundShape == null) {
            m_groundShape = newBox(5.0, 1.0, 5.0);
        }
        return newStaticBody(
                m_groundShape,
                (double) ((cx * 8 + 4) - m_offsetX),
                -1.0,
                (double) ((cy * 8 + 4) - m_offsetY));
    }

    public btRigidBody createMapGroundBody(int cx, int cy, int n) {
        if (m_groundShape == null) {
            m_groundShape = newBox((double) (n * 8), 1.0, (double) (n * 8));
        }
        int h = (n * 8) >> 1;
        return newStaticBody(
                m_groundShape,
                (double) ((h + cx * 8) - m_offsetX),
                -1.0,
                (double) ((h + cy * 8) - m_offsetY));
    }

    public btRigidBody createFloorBody(int x, int y, int level, int w, int h) {
        double ox = (double) (x - m_offsetX) + (double) w * 0.5;
        double oy = (double) (level * 3) * LEVEL_SCALE - 0.05000000074505806;
        double oz = (double) (y - m_offsetY) + (double) h * 0.5;
        int key = h << 16 | w;
        FloorPool pool = m_floorPools.computeIfAbsent(key, k -> new FloorPool());
        if (!pool.m_pool.isEmpty()) {
            return reuse(pool.m_pool.pollLast(), ox, oy, oz);
        }
        btRigidBody body = newStaticBody(getFloorShape(w, h), ox, oy, oz);
        m_floorKey.put(body, key);
        return body;
    }

    public btRigidBody createMeshBody(int x, int y, int level, int idx) {
        btCollisionShape shape = getMeshShape(idx);
        return newStaticBody(
                shape,
                (double) (((float) x - (float) m_offsetX) + 0.5f),
                (double) (level * 3) * LEVEL_SCALE,
                (double) (((float) y - (float) m_offsetY) + 0.5f));
    }

    public btRigidBody createSolid(int x, int y, int level) {
        if (m_solidShape == null) {
            m_solidShape = newBox(0.5, 1.0, 0.5);
        }
        double ox = (double) (x - m_offsetX) + 0.5;
        double oy = (double) ((float) level * 3.0f) * LEVEL_SCALE + 1.0;
        double oz = (double) (y - m_offsetY) + 0.5;
        if (!m_solidPool.isEmpty()) {
            return reuse(m_solidPool.pollLast(), ox, oy, oz);
        }
        return newStaticBody(m_solidShape, ox, oy, oz);
    }

    public btRigidBody createTreeBody(int x, int y, int level) {
        if (m_treeShape == null) {
            m_treeShape = newBox((double) 0.1f, 1.0, (double) 0.1f);
        }
        double ox = (double) ((float) (x - m_offsetX) + 0.5f) + 0.10000000149011612;
        double oy = (double) ((float) level * 3.0f) * LEVEL_SCALE + 1.0;
        double oz = (double) ((float) (y - m_offsetY) + 0.5f) + 0.10000000149011612;
        if (!m_treePool.isEmpty()) {
            return reuse(m_treePool.pollLast(), ox, oy, oz);
        }
        return newStaticBody(m_treeShape, ox, oy, oz);
    }

    /**
     * side: 0 N, 1 W, 2 S, 3 E. {@code len} outside 1..8 indexes the pool arrays out of bounds in
     * the native (UB); Java throws ArrayIndexOutOfBoundsException.
     */
    public btRigidBody createWallBody(int x, int y, int level, int len, int side) {
        int ix = x - m_offsetX;
        int iy = y - m_offsetY;
        double oy = (double) ((float) level * 3.0f) * LEVEL_SCALE + 1.2247450053691864;
        double ox;
        double oz;
        ArrayDeque<btRigidBody> pool;
        btCollisionShape shape;
        switch (side) {
            case 0:
                ox = (double) len * 0.5 + (double) ix;
                oz = (double) iy + 0.05000000074505806;
                pool = m_wallNPool[len - 1];
                if (!pool.isEmpty()) {
                    return reuse(pool.pollLast(), ox, oy, oz);
                }
                shape = getWallNShape(len);
                break;
            case 2:
                ox = (double) ix + (double) len * 0.5;
                oz = (double) (iy + 1) - 0.10000000149011612 * 0.5;
                pool = m_wallSPool[len - 1];
                if (!pool.isEmpty()) {
                    return reuse(pool.pollLast(), ox, oy, oz);
                }
                shape = getWallSShape(len);
                break;
            case 1:
                oz = (double) iy + (double) len * 0.5;
                ox = (double) ix + 0.05000000074505806;
                pool = m_wallWPool[len - 1];
                if (!pool.isEmpty()) {
                    return reuse(pool.pollLast(), ox, oy, oz);
                }
                shape = getWallWShape(len);
                break;
            case 3:
                oz = (double) iy + (double) len * 0.5;
                ox = (double) (ix + 1) - 0.05000000074505806;
                pool = m_wallEPool[len - 1];
                if (!pool.isEmpty()) {
                    return reuse(pool.pollLast(), ox, oy, oz);
                }
                shape = getWallEShape(len);
                break;
            default:
                ox = 0.0;
                oy = 0.0;
                oz = 0.0;
                pool = m_wallEPool[len - 1];
                if (!pool.isEmpty()) {
                    return reuse(pool.pollLast(), ox, oy, oz);
                }
                shape = getWallEShape(len);
                break;
        }
        return newStaticBody(shape, ox, oy, oz);
    }

    // ---------------------------------------------------------------------------------------------
    // shapes

    public btBoxShape getFloorShape(int w, int h) {
        if (Integer.compareUnsigned(w - 1, 7) > 0) {
            return null;
        }
        if (Integer.compareUnsigned(h - 1, 7) > 0) {
            return null;
        }
        int key = h << 16 | w;
        btBoxShape s = m_floorShapes.get(key);
        if (s == null) {
            s = newBox(((double) w + 0.02) * 0.5, (double) 0.05f, ((double) h + 0.02) * 0.5);
            m_floorShapes.put(key, s);
        }
        return s;
    }

    public btBoxShape getWallNShape(int len) {
        int i = len - 1;
        if (Integer.compareUnsigned(i, 7) > 0) {
            return null;
        }
        if (m_wallNShapes[i] != null) {
            return m_wallNShapes[i];
        }
        m_wallNShapes[i] = newBox((double) len * 0.5, 1.2247450053691864, 0.05000000074505806);
        return m_wallNShapes[i];
    }

    public btBoxShape getWallSShape(int len) {
        int i = len - 1;
        if (Integer.compareUnsigned(i, 7) > 0) {
            return null;
        }
        if (m_wallSShapes[i] != null) {
            return m_wallSShapes[i];
        }
        m_wallSShapes[i] = newBox((double) len * 0.5, 1.2247450053691864, 0.05000000074505806);
        return m_wallSShapes[i];
    }

    public btBoxShape getWallWShape(int len) {
        int i = len - 1;
        if (Integer.compareUnsigned(i, 7) > 0) {
            return null;
        }
        if (m_wallWShapes[i] != null) {
            return m_wallWShapes[i];
        }
        m_wallWShapes[i] =
                newBox(
                        (double) 0.05f,
                        Double.longBitsToDouble(0x3ff3988e38000000L),
                        (double) len * 0.5);
        return m_wallWShapes[i];
    }

    public btBoxShape getWallEShape(int len) {
        int i = len - 1;
        if (Integer.compareUnsigned(i, 7) > 0) {
            return null;
        }
        if (m_wallEShapes[i] != null) {
            return m_wallEShapes[i];
        }
        m_wallEShapes[i] =
                newBox(
                        (double) 0.05f,
                        Double.longBitsToDouble(0x3ff3988e38000000L),
                        (double) len * 0.5);
        return m_wallEShapes[i];
    }

    public btCollisionShape getMeshShape(int idx) {
        return m_meshes.get(idx);
    }

    public btCompoundShape getStairsShape() {
        if (m_stairsShape != null) {
            return m_stairsShape;
        }
        double d = 2.449490010738373 / (double) m_stairsCount1;
        long a = btGlobals.btAlignedAlloc(0xb0, 16);
        btCompoundShape compound = new btCompoundShape(true);
        m_addr.put(compound, a);
        btBoxShape box1 = newBox(m_stairs1X * 0.5, m_stairs1Y * 0.5, m_stairs1Z * 0.5);
        btBoxShape box2 = newBox(m_stairs2X * 0.5, m_stairs2Y * 0.5, m_stairs2Z * 0.5);
        double y = d * 0.5;
        double z = 1.75;
        for (int i = 0; i < m_stairsCount1; i++) {
            btTransform t = new btTransform();
            t.setIdentity();
            t.m_origin.x = 0.0;
            t.m_origin.y = y;
            t.m_origin.z = z;
            t.m_origin.w = 0.0;
            compound.addChildShape(t, box1);
            y += d;
            z -= 0.25;
        }
        y = 0.0;
        z = 1.875;
        for (int i = 0; i < m_stairsCount2; i++) {
            btTransform t = new btTransform();
            t.setIdentity();
            t.m_origin.x = 0.0;
            t.m_origin.y = y;
            t.m_origin.z = z;
            t.m_origin.w = 0.0;
            compound.addChildShape(t, box2);
            y += d;
            z -= 0.25;
        }
        m_stairsShape = compound;
        return compound;
    }

    public btCollisionObject createStairsCollisionObject() {
        if (m_stairsPool.isEmpty()) {
            btCompoundShape shape = getStairsShape();
            long a = btGlobals.btAlignedAlloc(0x1f8, 16);
            btCollisionObject co = new btCollisionObject();
            m_addr.put(co, a);
            co.setCollisionShape(shape);
            co.activate(true);
            return co;
        }
        return m_stairsPool.pollLast();
    }

    // ---------------------------------------------------------------------------------------------
    // add / remove static geometry

    private btRigidBody addStatic(
            btRigidBody b, int x, int y, int level, int type, int group, int mask) {
        BulletObject o = new BulletObject(b, (double) x, (double) y, (double) level, type);
        b.m_userObjectPointer = o;
        m_dynamicsWorld.addRigidBody(b, (short) group, (short) mask);
        return b;
    }

    public btRigidBody addFloor(int x, int y, int level, int w, int h) {
        return addStatic(createFloorBody(x, y, level, w, h), x, y, level, 8, 1, 0xe);
    }

    public btRigidBody addMesh(int x, int y, int level, int idx) {
        return addStatic(createMeshBody(x, y, level, idx), x, y, level, 10, 1, 0xe);
    }

    public btRigidBody addSolid(int x, int y, int level) {
        return addStatic(createSolid(x, y, level), x, y, level, 7, 1, 0xe);
    }

    public btRigidBody addSolidStairs(int x, int y, int level) {
        return addStatic(createSolid(x, y, level), x, y, level, 7, 1, 6);
    }

    public btRigidBody addTree(int x, int y, int level) {
        return addStatic(createTreeBody(x, y, level), x, y, level, 9, 1, 0xe);
    }

    public btRigidBody addWallN(int x, int y, int level, int len) {
        return addStatic(createWallBody(x, y, level, len, 0), x, y, level, 3, 1, 0xe);
    }

    public btRigidBody addWallW(int x, int y, int level, int len) {
        return addStatic(createWallBody(x, y, level, len, 1), x, y, level, 4, 1, 0xe);
    }

    public btRigidBody addWallS(int x, int y, int level, int len) {
        return addStatic(createWallBody(x, y, level, len, 2), x, y, level, 5, 1, 0xe);
    }

    public btRigidBody addWallE(int x, int y, int level, int len) {
        return addStatic(createWallBody(x, y, level, len, 3), x, y, level, 6, 1, 0xe);
    }

    public btRigidBody addGroundBody(int cx, int cy) {
        btRigidBody b = createGroundBody(cx, cy);
        m_dynamicsWorld.addRigidBody(b, (short) 1, (short) 0xe);
        return b;
    }

    public btRigidBody addMapGroundBody(int cx, int cy, int n) {
        btRigidBody b = createMapGroundBody(cx, cy, n);
        m_dynamicsWorld.addRigidBody(b, (short) 1, (short) 0xe);
        return b;
    }

    /** No BulletObject is attached to stairs. */
    public btCollisionObject addStairs(int x, int level, int y, int t) {
        double dz = m_stairs1Z;
        double dy = m_stairs1Y;
        double dx = m_stairs1X;
        btCollisionObject co = createStairsCollisionObject();
        double e00;
        double e02;
        double e20;
        if (t == 9) {
            dz = m_stairs1X;
            dy = m_stairs1Y;
            e02 = 1.0;
            e20 = -1.0;
            e00 = Double.longBitsToDouble(0x3cb0000000000000L);
            dx = m_stairs1Z;
        } else {
            e02 = 0.0;
            e00 = 1.0;
            e20 = 0.0;
        }
        btTransform wt = co.m_worldTransform;
        wt.m_basis.m_el2.x = e20;
        wt.m_basis.m_el0.x = e00;
        wt.m_basis.m_el0.z = e02;
        co.m_updateRevision++;
        wt.m_basis.m_el0.y = 0.0;
        wt.m_basis.m_el2.z = e00;
        wt.m_basis.m_el0.w = 0.0;
        wt.m_basis.m_el1.z = 0.0;
        wt.m_basis.m_el1.w = 0.0;
        wt.m_basis.m_el2.y = 0.0;
        wt.m_basis.m_el2.w = 0.0;
        wt.m_basis.m_el1.x = 0.0;
        wt.m_basis.m_el1.y = 1.0;
        wt.m_origin.w = 0.0;
        wt.m_origin.x = dx * 0.5 + (double) (x - m_offsetX);
        wt.m_origin.y = (double) ((float) level * 3.0f) * LEVEL_SCALE + dy * 0.5;
        wt.m_origin.z = (double) (y - m_offsetY) + dz * 0.5;
        m_dynamicsWorld.addCollisionObject(co, (short) 1, (short) 0xd);
        return co;
    }

    public void removeFloor(btRigidBody b) {
        if (b == null) {
            return;
        }
        // delete (BulletObject*) userPointer (pointer left dangling)
        m_dynamicsWorld.removeRigidBody(b);
        Integer key = m_floorKey.get(b);
        if (key == null) {
            deleteBody(b);
            return;
        }
        m_floorPools.computeIfAbsent(key, k -> new FloorPool()).m_pool.addLast(b);
    }

    /** Mesh bodies are not pooled: the body is deleted (motion state: none). idx is unused. */
    public void removeMesh(btRigidBody b, int idx) {
        if (b == null) {
            return;
        }
        // delete (BulletObject*) userPointer (pointer left dangling)
        m_dynamicsWorld.removeRigidBody(b);
        deleteBody(b);
    }

    public void removeSolid(btRigidBody b) {
        if (b != null) {
            m_dynamicsWorld.removeRigidBody(b);
            m_solidPool.addLast(b);
        }
    }

    public void removeTree(btRigidBody b) {
        if (b != null) {
            m_dynamicsWorld.removeRigidBody(b);
            m_treePool.addLast(b);
        }
    }

    private void removeWall(btRigidBody b, btBoxShape[] shapes, ArrayDeque<btRigidBody>[] pools) {
        if (b == null) {
            return;
        }
        m_dynamicsWorld.removeRigidBody(b);
        btCollisionShape s = b.m_collisionShape;
        for (int i = 0; i < 8; i++) {
            if (s == shapes[i]) {
                pools[i].addLast(b);
                return;
            }
        }
        // not one of the cached shapes: body is leaked (native returns)
    }

    public void removeWallN(btRigidBody b) {
        removeWall(b, m_wallNShapes, m_wallNPool);
    }

    public void removeWallW(btRigidBody b) {
        removeWall(b, m_wallWShapes, m_wallWPool);
    }

    public void removeWallS(btRigidBody b) {
        removeWall(b, m_wallSShapes, m_wallSPool);
    }

    public void removeWallE(btRigidBody b) {
        removeWall(b, m_wallEShapes, m_wallEPool);
    }

    public void removeStairs(btCollisionObject co) {
        if (co == null) {
            return;
        }
        m_dynamicsWorld.removeCollisionObject(co);
        m_stairsPool.addLast(co);
    }

    // ---------------------------------------------------------------------------------------------
    // physics objects / meshes

    public int addPhysicsObject(float x, float y) {
        if (m_physicsObjectShape == null) {
            m_physicsObjectShape = newBox(0.5, 0.5, 0.5);
        }
        btTransform t = new btTransform();
        t.setIdentity();
        t.m_origin.x = (double) (x - (float) m_offsetX) + 0.5;
        t.m_origin.y = 10.5;
        t.m_origin.z = (double) (y - (float) m_offsetY) + 0.5;
        t.m_origin.w = 0.0;
        long msAddr = btGlobals.btAlignedAlloc(400, 16);
        btDefaultMotionState ms = new btDefaultMotionState();
        m_addr.put(ms, msAddr);
        ms.setWorldTransform(t);
        btVector3 inertia = new btVector3(0.0, 0.0, 0.0);
        m_physicsObjectShape.calculateLocalInertia(50.0, inertia);
        btRigidBody.btRigidBodyConstructionInfo ci =
                new btRigidBody.btRigidBodyConstructionInfo(
                        50.0, ms, m_physicsObjectShape, inertia);
        long a = btGlobals.btAlignedAlloc(btRigidBody.SIZEOF, 16);
        btRigidBody body = new btRigidBody(ci);
        m_addr.put(body, a);
        body.setAngularFactor(0.0);
        m_dynamicsWorld.addRigidBody(body, (short) 4, (short) 0xf);
        int id = ++m_physicsObjectCounter;
        m_physicsObjects.putIfAbsent(id, body);
        return id;
    }

    /**
     * removePhysicsObject @001782a0 (no JNI caller): removeRigidBody, delete the body (its motion
     * state leaks), erase the map entry.
     */
    public void removePhysicsObject(int id) {
        btRigidBody body = m_physicsObjects.get(id);
        if (body == null && !m_physicsObjects.containsKey(id)) {
            return;
        }
        m_dynamicsWorld.removeRigidBody(body);
        if (body != null) {
            deleteBody(body);
        }
        m_physicsObjects.remove(id);
    }

    public int throwPhysicsObject(float x, float y, float z, float vx, float vy, float vz) {
        if (m_thrownObjectShape == null) {
            m_thrownObjectShape = newBox(0.125, 0.125, 0.125);
        }
        btTransform t = new btTransform();
        t.setIdentity();
        t.m_origin.x = (double) (x - (float) m_offsetX) + 0.125;
        t.m_origin.y = (double) (z * 3.0f) * LEVEL_SCALE + 0.125;
        t.m_origin.z = (double) (y - (float) m_offsetY) + 0.125;
        t.m_origin.w = 0.0;
        long msAddr = btGlobals.btAlignedAlloc(400, 16);
        btDefaultMotionState ms = new btDefaultMotionState();
        m_addr.put(ms, msAddr);
        ms.setWorldTransform(t);
        btVector3 inertia = new btVector3(0.0, 0.0, 0.0);
        m_thrownObjectShape.calculateLocalInertia(1.0, inertia);
        btRigidBody.btRigidBodyConstructionInfo ci =
                new btRigidBody.btRigidBodyConstructionInfo(1.0, ms, m_thrownObjectShape, inertia);
        long a = btGlobals.btAlignedAlloc(btRigidBody.SIZEOF, 16);
        btRigidBody body = new btRigidBody(ci);
        m_addr.put(body, a);
        // applyCentralImpulse((vx, vz, vy)) inlined
        btVector3 lv = body.m_linearVelocity;
        btVector3 lf = body.m_linearFactor;
        double im = body.m_inverseMass;
        lv.x = (double) vx * lf.x * im + lv.x;
        lv.y = (double) vz * lf.y * im + lv.y;
        lv.z = (double) vy * lf.z * im + lv.z;
        body.m_ccdSweptSphereRadius = 0.0625;
        body.m_ccdMotionThreshold = (double) 0.05f;
        m_dynamicsWorld.addRigidBody(body, (short) 4, (short) 0xf);
        int id = ++m_physicsObjectCounter;
        m_physicsObjects.putIfAbsent(id, body);
        return id;
    }

    /** Deletes every mesh shape (compound children are not deleted) and empties the vector. */
    public void clearPhysicsMeshes() {
        for (btCollisionShape s : m_meshes) {
            freeTracked(s);
        }
        m_meshes.clear();
    }

    public void definePhysicsMesh(int idx, boolean compound, int n, float[] p) {
        while (m_meshes.size() < idx + 1) {
            m_meshes.add(null);
        }
        long a = btGlobals.btAlignedAlloc(0xd8, 16);
        btConvexHullShape hull = new btConvexHullShape(null, 0, 0x20);
        m_addr.put(hull, a);
        for (int i = 0; i < n; i++) {
            hull.addPoint(
                    new btVector3((double) p[i * 3], (double) p[i * 3 + 1], (double) p[i * 3 + 2]),
                    i == n - 1);
        }
        if (compound) {
            if (m_meshes.get(idx) == null) {
                long ca = btGlobals.btAlignedAlloc(0xb0, 16);
                btCompoundShape c = new btCompoundShape(true);
                m_addr.put(c, ca);
                m_meshes.set(idx, c);
            }
            btTransform t = new btTransform();
            t.setIdentity();
            ((btCompoundShape) m_meshes.get(idx)).addChildShape(t, hull);
        } else {
            m_meshes.set(idx, hull);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // vehicles / ragdolls / ballistics / constraints

    public void addVehicle(int id, btTransform t, PZVehicleScript s) {
        PZVehicle v = new PZVehicle(id, s.wheelCount == 0, s);
        v.addToWorld(t);
    }

    public void removeVehicle(int id) {
        PZVehicle v = m_vehicles.get(id);
        if (v == null) {
            return;
        }
        removeConstraintsOnVehicle(v);
        v.removeFromWorld();
        m_vehicles.remove(id);
    }

    public void setVehicleActive(int id, boolean active) {
        PZVehicle v = m_vehicles.get(id);
        if (v != null) {
            v.setActive(active);
        }
    }

    public void addRagdoll(int id, btTransform t) {
        m_ragdolls.add(id).addToWorld(t);
    }

    public void removeRagdoll(int id) {
        m_ragdolls.remove(id);
    }

    public void setRagdollActive(int id, boolean active) {
        if (m_ragdolls.containsKey(id)) {
            m_ragdolls.get(id).setActive(active);
        }
    }

    public PZBallistics addBallistics(int id) {
        return m_ballistics.add(id);
    }

    public void removeBallistics(int id) {
        if (m_ballistics.containsKey(id)) {
            m_ballistics.remove(id);
        }
    }

    public int addConstraint(Constraint c) {
        m_dynamicsWorld.addConstraint(c.c, false);
        int id = ++m_constraintCounter;
        m_constraints.putIfAbsent(id, c);
        return id;
    }

    public void removeConstraint(int id) {
        Constraint c = m_constraints.remove(id);
        if (c != null) {
            m_dynamicsWorld.removeConstraint(c.c);
            deleteConstraint(c.c);
        }
    }

    /** Virtual deleting dtor of a btTypedConstraint (aligned allocator): one btAlignedFree. */
    private static void deleteConstraint(btTypedConstraint c) {
        if (c != null) {
            btGlobals.btAlignedFree(1L);
        }
    }

    public void setConstraintERP(int id, float erp, int axis) {
        Constraint c = m_constraints.get(id);
        if (c != null) {
            c.c.setParam(2, (double) erp, axis);
        }
    }

    /**
     * The native erases the node and then increments the erased iterator (UB); on glibc this
     * normally continues with the next key, which is what the port does.
     */
    public void removeConstraintsOnVehicle(PZVehicle v) {
        Map.Entry<Integer, Constraint> e = m_constraints.firstEntry();
        while (e != null) {
            int key = e.getKey();
            Constraint c = e.getValue();
            if (c.vehB == v || c.vehA == v) {
                m_constraints.remove(key);
                m_dynamicsWorld.removeConstraint(c.c);
                deleteConstraint(c.c);
            }
            e = m_constraints.higherEntry(key);
        }
    }

    public void removeConstraintsOnRagdoll(PZRagdoll r) {
        Map.Entry<Integer, Constraint> e = m_constraints.firstEntry();
        while (e != null) {
            int key = e.getKey();
            Constraint c = e.getValue();
            if (c.ragdollB == r || c.ragdollA == r) {
                m_constraints.remove(key);
                m_dynamicsWorld.removeConstraint(c.c);
                deleteConstraint(c.c);
            }
            e = m_constraints.higherEntry(key);
        }
    }

    public void checkVehicleConstraints() {
        Integer[] keys = m_constraints.keySet().toArray(new Integer[0]);
        for (Integer key : keys) {
            if (m_constraints.isEmpty()) {
                break;
            }
            Constraint c = m_constraints.get(key);
            if (c != null && c.vehA != null && c.vehB != null && c.c.needsFeedback()) {
                double imp = c.c.getAppliedImpulse();
                if (imp > 500.0) {
                    onVehicleConstraintImpulse(key, c.vehA.m_id, c.vehB.m_id, imp);
                }
            }
        }
    }

    public void onVehicleConstraintImpulse(int id, int vehA, int vehB, double impulse) {
        BulletUpcalls.callOnVehicleConstraintImpulse(id, vehA, vehB, (float) impulse);
    }

    // ---------------------------------------------------------------------------------------------
    // step

    public void stepSimulation(double timeStep, int maxSubSteps, double fixedTimeStep) {
        for (int i = 0; i < 4; i++) {
            ChunkMap map = m_chunkMaps[i];
            if (map == null) {
                continue;
            }
            for (PZVehicle v : instance.m_vehicles.values()) {
                btTransform t = v.m_vehicle.getChassisWorldTransform();
                int lvl = btScalar.cvttsd2si(Math.floor(t.m_origin.y / 2.449490010738373));
                int wy = btScalar.cvttsd2si(Math.floor((double) m_offsetY + t.m_origin.z));
                int wx = btScalar.cvttsd2si(Math.floor((double) m_offsetX + t.m_origin.x));
                map.setNeed(wx, wy, lvl);
            }
            for (PZRagdoll r : instance.m_ragdolls.m_map.values()) {
                btVector3 o = r.m_ragdoll.getRigidBody(0).m_worldTransform.m_origin;
                int lvl = btScalar.cvttsd2si(Math.floor(o.y / 2.449490010738373));
                int wy = btScalar.cvttsd2si(Math.floor((double) m_offsetY + o.z));
                int wx = btScalar.cvttsd2si(Math.floor((double) m_offsetX + o.x));
                map.setNeed(wx, wy, lvl);
            }
            map.load();
        }
        m_dynamicsWorld.stepSimulation(timeStep, maxSubSteps, fixedTimeStep);
        checkVehicleConstraints();
    }
}
