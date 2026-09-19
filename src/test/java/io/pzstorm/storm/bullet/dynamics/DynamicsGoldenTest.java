package io.pzstorm.storm.bullet.dynamics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import io.pzstorm.storm.bullet.collision.broadphase.btDbvtBroadphase;
import io.pzstorm.storm.bullet.collision.dispatch.btCollisionDispatcher;
import io.pzstorm.storm.bullet.collision.dispatch.btCollisionObject;
import io.pzstorm.storm.bullet.collision.dispatch.btDefaultCollisionConfiguration;
import io.pzstorm.storm.bullet.collision.shapes.btBoxShape;
import io.pzstorm.storm.bullet.collision.shapes.btCollisionShape;
import io.pzstorm.storm.bullet.collision.shapes.btSphereShape;
import io.pzstorm.storm.bullet.dynamics.constraintsolver.btSequentialImpulseConstraintSolver;
import io.pzstorm.storm.bullet.dynamics.vehicle.btDefaultVehicleRaycaster;
import io.pzstorm.storm.bullet.dynamics.vehicle.btRaycastVehicle;
import io.pzstorm.storm.bullet.dynamics.vehicle.btWheelInfo;
import io.pzstorm.storm.bullet.libm.GlibcRand;
import io.pzstorm.storm.bullet.linearmath.btDefaultMotionState;
import io.pzstorm.storm.bullet.linearmath.btGlobals;
import io.pzstorm.storm.bullet.linearmath.btMatrix3x3;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Bit-exact comparison of the dynamics port against stock Bullet 2.82 (GCC 10.5 -O3,
 * BT_USE_DOUBLE_PRECISION). {@code dynamics-golden.txt} was produced by the C++ driver documented
 * in docs/re-bullet/dynamics.md, which runs the same three scenarios and prints, per step, an
 * FNV-1a hash over the raw bits of every recorded double. The scenarios below mirror that driver
 * call for call, so a single differing bit in any recorded value fails the step it first appears
 * in.
 */
class DynamicsGoldenTest implements UnitTest {

    /**
     * The C++ goldens ran in a fresh process. Other test classes in this JVM (the PZ world sets
     * gDeactivationTime and gContactAddedCallback) leave Bullet globals changed, so restore them.
     */
    @BeforeEach
    void freshLibrary() {
        btGlobals.resetAll();
        GlibcRand.srand(1);
    }

    /** Records values the way the C++ driver does: FNV-1a 64 over the little-endian bytes. */
    private static final class Rec {
        long h;
        final StringBuilder hex = new StringBuilder();
        final List<String> lines = new ArrayList<>();

        void reset() {
            h = 1469598103934665603L; // matches the C++ driver (not the canonical FNV basis)
            hex.setLength(0);
        }

        void d(double d) {
            long b = Double.doubleToRawLongBits(d);
            for (int i = 0; i < 8; i++) {
                h ^= (b >>> (8 * i)) & 0xff;
                h *= 0x100000001b3L;
            }
            hex.append(' ').append(String.format("%016x", b));
        }

        void i(long v) {
            d((double) v);
        }

        void v(btVector3 v) {
            d(v.x());
            d(v.y());
            d(v.z());
        }

        void t(btTransform t) {
            for (int r = 0; r < 3; r++) v(t.getBasis().getRow(r));
            v(t.getOrigin());
        }

        void end(String tag, int step) {
            lines.add(tag + " " + step + " " + String.format("%016x", h) + "   #" + hex);
        }
    }

    private static List<String> golden(String tag) throws IOException {
        List<String> out = new ArrayList<>();
        try (InputStream in = DynamicsGoldenTest.class.getResourceAsStream("dynamics-golden.txt")) {
            assertNotNull(in, "dynamics-golden.txt resource");
            BufferedReader r =
                    new BufferedReader(new InputStreamReader(in, StandardCharsets.US_ASCII));
            for (String line; (line = r.readLine()) != null; ) {
                if (line.startsWith(tag + " ")) out.add(line.trim());
            }
        }
        return out;
    }

    private static void check(String tag, Rec rec) throws IOException {
        List<String> exp = golden(tag);
        List<String> got = new ArrayList<>();
        for (String l : rec.lines) if (l.startsWith(tag + " ")) got.add(l);
        String dump = System.getenv("DYN_DUMP");
        if (dump != null) {
            java.nio.file.Files.write(java.nio.file.Paths.get(dump, tag + ".txt"), got);
        }
        assertTrue(!exp.isEmpty(), "golden has " + tag);
        assertEquals(exp.size(), got.size(), tag + " step count");
        for (int k = 0; k < exp.size(); k++) {
            String g = got.get(k);
            String gHash = g.substring(0, g.indexOf("   #"));
            assertEquals(
                    exp.get(k),
                    gHash,
                    "first divergence (java values:" + g.substring(g.indexOf('#') + 1) + ")");
        }
    }

    // ------------------------------------------------------------------ A: lone rigid body

    @Test
    void rigidBodyIntegrationDampingAndSleepingMatchCpp() throws IOException {
        Rec R = new Rec();
        btBoxShape box = new btBoxShape(new btVector3(1, 0.5, 2));
        btVector3 inertia = new btVector3(0, 0, 0);
        box.calculateLocalInertia(2.0, inertia);
        btRigidBody.btRigidBodyConstructionInfo ci =
                new btRigidBody.btRigidBodyConstructionInfo(2.0, null, box, inertia);
        ci.m_linearDamping = 0.3;
        ci.m_angularDamping = 0.5;
        ci.m_startWorldTransform.setOrigin(new btVector3(1, 2, 3));
        ci.m_startWorldTransform.setRotation(new btQuaternion(new btVector3(0.2, 1, -0.3), 0.7));
        btRigidBody body = new btRigidBody(ci);
        body.setGravity(new btVector3(0, -9.8, 0));
        body.setLinearVelocity(new btVector3(1, 2, -0.5));
        body.setAngularVelocity(new btVector3(0.3, -1.1, 0.7));
        double dt = 1.0 / 60.0;
        for (int i = 0; i < 120; i++) {
            R.reset();
            body.applyGravity();
            body.applyCentralForce(new btVector3(0.1 * i, 0, -0.05 * i));
            body.applyForce(new btVector3(0, 1.5, 0), new btVector3(0.5, 0, 0.25));
            body.applyTorque(new btVector3(0.01 * i, 0.2, 0));
            if (i % 17 == 0)
                body.applyImpulse(new btVector3(0.3, 0.1, -0.2), new btVector3(0, 0.4, -1));
            body.integrateVelocities(dt);
            body.applyDamping(dt);
            btTransform t = new btTransform();
            body.predictIntegratedTransform(dt, t);
            body.proceedToTransform(t);
            body.clearForces();
            body.updateDeactivation(dt);
            R.t(body.getWorldTransform());
            R.v(body.getLinearVelocity());
            R.v(body.getAngularVelocity());
            R.v(body.computeGyroscopicForce(3.0));
            btMatrix3x3 iw = body.getInvInertiaTensorWorld();
            R.v(iw.getRow(0));
            R.v(iw.getRow(1));
            R.v(iw.getRow(2));
            R.d(body.getDeactivationTime());
            R.i(body.wantsSleeping() ? 1 : 0);
            R.d(body.computeImpulseDenominator(new btVector3(1, 1, 1), new btVector3(0, 1, 0)));
            R.d(body.computeAngularImpulseDenominator(new btVector3(0.6, 0, 0.8)));
            R.end("rb", i);
        }
        body.setLinearVelocity(new btVector3(0.01, 0, 0));
        body.setAngularVelocity(new btVector3(0, 0.01, 0));
        for (int i = 0; i < 150; i++) {
            R.reset();
            body.updateDeactivation(0.02);
            R.d(body.getDeactivationTime());
            R.i(body.wantsSleeping() ? 1 : 0);
            R.i(body.getActivationState());
            R.end("sleep", i);
        }
        check("rb", R);
        check("sleep", R);
        // sanity (independent of golden): 150*0.02 = 3s > gDeactivationTime (2s) -> wants sleeping
        assertTrue(body.wantsSleeping());
    }

    // ------------------------------------------------------------------ B/C: world

    private static btDiscreteDynamicsWorld newWorld() {
        btDefaultCollisionConfiguration cfg = new btDefaultCollisionConfiguration();
        btCollisionDispatcher disp = new btCollisionDispatcher(cfg);
        btDbvtBroadphase bp = new btDbvtBroadphase();
        btSequentialImpulseConstraintSolver solver = new btSequentialImpulseConstraintSolver();
        return new btDiscreteDynamicsWorld(disp, bp, solver, cfg);
    }

    private static btRigidBody mkBody(
            btDiscreteDynamicsWorld w, btCollisionShape s, double mass, btTransform t, boolean ms) {
        btVector3 inertia = new btVector3(0, 0, 0);
        if (mass != 0) s.calculateLocalInertia(mass, inertia);
        btRigidBody.btRigidBodyConstructionInfo ci =
                new btRigidBody.btRigidBodyConstructionInfo(
                        mass, ms ? new btDefaultMotionState(t) : null, s, inertia);
        if (!ms) ci.m_startWorldTransform.set(t);
        btRigidBody b = new btRigidBody(ci);
        w.addRigidBody(b);
        return b;
    }

    @Test
    void worldSubstepsInterpolationAndSleepingMatchCpp() throws IOException {
        Rec R = new Rec();
        btDiscreteDynamicsWorld w = newWorld();
        btTransform t = new btTransform();
        t.setIdentity();
        t.setOrigin(new btVector3(0, -1, 0));
        btRigidBody ground = mkBody(w, new btBoxShape(new btVector3(50, 1, 50)), 0, t, false);
        ground.setFriction(0.8);
        t.setOrigin(new btVector3(0, 3, 0));
        t.setRotation(new btQuaternion(new btVector3(1, 0, 0.3), 0.4));
        btRigidBody box = mkBody(w, new btBoxShape(new btVector3(0.5, 0.5, 0.5)), 1, t, false);
        t.setIdentity();
        t.setOrigin(new btVector3(0.3, 6, 0.2));
        btRigidBody sphere = mkBody(w, new btSphereShape(0.5), 2, t, true);
        sphere.setLinearVelocity(new btVector3(0.5, 0, 0));
        sphere.setDamping(0.1, 0.2);
        final double[] dts = {0.016, 0.033, 0.005, 0.1, 1.0 / 60.0, 0.021, 0.0, 0.017};
        int totalSubsteps = 0;
        for (int i = 0; i < 400; i++) {
            R.reset();
            int n = w.stepSimulation(dts[i % 8], 3, 1.0 / 60.0);
            totalSubsteps += n;
            R.i(n);
            btTransform ms = new btTransform();
            sphere.getMotionState().getWorldTransform(ms);
            R.t(ms);
            R.t(sphere.getWorldTransform());
            R.t(box.getWorldTransform());
            R.v(box.getLinearVelocity());
            R.v(box.getAngularVelocity());
            R.i(box.getActivationState());
            R.i(sphere.getActivationState());
            R.i(w.getDispatcher().getNumManifolds());
            R.end("world", i);
        }
        // sanity: total substeps over 400 calls (value from the C++ run) and the box fell asleep
        assertEquals(626, totalSubsteps);
        assertEquals(btCollisionObject.ISLAND_SLEEPING, box.getActivationState());
        for (int i = 0; i < 30; i++) {
            R.reset();
            box.activate();
            sphere.activate();
            int n = w.stepSimulation(0.01 + 0.001 * i, 0);
            R.i(n);
            btTransform ms = new btTransform();
            sphere.getMotionState().getWorldTransform(ms);
            R.t(ms);
            R.t(box.getWorldTransform());
            R.end("varstep", i);
        }
        check("world", R);
        check("varstep", R);
    }

    @Test
    void raycastVehicleOnFlatGroundMatchesCpp() throws IOException {
        Rec R = new Rec();
        btDiscreteDynamicsWorld w = newWorld();
        btTransform t = new btTransform();
        t.setIdentity();
        t.setOrigin(new btVector3(0, -1, 0));
        mkBody(w, new btBoxShape(new btVector3(100, 1, 100)), 0, t, false);
        t.setOrigin(new btVector3(0, 1.2, 0));
        btRigidBody chassis = mkBody(w, new btBoxShape(new btVector3(1, 0.5, 2)), 800, t, true);
        chassis.setActivationState(btCollisionObject.DISABLE_DEACTIVATION);
        btRaycastVehicle.btVehicleTuning tuning = new btRaycastVehicle.btVehicleTuning();
        tuning.m_suspensionStiffness = 20;
        tuning.m_suspensionCompression = 4.4;
        tuning.m_suspensionDamping = 2.3;
        tuning.m_frictionSlip = 1000;
        btDefaultVehicleRaycaster rc = new btDefaultVehicleRaycaster(w);
        btRaycastVehicle v = new btRaycastVehicle(tuning, chassis, rc);
        v.setCoordinateSystem(0, 1, 2);
        w.addVehicle(v);
        btVector3 dir = new btVector3(0, -1, 0), axle = new btVector3(-1, 0, 0);
        for (int i = 0; i < 4; i++) {
            btVector3 cp = new btVector3((i & 1) != 0 ? -0.9 : 0.9, -0.3, (i < 2) ? 1.6 : -1.6);
            btWheelInfo wi = v.addWheel(cp, dir, axle, 0.6, 0.4, tuning, i < 2);
            wi.m_rollInfluence = 0.1;
        }
        v.resetSuspension();
        for (int i = 0; i < 360; i++) {
            R.reset();
            if (i == 30) {
                v.applyEngineForce(1500, 2);
                v.applyEngineForce(1500, 3);
            }
            if (i == 120) {
                v.setSteeringValue(0.3, 0);
                v.setSteeringValue(0.3, 1);
            }
            if (i == 200) {
                v.applyEngineForce(0, 2);
                v.applyEngineForce(0, 3);
                v.setBrake(40, 2);
                v.setBrake(40, 3);
            }
            if (i == 260) {
                v.setSteeringValue(-0.2, 0);
                v.setSteeringValue(-0.2, 1);
                v.setBrake(0, 2);
                v.setBrake(0, 3);
                v.applyEngineForce(-800, 2);
                v.applyEngineForce(-800, 3);
            }
            int n = w.stepSimulation(1.0 / 60.0, 1, 1.0 / 60.0);
            R.i(n);
            R.t(chassis.getWorldTransform());
            R.v(chassis.getLinearVelocity());
            R.v(chassis.getAngularVelocity());
            R.d(v.getCurrentSpeedKmHour());
            for (int k = 0; k < v.getNumWheels(); k++) {
                btWheelInfo wi = v.getWheelInfo(k);
                R.d(wi.m_raycastInfo.m_suspensionLength);
                R.d(wi.m_rotation);
                R.d(wi.m_deltaRotation);
                R.d(wi.m_skidInfo);
                R.d(wi.m_wheelsSuspensionForce);
                R.i(wi.m_raycastInfo.m_isInContact ? 1 : 0);
                R.v(wi.m_raycastInfo.m_contactPointWS);
                R.t(wi.m_worldTransform);
            }
            R.end("vehicle", i);
        }
        check("vehicle", R);
        // sanity: all wheels on the ground, car moved forward then reversed a bit
        for (int k = 0; k < 4; k++) assertTrue(v.getWheelInfo(k).m_raycastInfo.m_isInContact);
        assertTrue(chassis.getWorldTransform().getOrigin().z() > 14);
    }
}
