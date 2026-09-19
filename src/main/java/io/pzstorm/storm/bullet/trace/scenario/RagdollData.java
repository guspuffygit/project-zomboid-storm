package io.pzstorm.storm.bullet.trace.scenario;

import io.pzstorm.storm.bullet.trace.BulletBackend;
import io.pzstorm.storm.bullet.trace.SkeletonBoneTable;

/**
 * The ragdoll data the game uploads, and the skeleton buffers it streams per frame.
 *
 * <p>Script tables are {@code media/scripts/ragdolls/*.txt} (B42.20) in file order, packed exactly
 * as {@code RagdollScript.upload*} does. The bone hierarchy and T-pose are <b>synthetic</b>: the
 * game reads them from the {@code bob/bob_tpose} animation asset, which is binary model data; the
 * parent table follows the Biped naming ({@code SkeletonBone} ordinals) and the pose is a plausible
 * upright T-pose in model units.
 */
public final class RagdollData {

    private RagdollData() {}

    public static final int BONES = SkeletonBoneTable.COUNT;
    public static final int BODY_PARTS = 11;
    public static final int JOINTS = 10;

    /** {@code RagdollController.skeletonBuffer} / {@code BallisticsTarget.boneTransformData}. */
    public static final int SKELETON_FLOATS = 245;

    /** {@code RagdollController.rigidBodyBuffer}: 7 floats per body part. */
    public static final int RIGID_BODY_FLOATS = 77;

    /** ragdoll_constraints.txt: joint, type, partA, partB, axisA, axisB, offA, offB, limit. */
    static final float[][] CONSTRAINTS = {
        {
            5,
            4,
            5,
            6,
            0,
            1.5707964F,
            0,
            0,
            1.5707964F,
            0,
            0,
            -0.225F,
            0,
            0,
            0.185F,
            0,
            0,
            1.5707964F,
            0
        },
        {
            4,
            5,
            0,
            5,
            0,
            0,
            0.7853982F,
            0,
            0,
            0.7853982F,
            0.1F,
            -0.1F,
            0,
            0,
            0.175F,
            0,
            0.212F,
            0.724F,
            0.318F
        },
        {
            1,
            5,
            1,
            2,
            0,
            0,
            1.5707964F,
            0,
            0,
            1.5707964F,
            0,
            0.15F,
            0,
            0,
            -0.1F,
            0,
            0.7853982F,
            0.7853982F,
            1.077F
        },
        {7, 4, 7, 8, 0, 1.5707964F, 0, 0, 1.5707964F, 0, 0, 0.1F, 0, 0, -0.1F, 0, 0, 1.5707964F, 0},
        {8, 5, 1, 9, 0, 0, 0, 0, 0, 1.5707964F, 0.1F, 0.1F, 0, 0, -0.075F, 0, 1.0F, 1.0F, 2.402F},
        {
            0,
            4,
            0,
            1,
            0,
            1.5707964F,
            0,
            0,
            1.5707964F,
            0,
            0,
            0.1F,
            0,
            0,
            -0.1F,
            0,
            -0.565F,
            0.935F,
            1.571F
        },
        {
            2,
            5,
            0,
            3,
            0,
            0,
            2.3561945F,
            0,
            0,
            2.3561945F,
            -0.1F,
            -0.1F,
            0,
            0,
            0.175F,
            0,
            0.212F,
            0.741F,
            0.282F
        },
        {
            3,
            4,
            3,
            4,
            0,
            1.5707964F,
            0,
            0,
            1.5707964F,
            0,
            0,
            -0.225F,
            0,
            0,
            0.185F,
            0,
            0,
            1.5707964F,
            0
        },
        {
            6,
            5,
            1,
            7,
            0,
            0,
            3.1415927F,
            0,
            0,
            1.5707964F,
            -0.1F,
            0.1F,
            0,
            0,
            -0.075F,
            0,
            1.0F,
            1.0F,
            2.402F
        },
        {
            9,
            4,
            9,
            10,
            0,
            1.5707964F,
            0,
            0,
            1.5707964F,
            0,
            0,
            0.1F,
            0,
            0,
            -0.1F,
            0,
            0,
            1.5707964F,
            0
        },
    };

    /** ragdoll_bodypartinfo.txt: part, calcLength, radius, height, gap, shape, mass, offset. */
    static final float[][] BODY_PART_INFO = {
        {10, 0, 0.055F, 0.3F, 0.1F, 0, 0.02295F, 0, 0.1F, 0},
        {6, 0, 0.055F, 0.35F, 0.1F, 0, 0.0643F, 0, -0.1F, 0},
        {1, 1, 0.11F, 0.1F, 0.1F, 1, 0.3111F, 0, 0, 0},
        {4, 0, 0.055F, 0.35F, 0.1F, 0, 0.0643F, 0, -0.1F, 0},
        {7, 1, 0.055F, 0.1F, 0.1F, 0, 0.03075F, 0, 0, 0},
        {0, 1, 0.075F, 0.1F, 0.1F, 1, 0.1481F, 0, 0, 0},
        {3, 1, 0.055F, 0.1F, 0.1F, 0, 0.11125F, 0, 0, 0},
        {9, 1, 0.055F, 0.1F, 0.1F, 0, 0.03075F, 0, 0, 0},
        {2, 0, 0.1F, 0.1F, 0.1F, 1, 0.0823F, -0.125F, 0, 0},
        {8, 0, 0.055F, 0.3F, 0.1F, 0, 0.02295F, 0, 0.1F, 0},
        {5, 1, 0.055F, 0.1F, 0.1F, 0, 0.11125F, 0, 0, 0},
    };

    /** ragdoll_bodydynamics.txt part order; the other seven values are per-part below. */
    static final int[] DYNAMICS_PARTS = {7, 0, 1, 5, 4, 9, 2, 8, 10, 6, 3};

    /** ragdoll_anchors.txt: bone, bodyPart, reverse, original, enabled. */
    static final float[][] ANCHORS = {
        {17, 10, 0, 0, 0}, {18, 10, 0, 0, 0}, {7, 1, 0, 0, 0}, {19, 1, 0, 0, 0},
        {0, 0, 0, 0, 0}, {25, 6, 1, 0, 1}, {21, 4, 1, 0, 1}, {2, 0, 0, 0, 1},
        {3, 1, 0, 0, 0}, {26, 6, 0, 0, 0}, {6, 2, 0, 0, 0}, {13, 1, 0, 0, 0},
        {11, 8, 0, 0, 0}, {1, 0, 0, 0, 0}, {32, 10, 0, 0, 0}, {5, 2, 0, 0, 0},
        {28, 1, 0, 0, 0}, {9, 8, 0, 0, 1}, {8, 7, 0, 0, 1}, {23, 4, 0, 0, 0},
        {14, 9, 0, 0, 1}, {10, 8, 0, 0, 0}, {27, 6, 0, 0, 0}, {4, 1, 0, 0, 1},
        {31, 1, 0, 0, 0}, {15, 10, 0, 0, 1}, {22, 4, 0, 0, 0}, {30, 1, 0, 0, 0},
        {24, 5, 1, 0, 1}, {29, 1, 0, 0, 0}, {12, 8, 0, 0, 0}, {16, 0, 0, 0, 0},
        {20, 3, 1, 0, 1}, {33, 8, 0, 0, 0},
    };

    /** Synthetic Biped parent table by {@code SkeletonBone} ordinal; Dummy01 is the root. */
    static final int[] PARENT = {
        -1, // Dummy01
        0, // Bip01
        1, // Pelvis
        2, // Spine
        3, // Spine1
        4, // Neck
        5, // Head
        5, // L_Clavicle
        7, // L_UpperArm
        8, // L_Forearm
        9, // L_Hand
        10, // L_Finger0
        10, // L_Finger1
        5, // R_Clavicle
        13, // R_UpperArm
        14, // R_Forearm
        15, // R_Hand
        16, // R_Finger0
        16, // R_Finger1
        4, // BackPack
        2, // L_Thigh
        20, // L_Calf
        21, // L_Foot
        22, // L_Toe0
        2, // R_Thigh
        24, // R_Calf
        25, // R_Foot
        26, // R_Toe0
        2, // DressFront
        28, // DressFront02
        2, // DressBack
        30, // DressBack02
        16, // Prop1
        10, // Prop2
        1, // Translation_Data
    };

    /** Synthetic local bone offsets from the parent (model units, Biped: +x along the bone). */
    static final float[][] LOCAL_POS = {
        {0, 0, 0},
        {0, 0.95F, 0},
        {0, 0, 0},
        {0.1F, 0, 0},
        {0.15F, 0, 0},
        {0.2F, 0, 0},
        {0.08F, 0, 0},
        {0.02F, 0, 0.03F},
        {0.14F, 0, 0},
        {0.28F, 0, 0},
        {0.25F, 0, 0},
        {0.06F, 0.02F, 0},
        {0.08F, 0, 0},
        {0.02F, 0, -0.03F},
        {0.14F, 0, 0},
        {0.28F, 0, 0},
        {0.25F, 0, 0},
        {0.06F, 0.02F, 0},
        {0.08F, 0, 0},
        {0.1F, -0.15F, 0},
        {-0.05F, 0, 0.1F},
        {0.45F, 0, 0},
        {0.42F, 0, 0},
        {0.1F, 0.12F, 0},
        {-0.05F, 0, -0.1F},
        {0.45F, 0, 0},
        {0.42F, 0, 0},
        {0.1F, 0.12F, 0},
        {0, 0.1F, 0.08F},
        {0.3F, 0, 0},
        {0, -0.1F, 0.08F},
        {0.3F, 0, 0},
        {0.1F, 0, 0},
        {0.1F, 0, 0},
        {0, 0, 0},
    };

    /** Synthetic local rotation angle (radians, about z) per bone: legs/arms point down/out. */
    static final float[] LOCAL_ROT_Z = {
        0,
        0,
        -1.5707964F,
        0,
        0,
        0,
        0,
        3.1415927F,
        0,
        0,
        0,
        0,
        0,
        3.1415927F,
        0,
        0,
        0,
        0,
        0,
        0,
        3.1415927F,
        0,
        0,
        -1.5707964F,
        3.1415927F,
        0,
        0,
        -1.5707964F,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
    };

    /** {@code RagdollScript.toBullet(liveUpdate)}: bodyDynamics, constraints, anchors, info. */
    public static void uploadScripts(BulletBackend b, boolean liveUpdate) {
        uploadBodyDynamics(b, liveUpdate, 1.0F);
        uploadConstraints(b, liveUpdate);
        uploadAnchors(b, liveUpdate);
        uploadBodyPartInfo(b, liveUpdate);
    }

    /** {@code RagdollScript.uploadBodyDynamics}: float[8 * n]; friction scaled for live tuning. */
    public static void uploadBodyDynamics(BulletBackend b, boolean liveUpdate, float frictionMul) {
        float[] p = new float[8 * DYNAMICS_PARTS.length];
        int i = 0;
        for (int part : DYNAMICS_PARTS) {
            p[i++] = part;
            p[i++] = 0.05F;
            p[i++] = part == 7 || part == 9 ? 0.989F : 0.85F;
            p[i++] = 0.8F;
            p[i++] = 1.6F;
            p[i++] = 2.5F;
            p[i++] = 1.5F * frictionMul;
            p[i++] = 0.5F;
        }
        b.defineRagdollBodyDynamics(p, liveUpdate);
    }

    /** {@code RagdollScript.uploadConstraints}: float[22 * n], limitExtended = 0. */
    public static void uploadConstraints(BulletBackend b, boolean liveUpdate) {
        float[] p = new float[22 * CONSTRAINTS.length];
        int i = 0;
        for (float[] c : CONSTRAINTS) {
            System.arraycopy(c, 0, p, i, c.length);
            i += 22;
        }
        b.defineRagdollConstraints(p, liveUpdate);
    }

    /** {@code RagdollScript.uploadAnchors}: float[5 * n]. */
    public static void uploadAnchors(BulletBackend b, boolean liveUpdate) {
        float[] p = new float[5 * ANCHORS.length];
        int i = 0;
        for (float[] a : ANCHORS) {
            System.arraycopy(a, 0, p, i, 5);
            i += 5;
        }
        b.defineRagdollAnchors(p, liveUpdate);
    }

    /** {@code RagdollScript.uploadBodyPartInfo}: float[10 * n]. */
    public static void uploadBodyPartInfo(BulletBackend b, boolean liveUpdate) {
        float[] p = new float[10 * BODY_PART_INFO.length];
        int i = 0;
        for (float[] a : BODY_PART_INFO) {
            System.arraycopy(a, 0, p, i, 10);
            i += 10;
        }
        b.defineRagdollBodyPartInfo(p, liveUpdate);
    }

    /**
     * {@code RagdollBuilder.Initialize} (once per JVM, on the first chunk that adds ragdoll
     * controllers): skeleton parents, ballistics shape scale, T-pose, vehicle body dynamics.
     */
    public static void initializeBuilder(ScenarioContext ctx) {
        if (ctx.ragdollBuilderInitialized) {
            return;
        }
        ctx.ragdollBuilderInitialized = true;
        BulletBackend b = ctx.bullet;
        int[] hierarchy = new int[BONES * 7];
        System.arraycopy(PARENT, 0, hierarchy, 0, BONES);
        b.initializeRagdollSkeleton(BONES, hierarchy);
        b.setBallisticsTargetAdjustingShapeScale(0.35F, 0.4F, 0.35F);
        float[] q = localRotation();
        b.initializeRagdollPose(BONES, tPose(), q[0], q[1], q[2], q[3]);
    }

    /** {@code RagdollBuilder.setMass}: {@code setRagdollMass} only when the value changes. */
    public static void setMass(ScenarioContext ctx, float mass) {
        if (ctx.ragdollMass != mass) {
            ctx.bullet.setRagdollMass(mass);
        }
        ctx.ragdollMass = mass;
    }

    /** {@code RagdollBuilder.getBoneTransforms}: 7 floats per bone, -pos * 1.5 then rotation. */
    public static float[] tPose() {
        float[] f = new float[SKELETON_FLOATS];
        for (int i = 0; i < BONES; i++) {
            float[] r = axisAngle(0, 0, 1, LOCAL_ROT_Z[i]);
            int o = i * 7;
            f[o] = -LOCAL_POS[i][0] * 1.5F;
            f[o + 1] = -LOCAL_POS[i][1] * 1.5F;
            f[o + 2] = -LOCAL_POS[i][2] * 1.5F;
            f[o + 3] = r[0];
            f[o + 4] = r[1];
            f[o + 5] = r[2];
            f[o + 6] = r[3];
        }
        return f;
    }

    /**
     * An animated pose ({@code RagdollController.getBoneTransformsFromAnimation}): the T-pose with
     * a walk-cycle-like swing of phase {@code t} on the limbs.
     */
    public static float[] animatedPose(float t, float amplitude) {
        float[] f = tPose();
        int[] swing = {8, 9, 14, 15, 20, 21, 24, 25, 3, 6};
        for (int k = 0; k < swing.length; k++) {
            int bone = swing[k];
            float a = LOCAL_ROT_Z[bone] + amplitude * (float) Math.sin(t + k * 1.3F);
            float[] r = axisAngle(0, 0, 1, a);
            int o = bone * 7;
            f[o + 3] = r[0];
            f[o + 4] = r[1];
            f[o + 5] = r[2];
            f[o + 6] = r[3];
        }
        return f;
    }

    /**
     * {@code getBoneTransformVelocitiesFromAnimation}: (current - previous) / deltaT per float,
     * positions negated and scaled by 1.5 like the transforms; identity when deltaT is 0.
     */
    public static float[] velocities(float[] prev, float[] cur, float deltaT) {
        float[] f = new float[SKELETON_FLOATS];
        for (int i = 0; i < BONES; i++) {
            int o = i * 7;
            if (deltaT <= 0) {
                f[o + 6] = 1.0F;
                continue;
            }
            float k = 1.0F / deltaT;
            for (int j = 0; j < 7; j++) {
                f[o + j] = (cur[o + j] - prev[o + j]) * k;
            }
        }
        return f;
    }

    /** {@code PZMath.setFromAxisAngle}: (axis * sin(a/2), cos(a/2)) as xyzw. */
    public static float[] axisAngle(float x, float y, float z, float angle) {
        float s = (float) Math.sin(angle * 0.5F);
        return new float[] {x * s, y * s, z * s, (float) Math.cos(angle * 0.5F)};
    }

    /** lwjgl {@code Quaternion.mul(left, right)}: Hamilton product, xyzw. */
    public static float[] mul(float[] l, float[] r) {
        return new float[] {
            l[0] * r[3] + l[3] * r[0] + l[1] * r[2] - l[2] * r[1],
            l[1] * r[3] + l[3] * r[1] + l[2] * r[0] - l[0] * r[2],
            l[2] * r[3] + l[3] * r[2] + l[0] * r[1] - l[1] * r[0],
            l[3] * r[3] - l[0] * r[0] - l[1] * r[1] - l[2] * r[2]
        };
    }

    /** {@code RagdollController.getRagdollLocalRotation}: Qy(-PI) * Qz(-PI/2). */
    public static float[] localRotation() {
        return mul(
                axisAngle(0, 1, 0, (float) -Math.PI), axisAngle(0, 0, 1, (float) (-Math.PI / 2)));
    }

    /** {@code calculateRagdollWorldRotation}: Qy(-forward). */
    public static float[] worldRotation(float forwardAngle) {
        return axisAngle(0, 1, 0, -forwardAngle);
    }

    /**
     * {@code BaseVehicle}'s vehicle-contact dynamics: [BODYPART_COUNT, defaults of the first
     * bodydynamics entry, friction = vehicleCollisionFriction 0.4].
     */
    public static float[] vehicleBodyDynamics() {
        return new float[] {BODY_PARTS, 0.05F, 0.989F, 0.8F, 1.6F, 2.5F, 0.4F, 0.5F};
    }
}
