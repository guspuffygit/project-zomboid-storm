// Port of PZ Java_zombie_core_physics_Bullet_*Ballistics* / getTargetedBodyPart JNI entries
// (PZBullet.cpp) from decomp @00155xxx-@00156xxx, plus the undeclared export
// Java_zombie_core_physics_Bullet_getBallisticsTargetsSpread @00155940
package io.pzstorm.storm.bullet.pz.jni;

import io.pzstorm.storm.bullet.linearmath.btQuaternion;
import io.pzstorm.storm.bullet.linearmath.btVector3;
import io.pzstorm.storm.bullet.pz.PZBallistics;
import io.pzstorm.storm.bullet.pz.PZBallisticsTarget;
import io.pzstorm.storm.bullet.pz.WorldSimulation;

/**
 * Bodies of the ballistics JNI natives, same names and Java signatures as
 * zombie.core.physics.Bullet.
 *
 * <p>Deviations forced by Java: where the C++ dereferences a null {@code WorldSimulation::instance}
 * without a check it segfaults; Java raises a NullPointerException. The entries that do check it
 * throw the Java exception C++ raises ({@code NullPointerException "WorldSimulation::instance is
 * null"}); the C++ then returns a value (2 for addBallisticsTarget) that the Java caller never
 * sees. An output array shorter than 1 raises {@code IllegalArgumentException "output array is too
 * small"} (C++ returns -1 with that exception pending).
 */
public final class BallisticsNatives {

    private BallisticsNatives() {}

    private static final String NULL_WS = "WorldSimulation::instance is null";

    private static PZBallistics find(int id) {
        return WorldSimulation.instance.m_ballistics.get(id);
    }

    private static PZBallisticsTarget findTarget(int id) {
        return WorldSimulation.instance.m_ballisticsTargets.get(id);
    }

    private static void checkOut(float[] out) {
        if (out.length < 1) {
            throw new IllegalArgumentException("output array is too small");
        }
    }

    public static void updateBallistics(int id, float x, float y, float z) {
        WorldSimulation ws = WorldSimulation.instance;
        if (!ws.m_ballistics.containsKey(id)) {
            ws.addBallistics(id);
        }
        PZBallistics p = ws.m_ballistics.get(id);
        p.update(x - (float) ws.m_offsetX, y, z - (float) ws.m_offsetY);
    }

    public static void updateBallisticsMuzzleAimDirection(int id, float x, float y, float z) {
        PZBallistics p = find(id);
        if (p != null) {
            p.updateMuzzleAimDirection(x, y, z);
        }
    }

    public static void setBallisticsSize(int id, float size) {
        PZBallistics p = find(id);
        if (p != null) {
            p.setSize(size);
        }
    }

    public static void setBallisticsColor(int id, float r, float g, float b) {
        PZBallistics p = find(id);
        if (p != null) {
            p.setColor(new btVector3(r, g, b));
        }
    }

    public static int getBallisticsTargets(int id, float range, int max, float[] out) {
        checkOut(out);
        PZBallistics p = find(id);
        if (p == null) {
            return 0;
        }
        return p.getTargets(range, max, out);
    }

    public static int getBallisticsTargetsSpreadData(
            int id, float range, float spread, float exponent, int n, int unused, float[] out) {
        checkOut(out);
        PZBallistics p = find(id);
        if (p == null) {
            return 0;
        }
        return p.getSpreadTargetData(range, spread, exponent, n, unused, out);
    }

    /** Undeclared export @00155940 (no matching {@code native} in Bullet.java). */
    public static int getBallisticsTargetsSpread(
            int id, float range, float spread, int n, int max, float[] out) {
        checkOut(out);
        PZBallistics p = find(id);
        if (p == null) {
            return 0;
        }
        return p.getTargets(range, spread, n, max, out);
    }

    public static int getBallisticsCameraTargets(
            int id, float range, int max, boolean addParts, float[] out) {
        checkOut(out);
        PZBallistics p = find(id);
        if (p == null) {
            return 0;
        }
        return p.getCameraTargets(range, max, out, addParts);
    }

    public static void setBallisticsRange(int id, float range) {
        PZBallistics p = find(id);
        if (p != null) {
            p.setRange(range);
        }
    }

    public static void removeBallistics(int id) {
        WorldSimulation ws = WorldSimulation.instance;
        if (ws == null) {
            throw new NullPointerException(NULL_WS);
        }
        ws.removeBallistics(id);
    }

    public static void updateBallisticsAimReticlePosition(int id, float x, float y, float z) {
        WorldSimulation ws = WorldSimulation.instance;
        PZBallistics p = ws.m_ballistics.get(id);
        if (p != null) {
            p.updateAimReticlePosition(
                    new btVector3(x - (float) ws.m_offsetX, y, z - (float) ws.m_offsetY));
        }
    }

    public static void updateBallisticsAimReticleRotation(
            int id, float ax, float ay, float az, float angle) {
        PZBallistics p = find(id);
        if (p != null) {
            p.updateAimReticleRotation(new btQuaternion(new btVector3(ax, ay, az), angle));
        }
    }

    public static void updateBallisticsAimReticleQuaternion(
            int id, float x, float y, float z, float w) {
        PZBallistics p = find(id);
        if (p != null) {
            p.updateAimReticleQuaternion(new btQuaternion(x, y, z, w));
        }
    }

    public static void updateBallisticsAimReticleRotate(
            int id, float x, float y, float z, float w) {
        PZBallistics p = find(id);
        if (p != null) {
            p.updateAimReticleRotate(new btQuaternion(x, y, z, w));
        }
    }

    public static void updateBallisticsTargetSkeleton(int id, int n, float[] bones) {
        WorldSimulation ws = WorldSimulation.instance;
        PZBallisticsTarget t = ws.m_ballisticsTargets.get(id);
        if (t == null) {
            t = ws.m_ballisticsTargets.add(id);
        }
        t.updateSkeletonBoneTransforms(n, bones);
    }

    public static void updateBallisticsTarget(
            int id, float x, float y, float z, float qx, float qy, float qz, float qw, boolean b) {
        WorldSimulation ws = WorldSimulation.instance;
        if (ws == null) {
            throw new NullPointerException(NULL_WS);
        }
        PZBallisticsTarget t = ws.m_ballisticsTargets.get(id);
        if (t == null) {
            t = ws.m_ballisticsTargets.add(id);
        }
        t.update(x - (float) ws.m_offsetX, y, z - (float) ws.m_offsetY, qx, qy, qz, qw, b);
    }

    public static void setBallisticsTargetAxis(int id, float a, float b, float c) {
        PZBallisticsTarget t = findTarget(id);
        if (t != null) {
            t.setAxis(a, b, c);
        }
    }

    public static int addBallisticsTarget(int id) {
        WorldSimulation ws = WorldSimulation.instance;
        if (ws == null) {
            throw new NullPointerException(NULL_WS);
        }
        PZBallisticsTarget t = ws.m_ballisticsTargets.get(id);
        if (t == null) {
            t = ws.m_ballisticsTargets.add(id);
        }
        return t.addToWorld();
    }

    public static int removeBallisticsTarget(int id) {
        WorldSimulation ws = WorldSimulation.instance;
        PZBallisticsTarget t = ws.m_ballisticsTargets.get(id);
        if (t == null) {
            return 0;
        }
        int r = t.removeFromWorld();
        ws.m_ballisticsTargets.remove(id);
        return r;
    }

    public static int getTargetedBodyPart(int id) {
        PZBallisticsTarget t = findTarget(id);
        if (t == null) {
            return 11;
        }
        return t.getTargetedBodyPart();
    }

    public static void setBallisticsTargetAdjustingShapeScale(float x, float y, float z) {
        btVector3 v = PZBallisticsTarget.extentsScale;
        v.x = x;
        v.y = y;
        v.z = z;
        v.w = 0.0;
    }

    public static void setBallisticsTargetAllPartsColor(float r, float g, float b) {
        btVector3 v = PZBallisticsTarget.allPartsColor;
        v.x = r;
        v.y = g;
        v.z = b;
        v.w = 0.0;
    }
}
