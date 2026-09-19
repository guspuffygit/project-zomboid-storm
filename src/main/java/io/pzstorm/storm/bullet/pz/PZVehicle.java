// Port of PZ PZVehicle (PZVehicle.cpp) from decomp (libPZBulletNoOpenGL64):
//   PZVehicle(int,bool,PZVehicleScript*) @0015bf30, localCreateRigidBody @00159bf0,
//   localCreateRigidBodyBurnt @0015a0a0, reset @0015a530, removeFromWorld @0015a710,
//   control @0015a8b0, setActive @0015ab60, hasChunksAllAround @0015abe0, setStatic @0015adf0,
//   checkWheelCollision @0015b2d0, drawRenderHighlightedTarget @0015b460, debugDraw @0015b8e0,
//   render @0015b8f0, addToWorld @0015be00.
// C++ object layout is noted per field; member names are not in the binary (no debug info).
package io.pzstorm.storm.bullet.pz;

import io.pzstorm.storm.bullet.collision.dispatch.btCollisionWorld;
import io.pzstorm.storm.bullet.collision.shapes.btBoxShape;
import io.pzstorm.storm.bullet.collision.shapes.btCollisionShape;
import io.pzstorm.storm.bullet.collision.shapes.btCompoundShape;
import io.pzstorm.storm.bullet.collision.shapes.btConvexHullShape;
import io.pzstorm.storm.bullet.collision.shapes.btSphereShape;
import io.pzstorm.storm.bullet.dynamics.btDiscreteDynamicsWorld;
import io.pzstorm.storm.bullet.dynamics.btRigidBody;
import io.pzstorm.storm.bullet.dynamics.vehicle.btRaycastVehicle;
import io.pzstorm.storm.bullet.dynamics.vehicle.btWheelInfo;
import io.pzstorm.storm.bullet.libm.GlibcMath;
import io.pzstorm.storm.bullet.linearmath.btDefaultMotionState;
import io.pzstorm.storm.bullet.linearmath.btGlobals;
import io.pzstorm.storm.bullet.linearmath.btMatrix3x3;
import io.pzstorm.storm.bullet.linearmath.btMotionState;
import io.pzstorm.storm.bullet.linearmath.btQuaternion;
import io.pzstorm.storm.bullet.linearmath.btScalar;
import io.pzstorm.storm.bullet.linearmath.btTransform;
import io.pzstorm.storm.bullet.linearmath.btVector3;
import java.util.ArrayList;

public class PZVehicle {

    public static final int MAX_WHEELS = PZVehicleScript.MAX_WHEELS;

    /** +0x00 */
    public int m_id;

    /** +0x04 */
    public boolean m_burnt;

    /** +0x05: set by collisionCallback, reported and cleared by getVehiclePhysics. */
    public boolean m_collided;

    /** +0x08 */
    public btRaycastVehicle m_vehicle;

    /** +0x10 */
    public btRigidBody m_carChassis;

    /** +0x18 (btRaycastVehicle::btVehicleTuning, default-constructed) */
    public final btRaycastVehicle.btVehicleTuning m_tuning = new btRaycastVehicle.btVehicleTuning();

    /** +0x48 std::vector&lt;btCollisionShape*&gt; */
    public final ArrayList<btCollisionShape> m_shapes = new ArrayList<>();

    /** +0x60 */
    public btCompoundShape m_compound;

    /** +0x68 */
    public PZVehicleScript m_script;

    /** +0x70 */
    public double m_maxSpeed;

    /** +0x78 */
    public double m_velocityMultiplier;

    /** +0x80: chassis linear velocity seen by the previous tickCallback. */
    public final btVector3 m_lastLinearVelocity = new btVector3();

    /** +0xa0: chassis angular velocity seen by the previous tickCallback. */
    public final btVector3 m_lastAngularVelocity = new btVector3();

    /** +0xc0 */
    public boolean m_isStatic;

    /** +0xc1 */
    public boolean m_isActive;

    /** +0xc4 + 8*i: float tire inflation. */
    public final float[] m_tireInflation = new float[MAX_WHEELS];

    /** +0xc8 + 8*i */
    public final boolean[] m_tireRemoved = new boolean[MAX_WHEELS];

    /** +0xc9 + 8*i */
    public final boolean[] m_tirePresent = new boolean[MAX_WHEELS];

    /** Emulated btAlignedAlloc addresses (gNumAlignedAllocs / gNumAlignedFree bookkeeping). */
    private long m_compoundAddr;

    private final ArrayList<Long> m_shapeAddrs = new ArrayList<>();
    private long m_motionStateAddr;
    private long m_chassisAddr;

    private static btDiscreteDynamicsWorld world() {
        return (btDiscreteDynamicsWorld) btGlobals.gDynamicsWorld;
    }

    /** PZVehicle::PZVehicle(int id, bool burnt, PZVehicleScript* script) @0015bf30 */
    public PZVehicle(int id, boolean burnt, PZVehicleScript script) {
        m_id = id;
        m_collided = false;
        m_burnt = burnt;
        m_script = script;

        m_compoundAddr = btGlobals.btAlignedAlloc(0xb0, 16);
        m_compound = new btCompoundShape(true);

        for (int i = 0; i < script.shapeCount; i++) {
            PZVehicleScript.Shape s = script.shapes[i];
            btTransform localTransform = new btTransform();
            localTransform.setIdentity();
            localTransform.setOrigin(s.offset);
            btCollisionShape shape = null;
            long addr = 0;
            if (s.type == 1) {
                addr = btGlobals.btAlignedAlloc(0x70, 16);
                shape =
                        new btBoxShape(
                                new btVector3(
                                        s.extents.x * 0.5, s.extents.y * 0.5, s.extents.z * 0.5));
                localTransform
                        .getBasis()
                        .setRotation(
                                new btQuaternion(
                                        (s.rotate.y * Math.PI) / 180.0,
                                        (s.rotate.x * Math.PI) / 180.0,
                                        (s.rotate.z * Math.PI) / 180.0));
            } else if (s.type == 2) {
                addr = btGlobals.btAlignedAlloc(0x68, 16);
                shape = new btSphereShape(s.radius);
            } else if (s.type == 3) {
                addr = btGlobals.btAlignedAlloc(0xd8, 16);
                int n = s.mesh.size();
                double[] pts = new double[n];
                for (int k = 0; k < n; k++) {
                    pts[k] = s.mesh.get(k);
                }
                shape = new btConvexHullShape(pts, n / 3, 24);
            }
            if (shape != null) {
                m_compound.addChildShape(localTransform, shape);
                m_shapes.add(shape);
                m_shapeAddrs.add(addr);
            }
        }

        btRigidBody chassis = localCreateRigidBody(script.mass);
        m_carChassis = chassis;
        chassis.setCollisionFlags(chassis.getCollisionFlags() | 8);
        BulletObject bo = new BulletObject(chassis, 0.0, 0.0, 0.0, BulletObjectType.Vehicle);
        bo.vehicle = this;
        bo.id = id;
        chassis.setUserPointer(bo);

        m_vehicle = null;
        reset();

        m_vehicle =
                new btRaycastVehicle(
                        m_tuning, m_carChassis, WorldSimulation.instance.m_vehicleRaycaster);
        m_vehicle.setCoordinateSystem(0, 1, 2);

        btVector3 wheelDirectionCS0 = new btVector3(0.0, -1.0, 0.0);
        btVector3 wheelAxleCS = new btVector3(-1.0, 0.0, 0.0);
        for (int i = 0; i < script.wheelCount; i++) {
            PZVehicleScript.Wheel w = script.wheels[i];
            btWheelInfo wi =
                    m_vehicle.addWheel(
                            w.offset,
                            wheelDirectionCS0,
                            wheelAxleCS,
                            script.suspensionRestLength,
                            w.radius,
                            m_tuning,
                            w.front);
            wi.m_suspensionStiffness = script.suspensionStiffness;
            wi.m_maxSuspensionTravelCm = script.maxSuspensionTravelCm;
            wi.m_wheelsDampingRelaxation = script.suspensionDamping;
            wi.m_wheelsDampingCompression = script.suspensionCompression;
            wi.m_frictionSlip = script.wheelFriction;
            wi.m_rollInfluence = script.rollInfluence;
            if (!w.front) {
                m_vehicle.setBrake(10000.0, i);
            }
            m_tireInflation[i] = 1.0f;
            m_tireRemoved[i] = false;
            m_tirePresent[i] = true;
        }

        m_maxSpeed = 100000.0;
        m_velocityMultiplier = 1.0;

        int n = m_compound.getNumChildShapes();
        for (int i = 0; i < n; i++) {
            btCollisionShape child = m_compound.getChildShape(i);
            if (child != null) {
                BulletObject cbo =
                        new BulletObject(chassis, 0.0, 0.0, 0.0, BulletObjectType.VehiclePart);
                cbo.vehicle = this;
                cbo.id = id;
                child.setUserPointer(cbo);
            }
        }
    }

    /** btRigidBody* localCreateRigidBody(double mass) @00159bf0 */
    public btRigidBody localCreateRigidBody(double mass) {
        m_motionStateAddr = btGlobals.btAlignedAlloc(400, 16);
        btDefaultMotionState ms = new btDefaultMotionState(btTransform.getIdentity());
        btVector3 localInertia = new btVector3(0.0, 0.0, 0.0);
        m_compound.calculateLocalInertia(mass, localInertia);
        btRigidBody.btRigidBodyConstructionInfo ci =
                new btRigidBody.btRigidBodyConstructionInfo(mass, ms, m_compound, localInertia);
        ci.m_friction = 4.0;
        ci.m_restitution = 0.2;
        m_chassisAddr = btGlobals.btAlignedAlloc(0x4a0, 16);
        return new btRigidBody(ci);
    }

    /** btRigidBody* localCreateRigidBodyBurnt(double mass) @0015a0a0: no local inertia. */
    public btRigidBody localCreateRigidBodyBurnt(double mass) {
        m_motionStateAddr = btGlobals.btAlignedAlloc(400, 16);
        btDefaultMotionState ms = new btDefaultMotionState(btTransform.getIdentity());
        btVector3 localInertia = new btVector3(0.0, 0.0, 0.0);
        btRigidBody.btRigidBodyConstructionInfo ci =
                new btRigidBody.btRigidBodyConstructionInfo(mass, ms, m_compound, localInertia);
        ci.m_friction = 4.0;
        ci.m_restitution = 0.2;
        m_chassisAddr = btGlobals.btAlignedAlloc(0x4a0, 16);
        return new btRigidBody(ci);
    }

    /** void reset() @0015a530 */
    public void reset() {
        m_carChassis.setCenterOfMassTransform(btTransform.getIdentity());
        m_carChassis.setLinearVelocity(new btVector3(0.0, 0.0, 0.0));
        m_carChassis.setAngularVelocity(new btVector3(0.0, 0.0, 0.0));
        btDiscreteDynamicsWorld w = world();
        w.getBroadphase()
                .getOverlappingPairCache()
                .cleanProxyFromPairs(m_carChassis.getBroadphaseHandle(), w.getDispatcher());
        if (m_vehicle != null) {
            m_vehicle.resetSuspension();
            for (int i = 0; i < m_vehicle.getNumWheels(); i++) {
                m_vehicle.updateWheelTransform(i, true);
            }
        }
    }

    /** void addToWorld(const btTransform&) @0015be00 */
    public void addToWorld(btTransform t) {
        PZDebugLog.instance()
                .logf(
                        "PZBullet",
                        PZDebugType.Debug,
                        "/usr/src/pz/pzbullet/PZVehicle.cpp:317",
                        "PZVehicle::addToWorld> Vehicle[id:%d, script:%s]\n",
                        m_id,
                        m_script.name);
        m_carChassis.setWorldTransform(t);
        WorldSimulation ws = WorldSimulation.instance;
        world().addRigidBody(m_carChassis, (short) 2, (short) (ws.m_isServer ? 0xd : 0xf));
        world().addVehicle(m_vehicle);
        ws.m_vehicles.putIfAbsent(m_id, this);
    }

    /** void removeFromWorld() @0015a710 */
    public void removeFromWorld() {
        PZDebugLog.instance()
                .logf(
                        "PZBullet",
                        PZDebugType.Debug,
                        "/usr/src/pz/pzbullet/PZVehicle.cpp:330",
                        "PZVehicle::removeFromWorld> Vehicle[id:%d, script:%s]\n",
                        m_id,
                        m_script.name);
        // delete chassis BulletObject (user pointer): no Java counterpart.
        btDiscreteDynamicsWorld w = world();
        w.removeVehicle(m_vehicle);
        w.removeRigidBody(m_carChassis);
        if (m_carChassis.getMotionState() != null) {
            btGlobals.btAlignedFree(m_motionStateAddr);
            m_motionStateAddr = 0;
        }
        // ~btRigidBody: m_constraintRefs freed, then the object itself.
        m_carChassis.m_constraintRefs.clear();
        btGlobals.btAlignedFree(m_chassisAddr);
        m_chassisAddr = 0;
        if (m_compound != null) {
            m_compound.destroy();
            btGlobals.btAlignedFree(m_compoundAddr);
            m_compoundAddr = 0;
        }
        for (int i = 0; i < m_shapes.size(); i++) {
            btCollisionShape s = m_shapes.get(i);
            if (s != null) {
                if (s instanceof btConvexHullShape) {
                    ((btConvexHullShape) s).m_unscaledPoints.clear();
                }
                btGlobals.btAlignedFree(m_shapeAddrs.get(i));
            }
        }
        if (m_vehicle != null) {
            m_vehicle.destroy();
        }
    }

    /** void control(float engineForce, float brakeForce, float steering) @0015a8b0 */
    public void control(float engineForce, float brakeForce, float steering) {
        if (m_burnt) {
            PZDebugLog.instance()
                    .logf(
                            "PZBullet",
                            PZDebugType.Error,
                            "/usr/src/pz/pzbullet/PZVehicle.cpp:350",
                            "ERROR: PZVehicle::control - vehicle is burnt! Vehicle[id:%d,"
                                    + " script:%s]\n",
                            m_id,
                            m_script.name);
            return;
        }
        double stopForce;
        if (engineForce == 0.0f) {
            stopForce = m_script.stoppingMovementForce;
        } else {
            stopForce = 0.0;
        }
        btRaycastVehicle v = m_vehicle;
        double r = Math.abs(v.m_currentVehicleSpeedKmHour) / m_maxSpeed;
        double speedFactor;
        if (r < 0.0) {
            speedFactor = 0.0;
        } else {
            speedFactor = 1.0;
            if (r <= 1.0) {
                speedFactor = r;
            }
        }
        for (int i = 0; i < m_vehicle.getNumWheels(); i++) {
            double extra = 0.0;
            if (m_tireInflation[i] < 1.0f) {
                extra = (1.0 - (double) m_tireInflation[i]) * 300.0 * speedFactor;
            }
            if (m_script.wheels[i].front) {
                if (!m_tirePresent[i]) {
                    m_vehicle.setSteeringValue((double) steering, i);
                    m_vehicle.applyEngineForce(0.0, i);
                    m_vehicle.setBrake(stopForce + 10.0, i);
                } else {
                    m_vehicle.setSteeringValue((double) steering, i);
                    m_vehicle.applyEngineForce(0.0, i);
                    m_vehicle.setBrake(extra + stopForce, i);
                }
            } else if (!m_tirePresent[i]) {
                m_vehicle.applyEngineForce(0.0, i);
                m_vehicle.setBrake(stopForce + 10.0, i);
            } else {
                m_vehicle.applyEngineForce((double) engineForce, i);
                m_vehicle.setBrake((double) brakeForce + stopForce + extra, i);
            }
        }
    }

    /** void setActive(bool) @0015ab60 */
    public void setActive(boolean active) {
        if (!active) {
            m_isActive = false;
            m_carChassis.forceActivationState(1);
            return;
        }
        if (!m_isStatic) {
            m_isActive = true;
            m_carChassis.forceActivationState(4);
            return;
        }
        PZDebugLog.instance()
                .logf(
                        "PZBullet",
                        PZDebugType.Warning,
                        "/usr/src/pz/pzbullet/PZVehicle.cpp:388",
                        "PZVehicle::setActive> Vehicle's isStatic is TRUE. Cannot set to active."
                                + " Please set isStatic to false first. Vehicle[id:%d,"
                                + " script:%s]\n",
                        m_id,
                        m_script.name);
    }

    /** bool hasChunksAllAround() @0015abe0 */
    public boolean hasChunksAllAround() {
        WorldSimulation ws = WorldSimulation.instance;
        btVector3 o = m_carChassis.getWorldTransform().getOrigin();
        int cx = btScalar.cvttsd2si(Math.floor(((double) ws.m_offsetX + o.x) * 0.125));
        int cy = btScalar.cvttsd2si(Math.floor(((double) ws.m_offsetY + o.z) * 0.125));
        int lvl = btScalar.cvttsd2si(Math.floor(o.y / 2.449490010738373));
        for (int y = cy - 1; y <= cy + 1; y++) {
            for (int x = cx - 1; x <= cx + 1; x++) {
                if (ws.isValidChunk(x, y)) {
                    Chunk c = ws.getChunkForAnyPlayer(x, y);
                    if (c == null) {
                        return false;
                    }
                    if (c.minLevel <= lvl && lvl <= c.maxLevel) {
                        ChunkLevel ld = c.getLevelData(lvl);
                        if (ld == null) {
                            return false;
                        }
                        if (!ld.shapesSet) {
                            return false;
                        }
                        if (!ld.bodiesAdded) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    /** void setStatic(bool) @0015adf0 */
    public void setStatic(boolean isStatic) {
        if (m_isStatic == isStatic) {
            return;
        }
        btDiscreteDynamicsWorld w = world();
        btVector3 inertia = new btVector3(0.0, 0.0, 0.0);
        if (!m_isStatic) {
            w.removeVehicle(m_vehicle);
        }
        w.removeRigidBody(m_carChassis);
        if (isStatic) {
            m_compound.calculateLocalInertia(0.0, inertia);
            m_carChassis.setMassProps(0.0, inertia);
            m_carChassis.setCollisionFlags(m_carChassis.getCollisionFlags() | 2);
            if (!hasChunksAllAround()) {
                m_carChassis.setActivationState(5);
            } else {
                m_carChassis.setActivationState(4);
            }
            btMotionState ms = m_carChassis.getMotionState();
            if (ms != null) {
                ms.setWorldTransform(m_carChassis.getWorldTransform());
            }
        } else {
            m_compound.calculateLocalInertia(m_script.mass, inertia);
            m_carChassis.setMassProps(m_script.mass, inertia);
            m_carChassis.setCollisionFlags(m_carChassis.getCollisionFlags() & 0xfffffffd);
            if (!hasChunksAllAround()) {
                m_carChassis.setActivationState(5);
            } else {
                m_carChassis.forceActivationState(1);
            }
        }
        m_carChassis.setLinearVelocity(new btVector3(0.0, 0.0, 0.0));
        m_carChassis.setAngularVelocity(new btVector3(0.0, 0.0, 0.0));
        m_carChassis.clearForces();
        m_isStatic = isStatic;
        w.addRigidBody(
                m_carChassis, (short) 2, (short) (WorldSimulation.instance.m_isServer ? 0xd : 0xf));
        if (!isStatic) {
            w.addVehicle(m_vehicle);
        }
    }

    /**
     * bool checkWheelCollision(int ragdollId, int bodyPart, float maxDist) @0015b2d0: true if any
     * wheel centre is within maxDist of the ragdoll body part.
     */
    public boolean checkWheelCollision(int ragdollId, int bodyPart, float maxDist) {
        PZRagdoll r = WorldSimulation.instance.m_ragdolls.get(ragdollId);
        if (r == null) {
            return false;
        }
        for (int i = 0; i < m_vehicle.getNumWheels(); i++) {
            btVector3 wp = m_vehicle.m_wheelInfo.get(i).m_worldTransform.getOrigin();
            double wx = wp.x;
            double wy = wp.y;
            double wz = wp.z;
            btVector3 bp = r.m_ragdoll.getRigidBody(bodyPart).getWorldTransform().getOrigin();
            double dy = bp.y - wy;
            double dx = bp.x - wx;
            double dz = bp.z - wz;
            double d = GlibcMath.sqrt(dz * dz + dx * dx + dy * dy);
            if (d <= (double) maxDist) {
                return true;
            }
        }
        return false;
    }

    /** void drawRenderHighlightedTarget(btCollisionWorld*) @0015b460 */
    public void drawRenderHighlightedTarget(btCollisionWorld w) {
        BulletObject bo = (BulletObject) m_carChassis.getUserPointer();
        if (bo == null) {
            return;
        }
        btCollisionShape shape = m_carChassis.getCollisionShape();
        if (shape == null) {
            if (bo.flag40) {
                w.debugDrawObject(m_carChassis.getWorldTransform(), null, bo.color);
                bo.flag40 = false;
            }
            return;
        }
        btCompoundShape compound = (btCompoundShape) shape;
        for (int i = 0; i < compound.getNumChildShapes(); i++) {
            btCollisionShape child = compound.getChildShape(i);
            if (child == null) {
                continue;
            }
            BulletObject cbo = (BulletObject) child.getUserPointer();
            btVector3 color;
            if (cbo == null) {
                color = new btVector3(PZbtVector3.White);
            } else {
                if (!cbo.flag40) {
                    continue;
                }
                color = new btVector3(cbo.color);
                cbo.flag40 = false;
            }
            btTransform t = m_carChassis.getWorldTransform().mul(compound.getChildTransform(i));
            w.debugDrawObject(t, child, color);
        }
    }

    /** void debugDraw(btCollisionWorld*) @0015b8e0 */
    public void debugDraw(btCollisionWorld w) {
        drawRenderHighlightedTarget(w);
    }

    /** void render() @0015b8f0 */
    public void render() {
        debugDraw(world());
    }

    /** Helper used by the JNI physics getters: basis -> quaternion (btMatrix3x3::getRotation). */
    static btQuaternion rotationOf(btTransform t) {
        btQuaternion q = new btQuaternion();
        btMatrix3x3 b = t.getBasis();
        b.getRotation(q);
        return q;
    }
}
