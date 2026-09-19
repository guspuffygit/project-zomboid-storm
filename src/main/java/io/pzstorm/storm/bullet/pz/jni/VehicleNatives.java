// Port of the vehicle/constraint JNI entry points of libPZBulletNoOpenGL64 (PZBullet.cpp),
// Java_zombie_core_physics_Bullet_<name>. Signatures match zombie/core/physics/Bullet.java.
//
// JNI ThrowNew + return becomes a plain Java throw (the pending exception is raised when the
// native returns, so the C++ return value is never observed). GetFloatArrayElements /
// ReleaseFloatArrayElements(mode 0) copy-in/copy-back is replaced by direct array access.
package io.pzstorm.storm.bullet.pz.jni;

import io.pzstorm.storm.bullet.collision.shapes.btCollisionShape;
import io.pzstorm.storm.bullet.collision.shapes.btConvexShape;
import io.pzstorm.storm.bullet.dynamics.btDiscreteDynamicsWorld;
import io.pzstorm.storm.bullet.dynamics.btRigidBody;
import io.pzstorm.storm.bullet.dynamics.constraintsolver.btGeneric6DofConstraint;
import io.pzstorm.storm.bullet.dynamics.constraintsolver.btHingeConstraint;
import io.pzstorm.storm.bullet.dynamics.constraintsolver.btPoint2PointConstraint;
import io.pzstorm.storm.bullet.dynamics.vehicle.btRaycastVehicle;
import io.pzstorm.storm.bullet.dynamics.vehicle.btWheelInfo;
import io.pzstorm.storm.bullet.libm.GlibcMath;
import io.pzstorm.storm.bullet.linearmath.btGlobals;
import io.pzstorm.storm.bullet.linearmath.btQuaternion;
import io.pzstorm.storm.bullet.linearmath.btTransform;
import io.pzstorm.storm.bullet.linearmath.btVector3;
import io.pzstorm.storm.bullet.pz.PZBullet;
import io.pzstorm.storm.bullet.pz.PZDebugLog;
import io.pzstorm.storm.bullet.pz.PZDebugType;
import io.pzstorm.storm.bullet.pz.PZRagdoll;
import io.pzstorm.storm.bullet.pz.PZVehicle;
import io.pzstorm.storm.bullet.pz.PZVehicleClosestConvexResultCallback;
import io.pzstorm.storm.bullet.pz.PZVehicleScript;
import io.pzstorm.storm.bullet.pz.WorldSimulation;

public final class VehicleNatives {

    private VehicleNatives() {}

    private static final String INSTANCE_NULL = "WorldSimulation::instance is null";
    private static final String NOT_FOUND = "Vehicle not found. VehicleId:%d";

    private static btDiscreteDynamicsWorld world() {
        return (btDiscreteDynamicsWorld) btGlobals.gDynamicsWorld;
    }

    private static void warnNotFound(int line, int id) {
        PZDebugLog.instance()
                .logf(
                        "PZBullet",
                        PZDebugType.Warning,
                        "/usr/src/pz/pzbullet/PZBullet.cpp:" + line,
                        NOT_FOUND,
                        id);
    }

    private static WorldSimulation instanceOrNpe() {
        WorldSimulation ws = WorldSimulation.instance;
        if (ws == null) {
            throw new NullPointerException(INSTANCE_NULL);
        }
        return ws;
    }

    private static WorldSimulation instanceOrRte() {
        WorldSimulation ws = WorldSimulation.instance;
        if (ws == null) {
            throw new RuntimeException(INSTANCE_NULL);
        }
        return ws;
    }

    /** Game (x, y, z=level) float position to Bullet (x - offX, z, y - offY). @0014e580 etc. */
    private static btVector3 toBullet(WorldSimulation ws, float x, float y, float z) {
        return new btVector3(
                (double) (x - (float) ws.m_offsetX),
                (double) z,
                (double) (y - (float) ws.m_offsetY));
    }

    /**
     * @0014e580
     */
    public static void addVehicle(
            int id,
            float x,
            float y,
            float z,
            float qx,
            float qy,
            float qz,
            float qw,
            String scriptName) {
        WorldSimulation ws = instanceOrNpe();
        if (scriptName == null) {
            throw new NullPointerException("scriptName is null");
        }
        PZVehicleScript script = PZVehicleScript.findScript(scriptName);
        if (script == null) {
            throw new RuntimeException("unknown vehicle script");
        }
        btTransform t =
                new btTransform(
                        new btQuaternion((double) qx, (double) qy, (double) qz, (double) qw),
                        toBullet(ws, x, y, z));
        ws.addVehicle(id, t, script);
    }

    /**
     * @0014ea10
     */
    public static void removeVehicle(int id) {
        WorldSimulation ws = instanceOrNpe();
        ws.removeVehicle(id);
    }

    /**
     * @0014ea60
     */
    public static void controlVehicle(int id, float engineForce, float brakeForce, float steer) {
        WorldSimulation ws = instanceOrNpe();
        PZVehicle v = ws.m_vehicles.get(id);
        if (v == null) {
            warnNotFound(281, id);
            return;
        }
        v.control(engineForce, brakeForce, steer);
    }

    /**
     * @0014eb50
     */
    public static void setVehicleActive(int id, boolean active) {
        WorldSimulation ws = instanceOrNpe();
        if (active && ws.m_vehicles.get(id) == null) {
            throw new RuntimeException("Vehicle not found");
        }
        ws.setVehicleActive(id, active);
    }

    /**
     * @0014ec30
     */
    public static void applyCentralForceToVehicle(int id, float x, float y, float z) {
        WorldSimulation ws = instanceOrNpe();
        PZVehicle v = ws.m_vehicles.get(id);
        if (v == null) {
            warnNotFound(316, id);
            return;
        }
        v.m_carChassis.applyCentralForce(new btVector3((double) x, (double) y, (double) z));
    }

    /**
     * @0014ed70
     */
    public static void applyTorqueToVehicle(int id, float x, float y, float z) {
        WorldSimulation ws = instanceOrNpe();
        PZVehicle v = ws.m_vehicles.get(id);
        if (v == null) {
            warnNotFound(333, id);
            return;
        }
        // torque * linearFactor, then applyTorque multiplies by angularFactor (asm 0x4ed70)
        btRigidBody chassis = v.m_carChassis;
        chassis.applyTorque(
                new btVector3((double) x, (double) y, (double) z).mul(chassis.getLinearFactor()));
    }

    /**
     * @0014eec0
     */
    public static void teleportVehicle(
            int id, float x, float y, float z, float qx, float qy, float qz, float qw) {
        WorldSimulation ws = instanceOrNpe();
        PZVehicle v = ws.m_vehicles.get(id);
        if (v == null) {
            warnNotFound(350, id);
            return;
        }
        btDiscreteDynamicsWorld w = world();
        btRigidBody chassis = v.m_carChassis;
        w.removeRigidBody(chassis);
        chassis.setCenterOfMassTransform(
                new btTransform(
                        new btQuaternion((double) qx, (double) qy, (double) qz, (double) qw),
                        toBullet(ws, x, y, z)));
        chassis.setLinearVelocity(new btVector3(0.0, 0.0, 0.0));
        chassis.setAngularVelocity(new btVector3(0.0, 0.0, 0.0));
        w.addRigidBody(chassis, (short) 2, (short) (ws.m_isServer ? 0xd : 0xf));
    }

    /**
     * @0014f240
     */
    public static void setTireInflation(int id, int wheel, float inflation) {
        WorldSimulation ws = instanceOrNpe();
        PZVehicle v = ws.m_vehicles.get(id);
        if (v == null) {
            warnNotFound(384, id);
            return;
        }
        if (wheel < 0 || v.m_script.wheelCount <= wheel) {
            throw new IllegalArgumentException("invalid wheel index");
        }
        v.m_tireInflation[wheel] = inflation;
    }

    /**
     * @0014f390
     */
    public static void setTireRemoved(int id, int wheel, boolean removed) {
        WorldSimulation ws = instanceOrNpe();
        PZVehicle v = ws.m_vehicles.get(id);
        if (v == null) {
            warnNotFound(406, id);
            return;
        }
        if (wheel < 0 || v.m_script.wheelCount <= wheel) {
            throw new IllegalArgumentException("invalid wheel index");
        }
        v.m_tireRemoved[wheel] = removed;
        btWheelInfo wi = v.m_vehicle.getWheelInfo(wheel);
        if (!removed) {
            wi.m_frictionSlip = v.m_script.wheelFriction;
            wi.m_wheelsRadius = v.m_script.wheels[wheel].radius;
        } else {
            wi.m_frictionSlip = 0.4;
            wi.m_wheelsRadius = v.m_script.wheels[wheel].radius * 0.66;
        }
        v.m_vehicle.updateWheelTransformsWS(wi, false);
        v.m_vehicle.updateWheelTransform(wheel, false);
    }

    /**
     * @001565b0: refills hack_vehicles from the vehicle map (key order).
     */
    public static int getVehicleCount() {
        WorldSimulation ws = instanceOrRte();
        PZBullet.hack_vehicles.clear();
        for (PZVehicle v : ws.m_vehicles.values()) {
            PZBullet.hack_vehicles.add(v);
        }
        return PZBullet.hack_vehicles.size();
    }

    /** 4 floats per wheel: steering, rotation, skidInfo, suspensionLength. */
    private static int writeWheels(btRaycastVehicle rv, float[] out, int pos) {
        for (int i = 0; i < rv.getNumWheels(); i++) {
            btWheelInfo wi = rv.getWheelInfo(i);
            out[pos] = (float) wi.m_steering;
            out[pos + 1] = (float) wi.m_rotation;
            out[pos + 2] = (float) wi.m_skidInfo;
            out[pos + 3] = (float) wi.m_raycastInfo.m_suspensionLength;
            pos += 4;
        }
        return pos;
    }

    /**
     * @0014f5e0: records from hack_vehicles[offset..]; returns the number written.
     */
    public static int getVehiclePhysics(int offset, float[] out) {
        instanceOrRte();
        if (offset < 0) {
            throw new IllegalArgumentException("offset is invalid");
        }
        if (PZBullet.hack_vehicles.size() <= offset) {
            return 0;
        }
        int len = out.length;
        int maxCount = len / 30;
        if (len < 30) {
            throw new IllegalArgumentException("output array is too small");
        }
        int base = 0;
        int k = 0;
        do {
            int idx = offset + k;
            if (PZBullet.hack_vehicles.size() <= idx) {
                break;
            }
            PZVehicle v = PZBullet.hack_vehicles.get(idx);
            btTransform t = v.m_vehicle.getChassisWorldTransform();
            out[base] = (float) v.m_id + 0.1f;
            btVector3 o = t.getOrigin();
            out[base + 1] = (float) o.x;
            out[base + 2] = (float) o.y;
            out[base + 3] = (float) o.z;
            btQuaternion q = new btQuaternion();
            t.getBasis().getRotation(q);
            out[base + 4] = (float) q.x;
            out[base + 5] = (float) q.y;
            out[base + 6] = (float) q.z;
            out[base + 7] = (float) q.w;
            btVector3 lv = v.m_carChassis.getLinearVelocity();
            out[base + 8] = (float) lv.x;
            out[base + 9] = (float) lv.y;
            out[base + 10] = (float) lv.z;
            out[base + 11] = (float) v.m_vehicle.m_currentVehicleSpeedKmHour;
            out[base + 12] = v.m_collided ? 1.0f : 0.0f;
            v.m_collided = false;
            int n = v.m_vehicle.getNumWheels();
            out[base + 13] = (float) n + 0.1f;
            base = writeWheels(v.m_vehicle, out, base + 14);
            k++;
        } while (k < maxCount);
        return k;
    }

    /**
     * @0014fbc0
     */
    public static int getOwnVehiclePhysics(int id, float[] out) {
        WorldSimulation ws = instanceOrRte();
        PZVehicle v = ws.m_vehicles.get(id);
        if (v == null) {
            throw new RuntimeException("Vehicle not found");
        }
        btTransform t = v.m_vehicle.getChassisWorldTransform();
        btVector3 o = t.getOrigin();
        out[0] = (float) ((double) ws.m_offsetX + o.x);
        out[1] = (float) ((double) ws.m_offsetY + o.z);
        out[2] = (float) o.y;
        btQuaternion q = new btQuaternion();
        t.getBasis().getRotation(q);
        out[3] = (float) q.x;
        out[4] = (float) q.y;
        out[5] = (float) q.z;
        out[6] = (float) q.w;
        btVector3 lv = v.m_carChassis.getLinearVelocity();
        out[7] = (float) lv.x;
        out[8] = (float) lv.y;
        out[9] = (float) lv.z;
        out[10] = (float) v.m_vehicle.getNumWheels() + 0.1f;
        writeWheels(v.m_vehicle, out, 11);
        return 0;
    }

    /** x86 cvttss2si. */
    private static int cvttss2si(float f) {
        if (f != f || f >= 2147483648.0f || f < -2147483648.0f) {
            return Integer.MIN_VALUE;
        }
        return (int) f;
    }

    /**
     * @001500a0
     */
    public static int setOwnVehiclePhysics(int id, float[] p, boolean collide) {
        WorldSimulation ws = instanceOrRte();
        double px = (double) (p[0] - (float) ws.m_offsetX);
        double py = (double) p[2];
        double pz = (double) (p[1] - (float) ws.m_offsetY);
        PZVehicle v = ws.m_vehicles.get(id);
        if (v == null) {
            return -4;
        }
        btRigidBody chassis = v.m_carChassis;
        if (collide) {
            btTransform from = new btTransform(chassis.getWorldTransform());
            btVector3 o = from.getOrigin();
            double ox = o.x;
            double oy = o.y;
            double oz = o.z;
            btVector3 pos = new btVector3(px, py, pz);
            btTransform to = new btTransform(from.getBasis(), pos);
            double dz = pz - oz;
            double dx = px - ox;
            double dy = py - oy;
            double dist = GlibcMath.sqrt(dz * dz + dx * dx + dy * dy);
            if (0.0 < dist && 0 < v.m_compound.getNumChildShapes()) {
                double minFrac = 1.0;
                for (int i = 0; i < v.m_compound.getNumChildShapes(); i++) {
                    btCollisionShape shape = v.m_compound.getChildShape(i);
                    btTransform childT = v.m_compound.getChildTransform(i);
                    double margin = shape.getMargin();
                    shape.setMargin(margin + 0.1);
                    PZVehicleClosestConvexResultCallback cb =
                            new PZVehicleClosestConvexResultCallback(o, pos);
                    cb.m_collisionFilterGroup = (short) 2;
                    cb.m_collisionFilterMask = (short) 0xffff;
                    cb.m_me = v.m_compound;
                    cb.m_closestHitFraction = 1.0;
                    world().convexSweepTest(
                                    (btConvexShape) shape,
                                    from.mul(childT),
                                    to.mul(childT),
                                    cb,
                                    0.0);
                    double f = cb.m_closestHitFraction;
                    if (f < 1.0) {
                        minFrac = (minFrac <= f) ? minFrac : f;
                    }
                    shape.setMargin(margin);
                }
                if (minFrac < 1.0) {
                    pz = oz + (pz - oz) * minFrac;
                    px = minFrac * (px - ox) + ox;
                    py = minFrac * (py - oy) + oy;
                }
            }
        }
        btTransform tr =
                new btTransform(
                        new btQuaternion(
                                (double) p[3], (double) p[4], (double) p[5], (double) p[6]),
                        new btVector3(px, py, pz));
        chassis.getMotionState().setWorldTransform(tr);
        chassis.setCenterOfMassTransform(tr);
        chassis.setLinearVelocity(new btVector3((double) p[7], (double) p[8], (double) p[9]));
        btRaycastVehicle rv = v.m_vehicle;
        if (cvttss2si(p[10]) != rv.getNumWheels()) {
            return -5;
        }
        int pos = 11;
        for (int i = 0; i < rv.getNumWheels(); i++) {
            btWheelInfo wi = rv.getWheelInfo(i);
            wi.m_steering = (double) p[pos];
            wi.m_rotation = (double) p[pos + 1];
            wi.m_skidInfo = (double) p[pos + 2];
            wi.m_raycastInfo.m_suspensionLength = (double) p[pos + 3];
            pos += 4;
        }
        return ws.m_vehicles.size();
    }

    /**
     * @001516b0
     */
    public static int setVehicleStatic(int id, boolean isStatic) {
        WorldSimulation ws = instanceOrNpe();
        PZVehicle v = ws.m_vehicles.get(id);
        if (v == null) {
            warnNotFound(719, id);
            return 0;
        }
        v.setStatic(isStatic);
        return 1;
    }

    /**
     * @001518f0: 6 floats per wheel.
     */
    public static int setVehicleParams(int id, float[] params) {
        WorldSimulation ws = instanceOrRte();
        if (!ws.m_vehicles.containsKey(id)) {
            PZDebugLog.instance()
                    .logf(
                            "PZBullet",
                            PZDebugType.Warning,
                            "/usr/src/pz/pzbullet/PZBullet.cpp:772",
                            "PZBullet::setVehicleParams> Vehicle not found. VehicleId:%d",
                            id);
            return -1;
        }
        PZVehicle v = ws.m_vehicles.get(id);
        if (v == null) {
            PZDebugLog.instance()
                    .logf(
                            "PZBullet",
                            PZDebugType.Error,
                            "/usr/src/pz/pzbullet/PZBullet.cpp:779",
                            "PZBullet::setVehicleParams> Vehicle not valid. VehicleId:%d",
                            id);
            return -1;
        }
        PZVehicleScript script = v.m_script;
        if (params.length < script.wheelCount * 6) {
            throw new IllegalArgumentException("output array is too small");
        }
        for (int i = 0; i < script.wheelCount; i++) {
            int b = i * 6;
            btWheelInfo wi = v.m_vehicle.getWheelInfo(i);
            boolean present = params[b] > 0.1f;
            v.m_tirePresent[i] = present;
            v.m_tireInflation[i] = params[b + 1];
            // params[b + 2] is read by the caller's layout but unused by the native
            wi.m_wheelsDampingRelaxation = (double) params[b + 3];
            wi.m_wheelsDampingCompression = (double) params[b + 4];
            wi.m_suspensionRestLength1 = (double) params[b + 5] + script.suspensionRestLength;
            if (!present) {
                wi.m_wheelsRadius = script.wheels[i].radius * 0.66;
                wi.m_frictionSlip = 0.4;
            } else {
                wi.m_wheelsRadius = script.wheels[i].radius;
                wi.m_frictionSlip = script.wheelFriction;
            }
        }
        return 0;
    }

    /**
     * @00151c10
     */
    public static int setVehicleMass(int id, float mass) {
        WorldSimulation ws = instanceOrNpe();
        PZVehicle v = ws.m_vehicles.get(id);
        if (v == null) {
            warnNotFound(842, id);
            return -1;
        }
        btVector3 inertia = new btVector3(0.0, 0.0, 0.0);
        v.m_carChassis.getCollisionShape().calculateLocalInertia((double) mass, inertia);
        v.m_carChassis.setMassProps((double) mass, inertia);
        return 0;
    }

    /**
     * @00151e70
     */
    public static void defineVehicleScript(String name, float[] data) {
        if (name == null || data == null) {
            throw new NullPointerException("argument is null");
        }
        PZVehicleScript.fromJava(name, data, data.length);
    }

    /**
     * @00151f90
     */
    public static void defineVehiclePhysicsMesh(String name, int shapeIndex, float[] points) {
        if (name == null || points == null) {
            throw new NullPointerException("argument is null");
        }
        PZVehicleScript script = PZVehicleScript.findScript(name);
        if (script == null) {
            throw new RuntimeException("unknown vehicle script");
        }
        script.definePhysicsMesh(shapeIndex, points.length / 3, points);
    }

    /**
     * @001521b0
     */
    public static void setVehicleVelocityMultiplier(int id, float maxSpeed, float multiplier) {
        WorldSimulation ws = instanceOrNpe();
        PZVehicle v = ws.m_vehicles.get(id);
        if (v == null) {
            warnNotFound(967, id);
            return;
        }
        v.m_maxSpeed = (double) maxSpeed;
        v.m_velocityMultiplier = (double) multiplier;
    }

    /** Looks up both vehicles for the constraint natives; logs and returns null if missing. */
    private static PZVehicle[] lookupPair(
            WorldSimulation ws, int idA, int idB, int lineA, int lineB) {
        PZVehicle a = ws.m_vehicles.get(idA);
        if (a == null) {
            warnNotFound(lineA, idA);
            return null;
        }
        PZVehicle b = ws.m_vehicles.get(idB);
        if (b == null) {
            warnNotFound(lineB, idB);
            return null;
        }
        return new PZVehicle[] {a, b};
    }

    /**
     * @001522b0: hinge with axes (0,1,0) in both frames.
     */
    public static int addHingeConstraint(
            int idA, int idB, float ax, float ay, float az, float bx, float by, float bz) {
        WorldSimulation ws = instanceOrNpe();
        if (idA == idB) {
            return -1;
        }
        PZVehicle[] ab = lookupPair(ws, idA, idB, 992, 1001);
        if (ab == null) {
            return -1;
        }
        btVector3 pivotA = new btVector3((double) ax, (double) ay, (double) az);
        btVector3 pivotB = new btVector3((double) bx, (double) by, (double) bz);
        btVector3 axisA = new btVector3(0.0, 1.0, 0.0);
        btVector3 axisB = new btVector3(0.0, 1.0, 0.0);
        btGlobals.btAlignedAlloc(0x5e0, 16);
        btHingeConstraint c =
                new btHingeConstraint(
                        ab[0].m_carChassis,
                        ab[1].m_carChassis,
                        pivotA,
                        pivotB,
                        axisA,
                        axisB,
                        false);
        c.enableFeedback(true);
        return ws.addConstraint(new WorldSimulation.Constraint(ab[0], ab[1], null, null, c));
    }

    /**
     * @00152b60
     */
    public static int addPointConstraint(
            int idA, int idB, float ax, float ay, float az, float bx, float by, float bz) {
        WorldSimulation ws = instanceOrNpe();
        if (idA == idB) {
            return -1;
        }
        PZVehicle[] ab = lookupPair(ws, idA, idB, 1106, 1114);
        if (ab == null) {
            return -1;
        }
        btVector3 pivotA = new btVector3((double) ax, (double) ay, (double) az);
        btVector3 pivotB = new btVector3((double) bx, (double) by, (double) bz);
        btGlobals.btAlignedAlloc(0x2c0, 16);
        btPoint2PointConstraint c =
                new btPoint2PointConstraint(ab[0].m_carChassis, ab[1].m_carChassis, pivotA, pivotB);
        c.enableFeedback(true);
        return ws.addConstraint(new WorldSimulation.Constraint(ab[0], ab[1], null, null, c));
    }

    private static btTransform frameAt(float x, float y, float z) {
        btTransform t = new btTransform();
        t.setIdentity();
        t.setOrigin(new btVector3((double) x, (double) y, (double) z));
        return t;
    }

    /** fmod into [-pi, pi] as inlined in add6DofConstraint (btNormalizeAngle). */
    private static double normalizeAngle(double a) {
        double r = GlibcMath.fmod(a, 6.283185307179586);
        if (r < -3.141592653589793) {
            return r + 6.283185307179586;
        }
        if (3.141592653589793 < r) {
            return r - 6.283185307179586;
        }
        return r;
    }

    /**
     * @00152d90
     */
    public static int add6DofConstraint(
            int idA,
            int idB,
            float ax,
            float ay,
            float az,
            float bx,
            float by,
            float bz,
            float linLoX,
            float linLoY,
            float linLoZ,
            float linHiX,
            float linHiY,
            float linHiZ,
            float angLoX,
            float angLoY,
            float angLoZ,
            float angHiX,
            float angHiY,
            float angHiZ) {
        WorldSimulation ws = instanceOrNpe();
        if (idA == idB) {
            return -1;
        }
        PZVehicle[] ab = lookupPair(ws, idA, idB, 1159, 1167);
        if (ab == null) {
            return -1;
        }
        btTransform frameA = frameAt(ax, ay, az);
        btTransform frameB = frameAt(bx, by, bz);
        btGlobals.btAlignedAlloc(0xa20, 16);
        btGeneric6DofConstraint c =
                new btGeneric6DofConstraint(
                        ab[0].m_carChassis, ab[1].m_carChassis, frameA, frameB, true);
        c.setLinearLowerLimit(new btVector3((double) linLoX, (double) linLoY, (double) linLoZ));
        c.setLinearUpperLimit(new btVector3((double) linHiX, (double) linHiY, (double) linHiZ));
        float[] lo = {angLoX, angLoY, angLoZ};
        for (int i = 0; i < 3; i++) {
            c.getRotationalLimitMotor(i).m_loLimit = normalizeAngle((double) lo[i]);
        }
        float[] hi = {angHiX, angHiY, angHiZ};
        for (int i = 0; i < 3; i++) {
            c.getRotationalLimitMotor(i).m_hiLimit = normalizeAngle((double) hi[i]);
        }
        c.enableFeedback(true);
        return ws.addConstraint(new WorldSimulation.Constraint(ab[0], ab[1], null, null, c));
    }

    /**
     * @00152530
     */
    public static int addRopeConstraint(
            int idA,
            int idB,
            float ax,
            float ay,
            float az,
            float bx,
            float by,
            float bz,
            float maxDist) {
        WorldSimulation ws = instanceOrNpe();
        if (idA == idB) {
            throw new IllegalArgumentException("the same vehicle");
        }
        PZVehicle a = ws.m_vehicles.get(idA);
        if (a == null) {
            throw new NullPointerException("vehicle A not found");
        }
        PZVehicle b = ws.m_vehicles.get(idB);
        if (b == null) {
            throw new NullPointerException("vehicle B not found");
        }
        a.setStatic(false);
        a.setActive(true);
        b.setStatic(false);
        b.setActive(true);
        btTransform frameA = frameAt(ax, ay, az);
        btTransform frameB = frameAt(bx, by, bz);
        btGlobals.btAlignedAlloc(0xa20, 16);
        btGeneric6DofConstraint c =
                new btGeneric6DofConstraint(a.m_carChassis, b.m_carChassis, frameA, frameB, true);
        c.enableFeedback(true);
        double lo = (double) (-maxDist);
        double hi = (double) maxDist;
        c.setLinearLowerLimit(new btVector3(lo, lo, lo));
        c.setLinearUpperLimit(new btVector3(hi, hi, hi));
        return ws.addConstraint(new WorldSimulation.Constraint(a, b, null, null, c));
    }

    /**
     * @00153250
     */
    public static void setConstraintERP(int id, float erp, int axis) {
        WorldSimulation ws = instanceOrNpe();
        ws.setConstraintERP(id, erp, axis);
    }

    public static void removeConstraint(int id) {
        WorldSimulation ws = instanceOrNpe();
        ws.removeConstraint(id);
    }

    /** No instance null check in the binary (null deref would crash; NPE here). */
    public static void detachConstraint(int ragdollId, int constraintIndex) {
        PZRagdoll r = WorldSimulation.instance.m_ragdolls.get(ragdollId);
        if (r != null) {
            r.detachConstraint(constraintIndex);
        }
    }

    public static boolean checkWheelCollision(int vehicleId, int ragdollId, int bodyPart) {
        WorldSimulation ws = instanceOrNpe();
        PZVehicle v = ws.m_vehicles.get(vehicleId);
        if (v == null) {
            return false;
        }
        return v.checkWheelCollision(ragdollId, bodyPart, 0.5f);
    }
}
