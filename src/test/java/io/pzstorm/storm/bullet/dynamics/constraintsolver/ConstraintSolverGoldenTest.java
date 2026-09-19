package io.pzstorm.storm.bullet.dynamics.constraintsolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import io.pzstorm.storm.bullet.dynamics.btDiscreteDynamicsWorld;
import io.pzstorm.storm.bullet.dynamics.btRigidBody;
import io.pzstorm.storm.bullet.libm.GlibcRand;
import io.pzstorm.storm.bullet.linearmath.btGlobals;
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
 * Bit-exact comparison of the constraint solver port against stock Bullet 2.82 (GCC 10.5 -O3,
 * BT_USE_DOUBLE_PRECISION). {@code constraints-golden.txt} holds one FNV-1a hash per step over the
 * raw bits of every recorded double, produced by the C++ driver described in
 * docs/re-bullet/constraints.md; the scenarios here mirror that driver call for call.
 *
 * <p>Coverage: every joint type (hinge single/two-body with limits, motor, frame offset, breaking
 * threshold; point2point with CFM and iteration override; 6dof with linear/angular limits, both
 * motors, per-axis params and joint feedback; single-body 6dof; cone twist with limits, damping and
 * motor target), and contacts with restitution, rolling friction, anisotropic (rolling) friction,
 * gyroscopic force and a kinematic body — each under six solver-mode/parameter sets (SIMD vs scalar
 * paths, randomized order, interleaving, 2 friction directions, friction direction caching, no
 * warmstarting, split impulse on/off, global CFM, damping).
 */
class ConstraintSolverGoldenTest implements UnitTest {

    /**
     * The C++ goldens ran in a fresh process. Other test classes in this JVM (the PZ world sets
     * gDeactivationTime and gContactAddedCallback) leave Bullet globals changed, so restore them.
     */
    @BeforeEach
    void freshLibrary() {
        btGlobals.resetAll();
        GlibcRand.srand(1);
    }

    private static final class Rec {
        long h;
        final StringBuilder hex = new StringBuilder();
        final List<String> lines = new ArrayList<>();

        void reset() {
            h = 1469598103934665603L;
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
        try (InputStream in =
                ConstraintSolverGoldenTest.class.getResourceAsStream("constraints-golden.txt")) {
            assertNotNull(in, "constraints-golden.txt resource");
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
        String dump = System.getenv("CS_DUMP");
        if (dump != null) {
            java.nio.file.Files.write(java.nio.file.Paths.get(dump, tag + ".txt"), got);
        }
        assertFalse(exp.isEmpty(), "golden has " + tag);
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

    private static btDiscreteDynamicsWorld newWorld() {
        btDefaultCollisionConfiguration cfg = new btDefaultCollisionConfiguration();
        btCollisionDispatcher disp = new btCollisionDispatcher(cfg);
        btDbvtBroadphase bp = new btDbvtBroadphase();
        btSequentialImpulseConstraintSolver solver = new btSequentialImpulseConstraintSolver();
        return new btDiscreteDynamicsWorld(disp, bp, solver, cfg);
    }

    private static btRigidBody mk(
            btDiscreteDynamicsWorld w, btCollisionShape s, double mass, btTransform t) {
        btVector3 inertia = new btVector3(0, 0, 0);
        if (mass != 0) s.calculateLocalInertia(mass, inertia);
        btRigidBody.btRigidBodyConstructionInfo ci =
                new btRigidBody.btRigidBodyConstructionInfo(mass, null, s, inertia);
        ci.m_startWorldTransform.set(t);
        btRigidBody b = new btRigidBody(ci);
        w.addRigidBody(b);
        return b;
    }

    private static btTransform tr(double x, double y, double z) {
        btTransform t = new btTransform();
        t.setIdentity();
        t.setOrigin(new btVector3(x, y, z));
        return t;
    }

    private static void applyMode(btContactSolverInfo si, int mode) {
        switch (mode) {
            case 0:
                break;
            case 1:
                si.m_solverMode =
                        btContactSolverInfoData.SOLVER_SIMD
                                | btContactSolverInfoData.SOLVER_USE_WARMSTARTING
                                | btContactSolverInfoData.SOLVER_USE_2_FRICTION_DIRECTIONS
                                | btContactSolverInfoData.SOLVER_RANDMIZE_ORDER
                                | btContactSolverInfoData
                                        .SOLVER_INTERLEAVE_CONTACT_AND_FRICTION_CONSTRAINTS;
                break;
            case 2:
                si.m_solverMode =
                        btContactSolverInfoData.SOLVER_USE_WARMSTARTING
                                | btContactSolverInfoData.SOLVER_USE_2_FRICTION_DIRECTIONS
                                | btContactSolverInfoData.SOLVER_ENABLE_FRICTION_DIRECTION_CACHING
                                | btContactSolverInfoData
                                        .SOLVER_DISABLE_VELOCITY_DEPENDENT_FRICTION_DIRECTION
                                | btContactSolverInfoData.SOLVER_RANDMIZE_ORDER;
                break;
            case 3:
                si.m_solverMode = 0;
                si.m_splitImpulse = 0;
                break;
            case 4:
                si.m_solverMode =
                        btContactSolverInfoData.SOLVER_SIMD
                                | btContactSolverInfoData.SOLVER_USE_2_FRICTION_DIRECTIONS;
                si.m_splitImpulse = 0;
                si.m_globalCfm = 0.001;
                si.m_damping = 0.8;
                si.m_erp = 0.3;
                si.m_numIterations = 7;
                break;
            case 5:
                si.m_solverMode =
                        btContactSolverInfoData.SOLVER_SIMD
                                | btContactSolverInfoData.SOLVER_USE_WARMSTARTING
                                | btContactSolverInfoData.SOLVER_USE_2_FRICTION_DIRECTIONS;
                si.m_splitImpulsePenetrationThreshold = -0.001;
                si.m_warmstartingFactor = 0.6;
                si.m_linearSlop = 0.01;
                break;
            default:
                throw new IllegalArgumentException();
        }
    }

    // ------------------------------------------------------------------ statics

    @Test
    void randomOrderGeneratorAndLegacyResolversMatchCpp() throws IOException {
        Rec R = new Rec();
        btSequentialImpulseConstraintSolver rp = new btSequentialImpulseConstraintSolver();
        R.reset();
        int[] ns = {1, 2, 3, 4, 5, 16, 17, 255, 256, 257, 65536, 65537, 1000000};
        for (int k = 0; k < 13; k++) for (int j = 0; j < 20; j++) R.i(rp.btRandInt2(ns[k]));
        R.i(rp.getRandSeed());
        R.end("rand", 0);

        btSphereShape s = new btSphereShape(0.5);
        btVector3 in = new btVector3(0, 0, 0);
        s.calculateLocalInertia(2, in);
        btRigidBody.btRigidBodyConstructionInfo ci =
                new btRigidBody.btRigidBodyConstructionInfo(2, null, s, in);
        ci.m_startWorldTransform.set(tr(0, 1, 0));
        ci.m_startWorldTransform.setRotation(new btQuaternion(new btVector3(1, 1, 0), 0.5));
        btRigidBody a = new btRigidBody(ci);
        ci.m_mass = 1;
        ci.m_startWorldTransform.set(tr(0.2, 0.1, 0.3));
        btRigidBody b = new btRigidBody(ci);
        a.setLinearVelocity(new btVector3(0.3, -2, 0.1));
        a.setAngularVelocity(new btVector3(1, 0, -0.5));
        b.setLinearVelocity(new btVector3(0, 1, 0));
        b.setAngularVelocity(new btVector3(0, 2, 0));
        btContactSolverInfo si = new btContactSolverInfo();
        for (int i = 0; i < 10; i++) {
            R.reset();
            double imp =
                    btContactConstraint.resolveSingleCollision(
                            a,
                            b,
                            new btVector3(0.1, 0.55, 0.1 * i),
                            new btVector3(0, 1, 0.1).normalized(),
                            si,
                            -0.01 * i);
            R.d(imp);
            double[] bi = {0};
            btContactConstraint.resolveSingleBilateral(
                    a,
                    new btVector3(0.1, 0.5, 0.2),
                    b,
                    new btVector3(0.1 * i, 0.4, 0.2),
                    0,
                    new btVector3(0.6, 0, 0.8),
                    bi,
                    1.0 / 60.0);
            R.d(bi[0]);
            btContactConstraint.resolveSingleBilateral(
                    a,
                    new btVector3(0, 0, 0),
                    b,
                    new btVector3(0, 0, 0),
                    0,
                    new btVector3(1, 1, 0),
                    bi,
                    1.0 / 60.0);
            R.d(bi[0]);
            R.v(a.getLinearVelocity());
            R.v(a.getAngularVelocity());
            R.v(b.getLinearVelocity());
            R.v(b.getAngularVelocity());
            R.end("single", i);
        }
        check("rand", R);
        check("single", R);
    }

    // ------------------------------------------------------------------ joints

    private static void joints(int mode) throws IOException {
        Rec R = new Rec();
        btDiscreteDynamicsWorld w = newWorld();
        applyMode(w.getSolverInfo(), mode);
        btBoxShape groundS = new btBoxShape(new btVector3(50, 1, 50));
        btBoxShape boxS = new btBoxShape(new btVector3(0.5, 0.25, 0.25));
        btSphereShape sphS = new btSphereShape(0.3);
        btRigidBody ground = mk(w, groundS, 0, tr(0, -1, 0));
        ground.setFriction(0.7);
        btRigidBody b0 = mk(w, boxS, 1, tr(0, 3, 0));
        btRigidBody b1 = mk(w, boxS, 2, tr(1.2, 3, 0.1));
        btRigidBody b2 = mk(w, sphS, 1.5, tr(2.4, 3, 0));
        btTransform t3 = tr(3.6, 3, 0);
        t3.setRotation(new btQuaternion(new btVector3(0, 1, 0.2), 0.3));
        btRigidBody b3 = mk(w, boxS, 1, t3);
        btRigidBody b4 = mk(w, boxS, 0.7, tr(4.8, 3, -0.1));
        btRigidBody b5 = mk(w, boxS, 1.2, tr(-2, 2, 1));
        b1.setAngularVelocity(new btVector3(0.5, 0, 1));
        b4.setLinearVelocity(new btVector3(0, 3, 0));

        btHingeConstraint h0 =
                new btHingeConstraint(b0, new btVector3(-0.5, 0, 0), new btVector3(0, 0, 1));
        h0.setLimit(-1.0, 1.2, 0.9, 0.3, 1.0);
        h0.enableAngularMotor(true, 1.5, 0.4);
        w.addConstraint(h0);

        btPoint2PointConstraint p =
                new btPoint2PointConstraint(
                        b0, b1, new btVector3(0.6, 0, 0), new btVector3(-0.6, 0, 0));
        p.setParam(btTypedConstraint.BT_CONSTRAINT_CFM, 0.01);
        p.setOverrideNumSolverIterations(20);
        w.addConstraint(p, true);

        btTransform fa = tr(0.6, 0, 0), fb = tr(-0.6, 0, 0);
        fb.setRotation(new btQuaternion(new btVector3(1, 0, 0), 0.2));
        btGeneric6DofConstraint d = new btGeneric6DofConstraint(b1, b2, fa, fb, true);
        d.setLinearLowerLimit(new btVector3(-0.1, 0, 0));
        d.setLinearUpperLimit(new btVector3(0.2, 0, 0));
        d.setAngularLowerLimit(new btVector3(-0.5, -0.3, -0.2));
        d.setAngularUpperLimit(new btVector3(0.5, 0.3, 0.2));
        d.getRotationalLimitMotor(0).m_enableMotor = true;
        d.getRotationalLimitMotor(0).m_targetVelocity = 2;
        d.getRotationalLimitMotor(0).m_maxMotorForce = 0.5;
        d.getTranslationalLimitMotor().m_enableMotor[0] = true;
        d.getTranslationalLimitMotor().m_targetVelocity.setX(-0.5);
        d.getTranslationalLimitMotor().m_maxMotorForce.setX(1);
        d.setParam(btTypedConstraint.BT_CONSTRAINT_STOP_ERP, 0.5, 1);
        d.setParam(btTypedConstraint.BT_CONSTRAINT_CFM, 0.02, 4);
        btJointFeedback jf = new btJointFeedback();
        d.setJointFeedback(jf);
        w.addConstraint(d, true);

        btTransform ca = tr(0.6, 0, 0), cb = tr(-0.6, 0, 0);
        ca.setRotation(new btQuaternion(new btVector3(0, 0, 1), 0.1));
        btConeTwistConstraint c = new btConeTwistConstraint(b2, b3, ca, cb);
        c.setLimit(0.6, 0.4, 0.8, 0.9, 0.3, 1.0);
        c.setDamping(0.05);
        c.enableMotor(true);
        c.setMaxMotorImpulse(0.5);
        c.setMotorTarget(new btQuaternion(new btVector3(0.3, 1, 0), 0.25));
        w.addConstraint(c, true);

        btHingeConstraint h1 = new btHingeConstraint(b3, b4, tr(0.6, 0, 0), tr(-0.6, 0, 0), false);
        h1.setUseFrameOffset(true);
        h1.setLimit(-0.3, 0.3);
        h1.setBreakingImpulseThreshold(0.9);
        h1.setParam(btTypedConstraint.BT_CONSTRAINT_STOP_CFM, 0.001);
        w.addConstraint(h1, true);

        btGeneric6DofConstraint d1 = new btGeneric6DofConstraint(b5, tr(0.5, 0, 0), false);
        d1.setLinearLowerLimit(new btVector3(-0.5, -0.2, 0));
        d1.setLinearUpperLimit(new btVector3(0.5, 0.3, 0));
        d1.setAngularLowerLimit(new btVector3(0, -1, 0));
        d1.setAngularUpperLimit(new btVector3(0, 1, 0));
        w.addConstraint(d1);

        btRigidBody[] bs = {b0, b1, b2, b3, b4, b5};
        btTypedConstraint[] cs = {h0, p, d, c, h1, d1};
        String tag = "joints" + mode;
        for (int i = 0; i < 240; i++) {
            R.reset();
            if (i == 120) {
                c.setMotorTarget(new btQuaternion(new btVector3(1, 0, 0.5), -0.4));
                h0.enableAngularMotor(true, -2, 0.8);
            }
            w.stepSimulation(1.0 / 60.0, 1, 1.0 / 60.0);
            for (int k = 0; k < 6; k++) {
                R.t(bs[k].getWorldTransform());
                R.v(bs[k].getLinearVelocity());
                R.v(bs[k].getAngularVelocity());
            }
            for (int k = 0; k < 6; k++) {
                R.d(cs[k].getAppliedImpulse());
                R.i(cs[k].isEnabled() ? 1 : 0);
            }
            R.d(h0.getHingeAngle());
            R.d(h1.getHingeAngle());
            for (int k = 0; k < 3; k++) R.d(d.getAngle(k));
            R.d(d.getRelativePivotPosition(0));
            // m_twistAngle is never written while the cone-twist motor is on (C++ leaves it
            // uninitialised), so the driver records 0 here instead of getTwistAngle().
            R.d(0.0);
            R.d(c.getSwingSpan1());
            R.v(jf.m_appliedForceBodyA);
            R.v(jf.m_appliedTorqueBodyA);
            R.v(jf.m_appliedForceBodyB);
            R.v(jf.m_appliedTorqueBodyB);
            R.end(tag, i);
        }
        check(tag, R);
        // sanity (independent of golden): the weak hinge breaks, the others stay enabled
        assertFalse(h1.isEnabled(), "h1 should have broken");
        assertTrue(d.isEnabled());
    }

    @Test
    void jointsDefaultMode() throws IOException {
        joints(0);
    }

    @Test
    void jointsRandomInterleaved() throws IOException {
        joints(1);
    }

    @Test
    void jointsScalarFrictionCaching() throws IOException {
        joints(2);
    }

    @Test
    void jointsNoWarmstartNoSplit() throws IOException {
        joints(3);
    }

    @Test
    void jointsCfmDampingFewIterations() throws IOException {
        joints(4);
    }

    @Test
    void jointsSplitThresholdWarmstartFactor() throws IOException {
        joints(5);
    }

    // ------------------------------------------------------------------ contacts

    private static void contacts(int mode) throws IOException {
        Rec R = new Rec();
        btDiscreteDynamicsWorld w = newWorld();
        applyMode(w.getSolverInfo(), mode);
        btBoxShape groundS = new btBoxShape(new btVector3(50, 1, 50));
        btBoxShape boxS = new btBoxShape(new btVector3(0.5, 0.5, 0.5));
        btBoxShape plankS = new btBoxShape(new btVector3(1.5, 0.1, 0.4));
        btSphereShape sphS = new btSphereShape(0.4);
        btRigidBody ground = mk(w, groundS, 0, tr(0, -1, 0));
        ground.setFriction(0.7);
        ground.setRestitution(0.2);
        ground.setRollingFriction(0.05);
        btRigidBody[] bs = new btRigidBody[9];
        bs[0] = mk(w, boxS, 1, tr(0, 0.5, 0));
        bs[1] = mk(w, boxS, 1, tr(0.1, 1.55, 0.05));
        btTransform t2 = tr(-0.1, 2.7, 0);
        t2.setRotation(new btQuaternion(new btVector3(0, 1, 0), 0.4));
        bs[2] = mk(w, boxS, 1.5, t2);
        bs[3] = mk(w, sphS, 2, tr(3, 0.4, 0));
        bs[3].setRollingFriction(0.2);
        bs[3].setLinearVelocity(new btVector3(2, 0, 1));
        bs[3].setAngularVelocity(new btVector3(0, 3, 0));
        bs[4] = mk(w, boxS, 1, tr(-3, 0.6, 1));
        bs[4].setAnisotropicFriction(
                new btVector3(1, 0.2, 0.5), btCollisionObject.CF_ANISOTROPIC_FRICTION);
        bs[4].setLinearVelocity(new btVector3(1.5, 0, 2));
        bs[5] = mk(w, sphS, 1, tr(-3, 0.5, -3));
        bs[5].setRollingFriction(0.1);
        bs[5].setAnisotropicFriction(
                new btVector3(0.3, 1, 1), btCollisionObject.CF_ANISOTROPIC_ROLLING_FRICTION);
        bs[5].setAngularVelocity(new btVector3(4, 0.5, -2));
        btTransform t6 = tr(5, 1.5, -2);
        t6.setRotation(new btQuaternion(new btVector3(1, 0.2, 0), 0.8));
        bs[6] = mk(w, plankS, 3, t6);
        bs[6].setFlags(bs[6].getFlags() | btRigidBody.BT_ENABLE_GYROPSCOPIC_FORCE);
        bs[6].setAngularVelocity(new btVector3(1, 6, 0.5));
        bs[6].setRestitution(0.6);
        bs[7] = mk(w, plankS, 0, tr(0, 0.6, 3));
        bs[7].setCollisionFlags(bs[7].getCollisionFlags() | btCollisionObject.CF_KINEMATIC_OBJECT);
        bs[7].setActivationState(btCollisionObject.DISABLE_DEACTIVATION);
        bs[8] = mk(w, sphS, 0.5, tr(0.3, 1.6, 3));
        bs[8].setFriction(0.3);
        String tag = "contacts" + mode;
        for (int i = 0; i < 300; i++) {
            R.reset();
            btTransform kt = tr(0.01 * i, 0.6, 3);
            kt.setRotation(new btQuaternion(new btVector3(0, 1, 0), 0.02 * i));
            bs[7].setWorldTransform(kt);
            bs[7].setInterpolationWorldTransform(kt);
            w.stepSimulation(1.0 / 60.0, 1, 1.0 / 60.0);
            for (int k = 0; k < 9; k++) {
                R.t(bs[k].getWorldTransform());
                R.v(bs[k].getLinearVelocity());
                R.v(bs[k].getAngularVelocity());
            }
            R.end(tag, i);
        }
        check(tag, R);
    }

    @Test
    void contactsDefaultMode() throws IOException {
        contacts(0);
    }

    @Test
    void contactsRandomInterleaved() throws IOException {
        contacts(1);
    }

    @Test
    void contactsScalarFrictionCaching() throws IOException {
        contacts(2);
    }

    @Test
    void contactsNoWarmstartNoSplit() throws IOException {
        contacts(3);
    }

    @Test
    void contactsCfmDampingFewIterations() throws IOException {
        contacts(4);
    }

    @Test
    void contactsSplitThresholdWarmstartFactor() throws IOException {
        contacts(5);
    }
}
