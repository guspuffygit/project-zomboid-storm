// Port of PZ Java_zombie_core_physics_Bullet_* ragdoll JNI entries (PZBullet.cpp) plus the static
// helpers updateRagdollBodyDynamics(int, RagdollBodyDynamics) @00157730 and
// updateRagdollBodyParts(bool) @00157a50.
package io.pzstorm.storm.bullet.pz.jni;

import io.pzstorm.storm.bullet.linearmath.btQuaternion;
import io.pzstorm.storm.bullet.linearmath.btTransform;
import io.pzstorm.storm.bullet.linearmath.btVector3;
import io.pzstorm.storm.bullet.pz.PZBallisticsTarget;
import io.pzstorm.storm.bullet.pz.PZDebugLog;
import io.pzstorm.storm.bullet.pz.PZDebugType;
import io.pzstorm.storm.bullet.pz.PZRagdoll;
import io.pzstorm.storm.bullet.pz.RagdollBodyDynamics;
import io.pzstorm.storm.bullet.pz.RagdollBuilder;
import io.pzstorm.storm.bullet.pz.WorldSimulation;
import java.util.ArrayList;

/**
 * Bodies of the ragdoll JNI natives, same names and Java signatures as zombie.core.physics.Bullet.
 *
 * <p>Deviations forced by Java: where the C++ dereferences a null {@code WorldSimulation::instance}
 * without a check, Java raises a NullPointerException. Where the C++ raises a Java exception and
 * then returns a value, the side effects (logs, once-flags) happen first and the same exception
 * class/message is thrown; the C++ return value is never seen by the Java caller. The "out of
 * memory" branches (GetPrimitiveArrayCritical returning null) cannot occur in Java.
 */
public final class RagdollNatives {

    private RagdollNatives() {}

    private static final String LOC = "/usr/src/pz/pzbullet/PZBullet.cpp:";
    private static final String NULL_WS = "WorldSimulation::instance is null";

    private static boolean __onceSimulateRagdoll;
    private static boolean __onceSimulateRagdollWithRigidBodyOutput;
    private static boolean __onceUpdateSkeletonFromNetworkPhysics;
    private static boolean __onceUpdateRagdoll;
    private static boolean __onceUpdateRagdollSkeleton;
    private static boolean __onceUpdateRagdollSkeletonVelocities;

    private static void log(int line, String msg) {
        PZDebugLog.instance().log("PZBullet", PZDebugType.Debug, LOC + line, msg);
    }

    private static void trace(int line, String msg) {
        PZDebugLog.instance().log("PZBullet", PZDebugType.Trace, LOC + line, msg);
    }

    private static PZRagdoll find(int id) {
        return WorldSimulation.instance.m_ragdolls.get(id);
    }

    public static void initializeRagdollPose(
            int count, float[] positions, float qx, float qy, float qz, float qw) {
        if (positions != null) {
            RagdollBuilder.Instance().initializeSkeleton(count, positions, qx, qy, qz, qw);
        }
    }

    public static void initializeRagdollSkeleton(int count, int[] hierarchy) {
        log(1349, "PZBullet::Java_zombie_core_physics_Bullet_initializeRagdollSkeleton");
        RagdollBuilder.Instance().initializeSkeletonHiearachy(count, hierarchy);
    }

    public static void addRagdoll(
            int id, float x, float y, float z, float qx, float qy, float qz, float qw) {
        WorldSimulation ws = WorldSimulation.instance;
        if (ws == null) {
            throw new NullPointerException(NULL_WS);
        }
        float px = x - (float) ws.m_offsetX;
        float pz = z - (float) ws.m_offsetY;
        btTransform t = new btTransform(new btQuaternion(qx, qy, qz, qw), new btVector3(px, y, pz));
        ws.addRagdoll(id, t);
    }

    public static void removeRagdoll(int id) {
        WorldSimulation ws = WorldSimulation.instance;
        if (ws == null) {
            throw new NullPointerException(NULL_WS);
        }
        ws.removeRagdoll(id);
    }

    public static int simulateRagdoll(int id, float[] out) {
        final String fn = "PZBullet::Java_zombie_core_physics_Bullet_simulateRagdoll";
        if (!__onceSimulateRagdoll) {
            log(1423, fn);
        }
        WorldSimulation ws = WorldSimulation.instance;
        if (ws == null) {
            if (!__onceSimulateRagdoll) {
                log(1427, fn + " - WorldSimulation::instance is null");
            }
            __onceSimulateRagdoll = true;
            throw new RuntimeException(NULL_WS);
        }
        if (id < 0) {
            if (!__onceSimulateRagdoll) {
                log(1434, fn + " - id is invalid");
            }
            __onceSimulateRagdoll = true;
            throw new IllegalArgumentException("id is invalid");
        }
        if (out.length < 1) {
            __onceSimulateRagdoll = true;
            throw new IllegalArgumentException("output array is too small");
        }
        int result;
        PZRagdoll p = ws.m_ragdolls.get(id);
        if (p != null) {
            if (!__onceSimulateRagdoll) {
                log(1456, "[Success] " + fn + " - Ragdoll found!");
            }
            result = p.simulate(out);
        } else {
            if (!__onceSimulateRagdoll) {
                log(1461, "[Failure] " + fn + " - Ragdoll NOT found!");
            }
            result = 0;
        }
        __onceSimulateRagdoll = true;
        return result;
    }

    public static int simulateRagdollWithRigidBodyOutput(
            int id, float[] skeletonOut, float[] rigidBodyOut) {
        final String fn =
                "PZBullet::Java_zombie_core_physics_Bullet_simulateRagdollWithRigidBodyOutput";
        if (!__onceSimulateRagdollWithRigidBodyOutput) {
            log(1471, fn);
        }
        WorldSimulation ws = WorldSimulation.instance;
        if (ws == null) {
            if (!__onceSimulateRagdollWithRigidBodyOutput) {
                log(1475, fn + " - WorldSimulation::instance is null");
            }
            __onceSimulateRagdollWithRigidBodyOutput = true;
            throw new RuntimeException(NULL_WS);
        }
        if (id < 0) {
            if (!__onceSimulateRagdollWithRigidBodyOutput) {
                log(1482, fn + " - id is invalid");
            }
            __onceSimulateRagdollWithRigidBodyOutput = true;
            throw new IllegalArgumentException("id is invalid");
        }
        if (skeletonOut.length < 1) {
            __onceSimulateRagdollWithRigidBodyOutput = true;
            throw new IllegalArgumentException("output skeletonBuffer is too small");
        }
        if (rigidBodyOut.length < 1) {
            __onceSimulateRagdollWithRigidBodyOutput = true;
            throw new IllegalArgumentException("output rigidBodyBuffer is too small");
        }
        int result;
        PZRagdoll p = ws.m_ragdolls.get(id);
        if (p != null) {
            if (!__onceSimulateRagdollWithRigidBodyOutput) {
                log(1519, "[Success] " + fn + " - Ragdoll found!");
            }
            result = p.simulate(skeletonOut, rigidBodyOut);
        } else {
            if (!__onceSimulateRagdollWithRigidBodyOutput) {
                log(1524, "[Failure] " + fn + " - Ragdoll NOT found!");
            }
            result = 0;
        }
        __onceSimulateRagdollWithRigidBodyOutput = true;
        return result;
    }

    public static int updateSkeletonFromNetworkPhysics(
            int id, float[] rigidBodyIn, float[] skeletonOut) {
        final String fn =
                "PZBullet::Java_zombie_core_physics_Bullet_updateSkeletonFromNetworkPhysics";
        if (!__onceUpdateSkeletonFromNetworkPhysics) {
            log(1535, fn);
        }
        WorldSimulation ws = WorldSimulation.instance;
        if (ws == null) {
            if (!__onceUpdateSkeletonFromNetworkPhysics) {
                log(1539, fn + " - WorldSimulation::instance is null");
            }
            __onceUpdateSkeletonFromNetworkPhysics = true;
            throw new RuntimeException(NULL_WS);
        }
        if (id < 0) {
            if (!__onceUpdateSkeletonFromNetworkPhysics) {
                log(1546, fn + " - id is invalid");
            }
            __onceUpdateSkeletonFromNetworkPhysics = true;
            throw new IllegalArgumentException("id is invalid");
        }
        if (skeletonOut.length < 1) {
            __onceUpdateSkeletonFromNetworkPhysics = true;
            throw new IllegalArgumentException("output skeletonBuffer is too small");
        }
        if (rigidBodyIn.length < 1) {
            __onceUpdateSkeletonFromNetworkPhysics = true;
            throw new IllegalArgumentException("output rigidBodyBuffer is too small");
        }
        int result;
        PZRagdoll p = ws.m_ragdolls.get(id);
        if (p != null) {
            if (!__onceUpdateSkeletonFromNetworkPhysics) {
                log(1583, "[Success] " + fn + " - Ragdoll found!");
            }
            result = p.updateRigidBodies(rigidBodyIn, skeletonOut);
        } else {
            if (!__onceUpdateSkeletonFromNetworkPhysics) {
                log(1588, "[Failure] " + fn + " - Ragdoll NOT found!");
            }
            result = 0;
        }
        __onceUpdateSkeletonFromNetworkPhysics = true;
        return result;
    }

    public static void setRagdollLocalTransformRotation(
            int id, float x, float y, float z, float w) {
        PZRagdoll p = find(id);
        if (p != null) {
            p.setLocalTransformRotation(x, y, z, w);
        }
    }

    public static void updateRagdoll(
            int id, float x, float y, float z, float qx, float qy, float qz, float qw) {
        final String fn = "PZBullet::Java_zombie_core_physics_Bullet_updateRagdoll";
        if (!__onceUpdateRagdoll) {
            log(1599, fn);
        }
        WorldSimulation ws = WorldSimulation.instance;
        PZRagdoll p = ws.m_ragdolls.get(id);
        if (p != null) {
            p.update(x - (float) ws.m_offsetX, y, z - (float) ws.m_offsetY, qx, qy, qz, qw);
            if (!__onceUpdateRagdoll) {
                log(1607, "[Success] " + fn);
            }
        }
        __onceUpdateRagdoll = true;
    }

    public static void updateRagdollSkeletonTransforms(int id, int count, float[] data) {
        final String fn =
                "PZBullet::Java_zombie_core_physics_Bullet_updateRagdollSkeletonTransforms";
        if (!__onceUpdateRagdollSkeleton) {
            log(1614, fn);
        }
        PZRagdoll p = find(id);
        if (p != null && data != null) {
            p.updateSkeletonBoneTransforms(count, data);
            if (!__onceUpdateRagdollSkeleton) {
                log(1623, "[Success] " + fn);
            }
        }
        __onceUpdateRagdollSkeleton = true;
    }

    public static void updateRagdollSkeletonPreviousTransforms(
            int id, int count, float dt, float[] data) {
        final String fn =
                "PZBullet::Java_zombie_core_physics_Bullet_updateRagdollSkeletonPreviousTransforms";
        if (!__onceUpdateRagdollSkeletonVelocities) {
            log(1632, fn);
        }
        PZRagdoll p = find(id);
        if (p != null && data != null) {
            p.updateRagdollSkeletonPreviousBoneTransforms(count, dt, data);
            if (!__onceUpdateRagdollSkeletonVelocities) {
                log(1641, "[Success] " + fn);
            }
        }
        __onceUpdateRagdollSkeletonVelocities = true;
    }

    public static int getRagdollSimulationState(int id) {
        PZRagdoll p = find(id);
        if (p == null) {
            return -1;
        }
        return p.getSimulationState();
    }

    public static void resetSkeletonPose(int id) {
        PZRagdoll p = find(id);
        if (p != null) {
            p.resetSkeletonPose();
        }
    }

    public static void setRagdollActive(int id, boolean active) {
        PZRagdoll p = find(id);
        if (p != null) {
            p.setActive(active);
        }
    }

    public static void drawDebugSingleBone(int id, boolean b) {
        PZRagdoll p = find(id);
        if (p != null) {
            p.m_debugSingleBone = b;
        }
    }

    public static void drawDebugRagdollSkeleton(int id, boolean skeleton, boolean singleBone) {
        final String fn = "PZBullet::Java_zombie_core_physics_Bullet_drawDebugRagdollSkeleton";
        trace(1698, fn);
        PZRagdoll p = find(id);
        if (p != null) {
            p.m_drawSkeleton = skeleton;
            p.m_drawSingleBone = singleBone;
        }
        trace(1705, "[Success] " + fn);
    }

    public static void drawDebugRagdollBodyParts(
            int id, boolean bodyParts, boolean onlyHighlighted) {
        final String fn = "PZBullet::Java_zombie_core_physics_Bullet_drawDebugRagdollBodyParts";
        trace(1712, fn);
        PZRagdoll p = find(id);
        if (p != null) {
            p.m_drawBodyParts = bodyParts;
            p.m_drawOnlyHighlightedBodyPart = onlyHighlighted;
        }
        trace(1719, "[Success] " + fn);
    }

    public static void highlightRagdollBodyPart(int id, int part) {
        PZRagdoll p = find(id);
        if (p != null) {
            p.m_highlightedBodyPart = part;
        }
    }

    public static void applyForce(int id, int part, float[] force) {
        PZRagdoll p = find(id);
        if (p != null && force != null) {
            p.applyForce(part, force);
        }
    }

    public static void applyImpulse(int id, int part, float[] impulse) {
        PZRagdoll p = find(id);
        if (p != null && impulse != null) {
            p.applyImpulse(part, impulse);
        }
    }

    public static void setRagdollMass(float mass) {
        RagdollBuilder.Instance().setMass((double) mass);
        WorldSimulation ws = WorldSimulation.instance;
        for (PZRagdoll p : ws.m_ragdolls.m_map.values()) {
            p.setMass(mass);
        }
        for (PZRagdoll p : new ArrayList<>(ws.m_ragdolls.m_pool)) {
            p.setMass(mass);
        }
    }

    public static boolean defineRagdollConstraints(float[] data, boolean update) {
        if (data == null) {
            log(
                    2169,
                    "Java_zombie_core_physics_Bullet_defineRagdollConstraints - argument is null");
            throw new NullPointerException("argument is null");
        }
        RagdollBuilder.Instance().m_script.constraintsFromJava(data, data.length);
        if (update) {
            WorldSimulation.instance.m_ragdolls.forEachAll(PZRagdoll::updateConstraints);
        }
        return true;
    }

    public static boolean defineRagdollAnchors(float[] data, boolean update) {
        if (data == null) {
            log(2204, "Java_zombie_core_physics_Bullet_defineRagdollAnchors - argument is null");
            throw new NullPointerException("argument is null");
        }
        RagdollBuilder.Instance().m_script.anchorsFromJava(data, data.length);
        if (update) {
            WorldSimulation.instance.m_ragdolls.forEachAll(PZRagdoll::updateAnchors);
        }
        return true;
    }

    public static boolean defineRagdollBodyPartInfo(float[] data, boolean update) {
        if (data == null) {
            log(
                    2240,
                    "Java_zombie_core_physics_Bullet_defineRagdollBodyPartInfo - argument is null");
            throw new NullPointerException("argument is null");
        }
        RagdollBuilder.Instance().m_script.bodyPartInfoFromJava(data, data.length);
        updateRagdollBodyParts(update);
        return true;
    }

    public static boolean defineRagdollBodyDynamics(float[] data, boolean update) {
        if (data == null) {
            log(
                    2264,
                    "Java_zombie_core_physics_Bullet_defineRagdollBodyDynamics - argument is null");
            throw new NullPointerException("argument is null");
        }
        RagdollBuilder.Instance().m_script.bodyDynamicsFromJava(data, data.length);
        updateRagdollBodyParts(update);
        return true;
    }

    public static boolean setRagdollBodyDynamics(int id, float[] data) {
        if (data == null) {
            log(2289, "Java_zombie_core_physics_Bullet_setRagdollBodyDynamics - argument is null");
            throw new NullPointerException("argument is null");
        }
        RagdollBodyDynamics d = new RagdollBodyDynamics();
        RagdollBuilder.Instance().m_script.setRagdollBodyDynamics(d, data, data.length);
        updateRagdollBodyDynamics(id, d);
        return true;
    }

    public static boolean resetRagdollBodyDynamics(int id) {
        WorldSimulation ws = WorldSimulation.instance;
        for (PZRagdoll p : ws.m_ragdolls.m_map.values()) {
            if (p.m_id == id) {
                p.resetBodyDynamics();
                return true;
            }
        }
        for (PZRagdoll p : new ArrayList<>(ws.m_ragdolls.m_pool)) {
            if (p.m_id == id) {
                p.resetBodyDynamics();
                return true;
            }
        }
        return false;
    }

    /** updateRagdollBodyDynamics(int, RagdollBodyDynamics) @00157730: first id match wins. */
    static void updateRagdollBodyDynamics(int id, RagdollBodyDynamics d) {
        WorldSimulation ws = WorldSimulation.instance;
        for (PZRagdoll p : ws.m_ragdolls.m_map.values()) {
            if (p.m_id == id) {
                p.updateBodyDynamics(d);
                return;
            }
        }
        for (PZRagdoll p : new ArrayList<>(ws.m_ragdolls.m_pool)) {
            if (p.m_id == id) {
                p.updateBodyDynamics(d);
                return;
            }
        }
    }

    /** updateRagdollBodyParts(bool) @00157a50 */
    static void updateRagdollBodyParts(boolean update) {
        if (!update) {
            return;
        }
        RagdollBuilder.Instance().updateBodyPartShapes();
        WorldSimulation ws = WorldSimulation.instance;
        ws.m_ragdolls.forEachAll(PZRagdoll::updateBodyParts);
        for (PZBallisticsTarget t : ws.m_ballisticsTargets.m_map.values()) {
            t.updateBodyParts();
        }
        for (PZBallisticsTarget t : new ArrayList<>(ws.m_ballisticsTargets.m_pool)) {
            t.updateBodyParts();
        }
    }
}
