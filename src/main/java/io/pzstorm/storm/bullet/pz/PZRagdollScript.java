// Port of PZ glue PZRagdollScript (PZRagdollScript.cpp; @001644b0..@00165090). 0x88 bytes.
package io.pzstorm.storm.bullet.pz;

import io.pzstorm.storm.bullet.linearmath.btVector3;
import java.util.ArrayList;

/**
 * Parses the flat float arrays uploaded by zombie.scripting.objects.RagdollScript. The C++ {@code
 * next(T&)} overloads write through a reference and return success; here they return success and
 * write into a caller-supplied holder.
 *
 * <p>Gap: {@code vector<RagdollBodyDynamics>::_M_default_append} does not initialise the new
 * elements (heap garbage in C++); Java starts them at zero.
 */
public class PZRagdollScript {

    private static final String LOC = "/usr/src/pz/pzbullet/PZRagdollScript.cpp:";

    /** +0 */
    public double m_mass = 70.0;

    /** +8 */
    public double m_friction = 1.5;

    /** +0x10 */
    public double m_rollingFriction = 0.5;

    /** +0x18 vector&lt;RagdollConstraint&gt; (10) */
    public final ArrayList<RagdollConstraint> m_constraints = new ArrayList<>();

    /** +0x30 vector&lt;RagdollAnchor&gt; (35) */
    public final ArrayList<RagdollAnchor> m_anchors = new ArrayList<>();

    /** +0x48 vector&lt;BodyPartInfo&gt; (11) */
    public final ArrayList<BodyPartInfo> m_bodyPartInfo = new ArrayList<>();

    /** +0x60 vector&lt;RagdollBodyDynamics&gt; (11) */
    public final ArrayList<RagdollBodyDynamics> m_bodyDynamics = new ArrayList<>();

    /** +0x78 float* */
    public float[] m_data;

    /** +0x80 */
    public int m_size = -1;

    /** +0x84 */
    public int m_index = -1;

    /**
     * @00165090
     */
    public PZRagdollScript() {
        PZDebugLog.instance()
                .log("PZBullet", PZDebugType.Debug, LOC + "5", "PZRagdollScript::PZRagdollScript");
        while (m_constraints.size() < 10) {
            m_constraints.add(new RagdollConstraint());
        }
        while (m_anchors.size() < 0x23) {
            m_anchors.add(new RagdollAnchor());
        }
        while (m_bodyPartInfo.size() < 0xb) {
            m_bodyPartInfo.add(new BodyPartInfo());
        }
        while (m_bodyDynamics.size() < 0xb) {
            m_bodyDynamics.add(new RagdollBodyDynamics());
        }
        PZDebugLog.instance()
                .log(
                        "PZBullet",
                        PZDebugType.Debug,
                        LOC + "10",
                        "[Success] PZRagdollScript::PZRagdollScript");
    }

    /** cvttsd2si: INT_MIN for NaN / out-of-range. */
    static int cvttsd2si(double d) {
        if (Double.isNaN(d) || d >= 2147483648.0 || d <= -2147483649.0) {
            return Integer.MIN_VALUE;
        }
        return (int) d;
    }

    // ---- next overloads -------------------------------------------------------------------

    /**
     * @001644b0 next(double&)
     */
    public boolean next(double[] out) {
        int i = m_index;
        if (i < m_size) {
            m_index = i + 1;
            out[0] = (double) m_data[i];
            return true;
        }
        return false;
    }

    /**
     * @001644f0 next(btVector3&)
     */
    public boolean next(btVector3 out) {
        int i = m_index;
        boolean ok = i + 2 < m_size;
        if (ok) {
            float f0 = m_data[i];
            float f1 = m_data[i + 1];
            float f2 = m_data[i + 2];
            m_index = i + 3;
            out.w = 0.0;
            out.x = (double) f0;
            out.y = (double) f1;
            out.z = (double) f2;
        }
        return ok;
    }

    /**
     * @00164560 next(int&): round half away from zero in double, then cvttsd2si.
     */
    public boolean next(int[] out) {
        int i = m_index;
        if (m_size <= i) {
            return false;
        }
        m_index = i + 1;
        float f = m_data[i];
        if (0.0 <= f) {
            out[0] = cvttsd2si((double) f + 0.5);
            return true;
        }
        out[0] = cvttsd2si((double) f - 0.5);
        return true;
    }

    /**
     * @00164820 next(bool&)
     */
    public boolean next(boolean[] out) {
        int i = m_index;
        if (m_size <= i) {
            return false;
        }
        m_index = i + 1;
        out[0] = 0.0 < m_data[i];
        return true;
    }

    /**
     * @001649c0 next(float&)
     */
    public boolean next(float[] out) {
        int i = m_index;
        if (i < m_size) {
            m_index = i + 1;
            out[0] = m_data[i];
            return true;
        }
        return false;
    }

    private void begin(float[] data, int size) {
        m_data = data;
        m_index = 0;
        m_size = size;
    }

    // ---- *FromJava ------------------------------------------------------------------------

    /**
     * @001645e0
     */
    public boolean constraintsFromJava(float[] data, int size) {
        PZDebugLog.instance()
                .log(
                        "PZBullet",
                        PZDebugType.Debug,
                        LOC + "15",
                        "PZRagdollScript::constraintsFromJava");
        begin(data, size);
        int[] joint = new int[1], type = new int[1], a = new int[1], b = new int[1];
        btVector3 v0 = new btVector3(), v1 = new btVector3(), v2 = new btVector3();
        btVector3 v3 = new btVector3(), v4 = new btVector3(), v5 = new btVector3();
        int n = 10;
        while (next(joint)
                && next(type)
                && next(a)
                && next(b)
                && next(v0)
                && next(v1)
                && next(v2)
                && next(v3)
                && next(v4)
                && next(v5)) {
            RagdollConstraint c = m_constraints.get(joint[0]);
            c.joint = joint[0];
            c.constraintType = type[0];
            c.constraintPartA = a[0];
            c.constraintPartB = b[0];
            c.constraintAxisA.set(v0);
            c.constraintAxisB.set(v1);
            c.constraintPositionOffsetA.set(v2);
            c.constraintPositionOffsetB.set(v3);
            c.constraintLimit.set(v4);
            c.constraintLimitExtended.set(v5);
            n--;
            if (n == 0) {
                PZDebugLog.instance()
                        .log(
                                "PZBullet",
                                PZDebugType.Debug,
                                LOC + "35",
                                "[Success] PZRagdollScript::constraintsFromJava");
                return true;
            }
        }
        return false;
    }

    /**
     * @00164880
     */
    public boolean anchorsFromJava(float[] data, int size) {
        PZDebugLog.instance()
                .log("PZBullet", PZDebugType.Debug, LOC + "41", "PZRagdollScript::anchorsFromJava");
        begin(data, size);
        int[] bone = new int[1], part = new int[1];
        boolean[] r = new boolean[1], o = new boolean[1], e = new boolean[1];
        int i = 0;
        while (true) {
            if (SkeletonBone.Count() <= i) {
                PZDebugLog.instance()
                        .log(
                                "PZBullet",
                                PZDebugType.Debug,
                                LOC + "56",
                                "[Success] PZRagdollScript::anchorsFromJava");
                return true;
            }
            if (!next(bone) || !next(part) || !next(r) || !next(o) || !next(e)) {
                return false;
            }
            i++;
            RagdollAnchor an = m_anchors.get(bone[0]);
            an.bone = bone[0];
            an.bodyPart = part[0];
            an.reverse = r[0];
            an.original = o[0];
            an.enabled = e[0];
        }
    }

    /**
     * @00164b50
     */
    public boolean bodyPartInfoFromJava(float[] data, int size) {
        PZDebugLog.instance()
                .log(
                        "PZBullet",
                        PZDebugType.Debug,
                        LOC + "62",
                        "PZRagdollScript::bodyPartInfoFromJava");
        begin(data, size);
        int n = 0xb;
        int[] iv = new int[1];
        boolean[] bv = new boolean[1];
        float[] fv = new float[1];
        double[] dv = new double[1];
        while (true) {
            BodyPartInfo local = new BodyPartInfo();
            local.part = -1;
            local.calculateLength = true;
            // Locals start at the struct defaults; each successful read overwrites one field.
            if (!next(iv)) break;
            local.part = iv[0];
            if (!next(bv)) break;
            local.calculateLength = bv[0];
            if (!next(fv)) break;
            local.radius = fv[0];
            if (!next(fv)) break;
            local.height = fv[0];
            if (!next(fv)) break;
            local.gap = fv[0];
            if (!next(iv)) break;
            local.shape = iv[0];
            if (!next(dv)) break;
            local.mass = dv[0];
            if (!next(local.offset)) break;
            m_bodyPartInfo.get(local.part).set(local);
            n--;
            if (n == 0) {
                PZDebugLog.instance()
                        .log(
                                "PZBullet",
                                PZDebugType.Debug,
                                LOC + "80",
                                "[Success] PZRagdollScript::bodyPartShapesFromJava");
                return true;
            }
        }
        return false;
    }

    private boolean readDynamics(RagdollBodyDynamics d) {
        int[] iv = new int[1];
        float[] fv = new float[1];
        if (!next(iv)) return false;
        d.part = iv[0];
        if (!next(fv)) return false;
        d.linearDamping = fv[0];
        if (!next(fv)) return false;
        d.angularDamping = fv[0];
        if (!next(fv)) return false;
        d.deactivationTime = fv[0];
        if (!next(fv)) return false;
        d.linearSleepingThreshold = fv[0];
        if (!next(fv)) return false;
        d.angularSleepingThreshold = fv[0];
        if (!next(fv)) return false;
        d.friction = fv[0];
        if (!next(fv)) return false;
        d.rollingFriction = fv[0];
        return true;
    }

    /**
     * @00164d10
     */
    public boolean bodyDynamicsFromJava(float[] data, int size) {
        PZDebugLog.instance()
                .log(
                        "PZBullet",
                        PZDebugType.Debug,
                        LOC + "86",
                        "PZRagdollScript::bodyDynamicsFromJava");
        begin(data, size);
        RagdollBodyDynamics local = new RagdollBodyDynamics();
        int n = 0xb;
        while (readDynamics(local)) {
            m_bodyDynamics.get(local.part).set(local);
            n--;
            if (n == 0) {
                PZDebugLog.instance()
                        .log(
                                "PZBullet",
                                PZDebugType.Debug,
                                LOC + "104",
                                "[Success] PZRagdollScript::bodyDynamicsFromJava");
                return true;
            }
        }
        return false;
    }

    /**
     * @00164a00: quirk — parses into locals and stores nothing.
     */
    public boolean setBodyDynamicsFromJava(float[] data, int size) {
        PZDebugLog.instance()
                .log(
                        "PZBullet",
                        PZDebugType.Debug,
                        LOC + "110",
                        "PZRagdollScript::setBodyDynamicsFromJava");
        begin(data, size);
        RagdollBodyDynamics local = new RagdollBodyDynamics();
        int n = 0xb;
        while (readDynamics(local)) {
            n--;
            if (n == 0) {
                PZDebugLog.instance()
                        .log(
                                "PZBullet",
                                PZDebugType.Debug,
                                LOC + "127",
                                "[Success] PZRagdollScript::setBodyDynamicsFromJava");
                return true;
            }
        }
        return false;
    }

    /**
     * @00164f50: reads 11 records straight into {@code d} (each overwrites the previous).
     */
    public boolean setRagdollBodyDynamics(RagdollBodyDynamics d, float[] data, int size) {
        PZDebugLog.instance()
                .log(
                        "PZBullet",
                        PZDebugType.Debug,
                        LOC + "255",
                        "PZRagdollScript::setRagdollBodyDynamics");
        m_size = size;
        m_index = 0;
        m_data = data;
        int n = 0xb;
        while (readDynamics(d)) {
            n--;
            if (n == 0) {
                PZDebugLog.instance()
                        .log(
                                "PZBullet",
                                PZDebugType.Debug,
                                LOC + "271",
                                "[Success] PZRagdollScript::setRagdollBodyDynamics");
                return true;
            }
        }
        return false;
    }

    // ---- lookups --------------------------------------------------------------------------

    /**
     * @00164e80
     */
    public RagdollConstraint getRagdollConstraint(int joint) {
        for (RagdollConstraint c : m_constraints) {
            if (c.joint == joint) {
                return c;
            }
        }
        return null;
    }

    /**
     * @00164eb0
     */
    public RagdollAnchor getRagdollAnchor(int bone) {
        for (RagdollAnchor a : m_anchors) {
            if (a.bone == bone) {
                return a;
            }
        }
        return null;
    }

    /**
     * @00164ee0
     */
    public BodyPartInfo getBodyPartInfo(int part) {
        for (BodyPartInfo b : m_bodyPartInfo) {
            if (b.part == part) {
                return b;
            }
        }
        return null;
    }

    /**
     * @00164f10
     */
    public ArrayList<RagdollAnchor> getRagdollAnchors() {
        return m_anchors;
    }

    /**
     * @00164f20
     */
    public RagdollBodyDynamics getRagdollBodyDynamics(int part) {
        for (RagdollBodyDynamics d : m_bodyDynamics) {
            if (d.part == part) {
                return d;
            }
        }
        return null;
    }
}
