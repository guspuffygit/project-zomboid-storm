package io.pzstorm.storm.bullet.pz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import io.pzstorm.storm.bullet.BulletUpcalls;
import io.pzstorm.storm.bullet.collision.broadphase.btDbvtBroadphase;
import io.pzstorm.storm.bullet.collision.dispatch.btCollisionDispatcher;
import io.pzstorm.storm.bullet.collision.dispatch.btDefaultCollisionConfiguration;
import io.pzstorm.storm.bullet.dynamics.btDiscreteDynamicsWorld;
import io.pzstorm.storm.bullet.dynamics.btRigidBody;
import io.pzstorm.storm.bullet.dynamics.constraintsolver.btConeTwistConstraint;
import io.pzstorm.storm.bullet.dynamics.constraintsolver.btHingeConstraint;
import io.pzstorm.storm.bullet.dynamics.constraintsolver.btSequentialImpulseConstraintSolver;
import io.pzstorm.storm.bullet.linearmath.btGlobals;
import io.pzstorm.storm.bullet.linearmath.btIDebugDraw;
import io.pzstorm.storm.bullet.linearmath.btQuaternion;
import io.pzstorm.storm.bullet.linearmath.btTransform;
import io.pzstorm.storm.bullet.linearmath.btVector3;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * PZ ragdoll glue: skeleton init / pose, RagdollBuilder bodies + constraints, PZRagdoll simulate /
 * bone-transform parsing / debug render, with a stub {@link BulletUpcalls.Target}.
 */
class PZRagdollTest implements UnitTest {

    private static final int BONES = SkeletonBone.COUNT;
    private static final float BONE_LEN = 0.1f;

    private final List<String> logs = new ArrayList<>();

    /** Chain hierarchy: bone i hangs off bone i-1. */
    private static int[] parents() {
        int[] p = new int[BONES];
        for (int i = 0; i < BONES; i++) {
            p[i] = i - 1;
        }
        return p;
    }

    /** 7 floats per bone (x, y, z, qx, qy, qz, qw); bone i &gt; 0 sits BONE_LEN up its parent. */
    private static float[] bindData(float rootX) {
        float[] d = new float[BONES * 7];
        for (int i = 0; i < BONES; i++) {
            d[7 * i] = i == 0 ? rootX : 0f;
            d[7 * i + 1] = i == 0 ? 0f : BONE_LEN;
            d[7 * i + 6] = 1f;
        }
        return d;
    }

    @BeforeEach
    void setUp() {
        BulletUpcalls.target =
                new BulletUpcalls.Target() {
                    @Override
                    public boolean updatePhysicsForLevelIfNeeded(int wx, int wy, int level) {
                        return false;
                    }

                    @Override
                    public void onVehicleConstraintImpulse(int c, int a, int b, float i) {}

                    @Override
                    public void nativeLog(String a, String b, String c) {
                        logs.add(a + " " + b + " " + c);
                    }

                    @Override
                    public String getBoneName(int ordinal) {
                        return "Bone" + ordinal;
                    }

                    @Override
                    public int getBoneOrdinal(String name) {
                        return Integer.parseInt(name.substring(4));
                    }
                };
        RagdollBuilder.resetInstanceForTests();
        btGlobals.gDynamicsWorld = null;

        RagdollBuilder b = RagdollBuilder.Instance();
        PZRagdollScript s = b.m_script;
        for (int i = 0; i < Ragdoll.BODY_PART_COUNT; i++) {
            BodyPartInfo info = s.m_bodyPartInfo.get(i);
            info.part = i;
            info.calculateLength = true;
            info.radius = 0.03f;
            info.gap = 0.01f;
            info.shape = 0;
            info.mass = 1.0 / 11.0;
            RagdollBodyDynamics d = s.m_bodyDynamics.get(i);
            d.part = i;
            d.linearDamping = 0.1f;
            d.angularDamping = 0.2f;
            d.deactivationTime = 0.8f;
            d.linearSleepingThreshold = 0.5f;
            d.angularSleepingThreshold = 0.5f;
            d.friction = 0.5f;
            d.rollingFriction = 0.1f;
        }
        for (int j = 0; j < Ragdoll.JOINT_COUNT; j++) {
            RagdollConstraint c = s.m_constraints.get(j);
            c.joint = j;
            c.constraintType = (j % 2 == 0) ? 4 : 5;
            c.constraintPartA = j;
            c.constraintPartB = j + 1;
            c.constraintPositionOffsetA.setValue(0, 0.05, 0);
            c.constraintPositionOffsetB.setValue(0, -0.05, 0);
            // hinge: (low, high); cone twist: (swingSpan1, swingSpan2, twistSpan).
            if (c.constraintType == 4) {
                c.constraintLimit.setValue(-0.5, 0.5, 0.0);
            } else {
                c.constraintLimit.setValue(0.5, 0.4, 0.3);
            }
        }
        for (int i = 0; i < BONES; i++) {
            RagdollAnchor a = s.m_anchors.get(i);
            a.bone = i;
            a.bodyPart = 0;
            a.enabled = false;
        }
        for (int part = 0; part < Ragdoll.BODY_PART_COUNT; part++) {
            RagdollAnchor a = s.m_anchors.get(Ragdoll.rigidBodyToBone[part]);
            a.bodyPart = part;
            a.original = true;
            a.enabled = true;
        }
        b.initialize();
        b.initializeSkeletonHiearachy(BONES, parents());
        b.initializeSkeleton(BONES, bindData(0f), 0f, 0f, 0f, 1f);
    }

    @AfterEach
    void tearDown() {
        btGlobals.gDynamicsWorld = null;
        RagdollBuilder.resetInstanceForTests();
        BulletUpcalls.target = BulletUpcalls.GameTarget.INSTANCE;
    }

    private static btDiscreteDynamicsWorld newWorld() {
        btDefaultCollisionConfiguration cfg = new btDefaultCollisionConfiguration();
        btCollisionDispatcher disp = new btCollisionDispatcher(cfg);
        btDbvtBroadphase bp = new btDbvtBroadphase();
        btSequentialImpulseConstraintSolver solver = new btSequentialImpulseConstraintSolver();
        btDiscreteDynamicsWorld w = new btDiscreteDynamicsWorld(disp, bp, solver, cfg);
        w.setGravity(new btVector3(0, -10, 0));
        return w;
    }

    // ------------------------------------------------------------------ skeleton

    @Test
    void skeletonInitAndPose() {
        Skeleton sk = RagdollBuilder.Instance().m_defaultSkeleton;
        assertNotNull(sk);
        assertEquals(BONES, sk.m_boneCount);
        assertEquals(-1, sk.m_bones.get(0).parent);
        assertEquals(BONES - 2, sk.m_bones.get(BONES - 1).parent);

        SkeletonPose pose = RagdollBuilder.Instance().createSkeletonPose();
        pose.update();
        double y = 0.0;
        for (int i = 0; i < BONES; i++) {
            btTransform abs = pose.getBonePoseAbsolute(i);
            assertEquals(y, abs.m_origin.y, 1e-12, "bone " + i);
            assertEquals(0.0, abs.m_origin.x, 1e-12);
            y += BONE_LEN;
        }

        // set/get absolute round-trips through the bind-relative local transform.
        btTransform t = pose.getBonePoseAbsolute(5);
        t.m_origin.x += 0.25;
        pose.setBonePoseAbsolute(5, t);
        assertEquals(0.25, pose.m_localTransforms.get(5).m_origin.x, 1e-12);
        assertEquals(t.m_origin.x, pose.getBonePoseAbsolute(5).m_origin.x, 1e-12);

        pose.resetBoneTransforms();
        assertEquals(0.0, pose.m_localTransforms.get(5).m_origin.x);
    }

    @Test
    void getBoneLengthUsesLocalTransforms() {
        PZRagdoll r = new PZRagdoll(3);
        // Local (animation) transforms are identity: every pair has length 0.
        assertEquals(0.0, r.getBoneLength(8));
        BonePair pair = RagdollBuilder.Instance().m_bonePairs.get(8);
        r.m_skeletonPose.m_localTransforms.get(pair.first).m_origin.setValue(3, 0, 0);
        r.m_skeletonPose.m_localTransforms.get(pair.second).m_origin.setValue(0, 4, 0);
        assertEquals(5.0, r.getBoneLength(8));
    }

    // ------------------------------------------------------------------ builder

    @Test
    void builderCreatesBodiesAndConstraints() {
        PZRagdoll r = new PZRagdoll(7);
        assertEquals(7, r.getId());
        Ragdoll rd = r.m_ragdoll;
        List<BonePair> pairs = RagdollBuilder.Instance().m_bonePairs;
        for (int i = 0; i < Ragdoll.BODY_PART_COUNT; i++) {
            btRigidBody body = rd.getRigidBody(i);
            assertNotNull(body, "part " + i);
            assertEquals(1.0 / (70.0 * (1.0 / 11.0)), body.getInvMass(), 1e-12);
            // Body centre = midpoint of its bone pair (chain along +y, bone k at y = k*len).
            BonePair p = pairs.get(i);
            double expect = (p.first + p.second) * 0.5 * BONE_LEN;
            assertEquals(expect, body.m_worldTransform.m_origin.y, 1e-6, "part " + i);
            assertEquals(0.5, body.m_friction, 0.0);
            assertEquals((double) 0.8f, body.m_deactivationTime, 0.0);
        }
        for (int j = 0; j < Ragdoll.JOINT_COUNT; j++) {
            Object c = rd.getJointConstraint(j);
            assertNotNull(c);
            if (j % 2 == 0) {
                assertTrue(c instanceof btHingeConstraint, "joint " + j);
            } else {
                assertTrue(c instanceof btConeTwistConstraint, "joint " + j);
            }
        }
        // Parts 1 and 2 share bone pair (5, 6) and so the same body centre.
        assertEquals(
                rd.getRigidBody(1).m_worldTransform.m_origin.y,
                rd.getRigidBody(2).m_worldTransform.m_origin.y);
        assertNotSame(rd.getRigidBody(1), rd.getRigidBody(2));
    }

    @Test
    void updateSetsRagdollTransform() {
        PZRagdoll r = new PZRagdoll(1);
        float s = (float) Math.sqrt(0.5);
        r.update(1f, 2f, 3f, 0f, s, 0f, s);
        btTransform t = r.m_ragdoll.m_transform;
        assertEquals(1.0, t.m_origin.x);
        assertEquals(2.0, t.m_origin.y);
        assertEquals(3.0, t.m_origin.z);
        assertEquals(0.0, t.m_origin.w);
        assertEquals(1.0, r.m_position.x);
        btTransform expect = new btTransform();
        expect.m_basis.setRotation(new btQuaternion((double) 0f, (double) s, 0.0, (double) s));
        for (int i = 0; i < 3; i++) {
            assertEquals(expect.m_basis.getRow(i).x, t.m_basis.getRow(i).x, 0.0);
            assertEquals(expect.m_basis.getRow(i).y, t.m_basis.getRow(i).y, 0.0);
            assertEquals(expect.m_basis.getRow(i).z, t.m_basis.getRow(i).z, 0.0);
        }
    }

    // ------------------------------------------------------------------ bone transforms

    @Test
    void parseBoneTransformsChainsParentsAndAppliesLocalTransform() {
        PZRagdoll r = new PZRagdoll(1);
        List<btTransform> local = new ArrayList<>();
        List<btTransform> abs = new ArrayList<>();
        r.parseBoneTransforms(BONES, bindData(0.5f), local, abs);
        assertEquals(BONES, local.size());
        assertEquals(BONES, abs.size());
        double y = 0.0;
        for (int i = 0; i < BONES; i++) {
            assertEquals(0.5, abs.get(i).m_origin.x, 0.0, "bone " + i);
            assertEquals(y, abs.get(i).m_origin.y, 1e-12, "bone " + i);
            y += BONE_LEN;
        }
        // local[0] *= m_localTransform: a 180-degree turn about y flips the chain's x-offset of
        // later bones' parents only through bone 0's basis.
        r.setLocalTransformRotation(0f, 1f, 0f, 0f);
        local.clear();
        abs.clear();
        float[] d = bindData(0f);
        d[7] = 1f; // bone 1 offset +x from bone 0
        r.parseBoneTransforms(BONES, d, local, abs);
        assertEquals(-1.0, abs.get(1).m_origin.x, 1e-12);
        assertEquals(-1.0, local.get(0).m_basis.getRow(0).x, 1e-12);
    }

    @Test
    void updateSkeletonBoneTransformsMovesBodiesAndLogs() {
        PZRagdoll r = new PZRagdoll(1);
        r.updateSkeletonBoneTransforms(BONES, bindData(2f));
        for (int i = 0; i < Ragdoll.BODY_PART_COUNT; i++) {
            assertEquals(2.0, r.m_ragdoll.getRigidBody(i).m_worldTransform.m_origin.x, 1e-9);
        }
        assertTrue(
                logs.stream()
                        .anyMatch(
                                l ->
                                        l.contains(
                                                "(PZRagdoll.cpp:129) > PZRagdoll::updateSkeletonBoneTransforms"
                                                        + " getBoneLength(BODYPART_LEFT_LOWER_ARM):"
                                                        + " 0.000000")),
                String.join("\n", logs));
        assertTrue(
                logs.stream()
                        .anyMatch(
                                l ->
                                        l.contains(
                                                "updateSkeletonBoneTransforms(in_numberOfBones=35")));
    }

    // ------------------------------------------------------------------ simulation

    @Test
    void simulateInWorld() {
        btDiscreteDynamicsWorld w = newWorld();
        btGlobals.gDynamicsWorld = w;
        PZRagdoll r = new PZRagdoll(1);
        r.addToWorld(new btTransform(btTransform.getIdentity()));
        r.setActive(true);
        assertTrue(r.m_active);
        assertEquals(Ragdoll.JOINT_COUNT, w.getNumConstraints());
        assertEquals(1, r.getSimulationState());

        double y0 = r.m_ragdoll.getRigidBody(0).m_worldTransform.m_origin.y;
        for (int i = 0; i < 120; i++) {
            w.stepSimulation(1.0 / 60.0, 1, 1.0 / 60.0);
        }
        assertTrue(r.m_ragdoll.getRigidBody(0).m_worldTransform.m_origin.y < y0);

        float[] skel = new float[BONES * 7];
        float[] rb = new float[Ragdoll.BODY_PART_COUNT * 7];
        assertEquals(BONES, r.simulate(skel, rb));
        for (float f : skel) {
            assertFalse(Float.isNaN(f));
        }
        // Rigid body data: origin then rotation quaternion per body.
        for (int i = 0; i < Ragdoll.BODY_PART_COUNT; i++) {
            btRigidBody body = r.m_ragdoll.getRigidBody(i);
            assertEquals((float) body.m_worldTransform.m_origin.y, rb[7 * i + 1]);
            double qn =
                    rb[7 * i + 3] * rb[7 * i + 3]
                            + rb[7 * i + 4] * rb[7 * i + 4]
                            + rb[7 * i + 5] * rb[7 * i + 5]
                            + rb[7 * i + 6] * rb[7 * i + 6];
            assertEquals(1.0, qn, 1e-5);
        }
        // Root bone quaternion is unit, and the root local transform keeps only its rotation.
        double q0 = skel[3] * skel[3] + skel[4] * skel[4] + skel[5] * skel[5] + skel[6] * skel[6];
        assertEquals(1.0, q0, 1e-5);
        assertEquals(0.0, r.m_skeletonPose.m_localTransforms.get(0).m_origin.x);
        assertEquals(0.0, r.m_skeletonPose.m_localTransforms.get(0).m_origin.y);

        r.removeFromWorld();
        assertFalse(r.m_active);
        assertEquals(0, w.getNumConstraints());
    }

    @Test
    void previousBoneTransformsImpartVelocity() {
        btDiscreteDynamicsWorld w = newWorld();
        btGlobals.gDynamicsWorld = w;
        PZRagdoll r = new PZRagdoll(1);
        r.setActive(true);
        r.updateSkeletonBoneTransforms(BONES, bindData(0f));
        float dt = 0.5f;
        // Previous frame was 0.01 further -x: every body moves +x by 0.01 over dt.
        r.updateRagdollSkeletonPreviousBoneTransforms(BONES, dt, bindData(-0.01f));
        for (int i = 0; i < Ragdoll.BODY_PART_COUNT; i++) {
            btRigidBody body = r.m_ragdoll.getRigidBody(i);
            double expect = (double) 0.01f / dt * body.getInvMass();
            // delta is expressed in the body frame (O^-1 * P^-1 * A * O): only its length is
            // frame independent.
            assertEquals(expect, body.m_linearVelocity.length(), 1e-6, "part " + i);
            assertEquals(0.0, body.m_angularVelocity.length(), 1e-9);
        }
        r.setActive(false);
    }

    @Test
    void setMassUpdatesAllBodies() {
        PZRagdoll r = new PZRagdoll(1);
        r.setMass(2f);
        assertEquals(2.0, r.m_mass);
        for (int i = 0; i < Ragdoll.BODY_PART_COUNT; i++) {
            assertEquals(0.5, r.m_ragdoll.getRigidBody(i).getInvMass());
        }
    }

    // ------------------------------------------------------------------ debug render

    private static final class CountingDrawer extends btIDebugDraw {
        int lines;
        final List<btVector3> colors = new ArrayList<>();

        @Override
        public void drawLine(btVector3 from, btVector3 to, btVector3 color) {
            lines++;
            colors.add(new btVector3(color));
        }

        @Override
        public void drawContactPoint(
                btVector3 p, btVector3 n, double d, int lifeTime, btVector3 color) {}

        @Override
        public void reportErrorWarning(String warningString) {}

        @Override
        public void draw3dText(btVector3 location, String textString) {}

        @Override
        public void setDebugMode(int debugMode) {}

        @Override
        public int getDebugMode() {
            return DBG_DrawWireframe;
        }
    }

    @Test
    void debugRenderSkeletonDrawsOneLinePerParentedBone() {
        btDiscreteDynamicsWorld w = newWorld();
        CountingDrawer d = new CountingDrawer();
        w.setDebugDrawer(d);
        PZRagdoll r = new PZRagdoll(1);
        r.debugRenderSkeleton(w, PZbtVector3.Orange);
        assertEquals(BONES - 1, d.lines);
        d.lines = 0;
        r.m_drawSingleBone = true;
        r.m_singleBoneIndex = 4;
        r.debugRenderSkeleton(w, PZbtVector3.Orange);
        assertEquals(1, d.lines);
        d.lines = 0;
        r.m_singleBoneIndex = 0; // root has no parent
        r.debugRenderSkeleton(w, PZbtVector3.Orange);
        assertEquals(0, d.lines);
    }

    @Test
    void debugRenderBodyPartsColoursByActivationState() {
        btDiscreteDynamicsWorld w = newWorld();
        CountingDrawer d = new CountingDrawer();
        w.setDebugDrawer(d);
        PZRagdoll r = new PZRagdoll(1);
        r.m_drawOnlyHighlightedBodyPart = true;
        r.m_highlightedBodyPart = 3;
        r.m_ragdoll.getRigidBody(3).m_activationState1 = 5;
        r.debugRenderBodyParts(w, PZbtVector3.White, true);
        assertTrue(d.lines > 0);
        // debugDrawObject also draws the transform axes in fixed colours; the shape is purple.
        assertTrue(
                d.colors.stream()
                        .anyMatch(
                                c ->
                                        c.x == PZbtVector3.Purple.x
                                                && c.y == PZbtVector3.Purple.y
                                                && c.z == PZbtVector3.Purple.z));
        assertFalse(
                d.colors.stream()
                        .anyMatch(
                                c ->
                                        c.x == PZbtVector3.White.x
                                                && c.y == PZbtVector3.White.y
                                                && c.z == PZbtVector3.White.z));
        int one = d.lines;
        d.lines = 0;
        d.colors.clear();
        r.m_drawOnlyHighlightedBodyPart = false;
        r.debugRenderBodyParts(w, PZbtVector3.White, false);
        assertEquals(one * Ragdoll.BODY_PART_COUNT, d.lines);
    }

    @Test
    void debugRenderHighlightedBodyPartsClearsFlag() {
        btDiscreteDynamicsWorld w = newWorld();
        CountingDrawer d = new CountingDrawer();
        w.setDebugDrawer(d);
        PZRagdoll r = new PZRagdoll(1);
        BulletObject bo = new BulletObject(null, 0.0, 0.0, 0.0, BulletObjectType.RagdollPart);
        bo.flag40 = true;
        bo.color.set(PZbtVector3.Blue);
        r.m_ragdoll.getRigidBody(2).m_userObjectPointer = bo;
        r.debugRenderHighlightedBodyParts(w);
        assertTrue(d.lines > 0);
        assertFalse(bo.flag40);
        d.lines = 0;
        r.debugRenderHighlightedBodyParts(w);
        assertEquals(0, d.lines);
    }
}
