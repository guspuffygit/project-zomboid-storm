// Port of PZ PZVehicleScript (PZVehicle.cpp) from decomp:
//   next(double&) @001597c0, next(btVector3&) @00159800, next(int&) @00159870, next(bool&)
// @001598f0,
//   fromJava(float*,int) @00159940, fromJava(char const*,float*,int) @0015b900, findScript
// @00159b60,
//   definePhysicsMesh @001594b0, static m_scripts (_GLOBAL__sub_I_PZVehicle.cpp @00148510).
// C++ object size 0x760; member names are not in the binary (no debug info), chosen descriptively.
package io.pzstorm.storm.bullet.pz;

import io.pzstorm.storm.bullet.linearmath.btVector3;
import java.util.ArrayList;

public class PZVehicleScript {

    public static final int MAX_SHAPES = 10;
    public static final int MAX_WHEELS = 8;

    /** One physics shape (stride 0x88). */
    public static class Shape {
        public int type; // +0x00 (1 = box, 2 = sphere, 3 = mesh)
        public final btVector3 offset = new btVector3(); // +0x08
        public final btVector3 rotate = new btVector3(); // +0x28
        public final btVector3 extents = new btVector3(); // +0x48
        public double radius; // +0x68
        public final ArrayList<Double> mesh = new ArrayList<>(); // +0x70 std::vector<double>
    }

    /** One wheel (stride 0x30). */
    public static class Wheel {
        public final btVector3 offset = new btVector3(); // +0x00
        public double radius; // +0x20
        public boolean front; // +0x28
    }

    /** static std::vector&lt;PZVehicleScript*&gt; m_scripts */
    public static final ArrayList<PZVehicleScript> m_scripts = new ArrayList<>();

    public String name = ""; // +0x00 std::string
    public double modelScale = (double) 2.15f; // +0x20
    public double mass = 800.0; // +0x28
    public double rollInfluence = 1.0; // +0x30
    public double suspensionStiffness = 20.0; // +0x38
    public double suspensionCompression = (double) 1.83f; // +0x40
    public double suspensionDamping = (double) 1.88f; // +0x48
    public double maxSuspensionTravelCm = 100.0; // +0x50
    public double suspensionRestLength = 1.0; // +0x58
    public double wheelFriction = 2.0; // +0x60
    public double stoppingMovementForce = 1.0; // +0x68
    public final Shape[] shapes = new Shape[MAX_SHAPES]; // +0x70
    public int shapeCount = 0; // +0x5c0
    public final Wheel[] wheels = new Wheel[MAX_WHEELS]; // +0x5c8
    public int wheelCount = 4; // +0x748
    public float[] data; // +0x750 (parse cursor, valid only during fromJava)
    public int len; // +0x758
    public int pos; // +0x75c

    public PZVehicleScript() {
        for (int i = 0; i < MAX_SHAPES; i++) {
            shapes[i] = new Shape();
        }
        for (int i = 0; i < MAX_WHEELS; i++) {
            wheels[i] = new Wheel();
        }
        // default 4-wheel layout (constructor inlined into fromJava @0015b900)
        wheels[0].offset.x = 0.25;
        wheels[0].offset.y = (double) -0.18f;
        wheels[0].offset.z = (double) 0.63f;
        wheels[0].radius = (double) 0.3f;
        wheels[0].front = true;
        wheels[1].offset.x = -0.25;
        wheels[1].offset.y = (double) -0.18f;
        wheels[1].offset.z = (double) 0.63f;
        wheels[1].radius = (double) 0.3f;
        wheels[1].front = true;
        wheels[2].offset.x = 0.25;
        wheels[2].offset.y = (double) -0.18f;
        wheels[2].offset.z = (double) -0.59f;
        wheels[2].radius = (double) 0.3f;
        wheels[2].front = false;
        wheels[3].offset.x = -0.25;
        wheels[3].offset.y = (double) -0.18f;
        wheels[3].offset.z = (double) -0.59f;
        wheels[3].radius = (double) 0.3f;
        wheels[3].front = false;
    }

    /** bool next(double&) @001597c0; out[0] receives the value. */
    public boolean next(double[] out) {
        int p = pos;
        int n = len;
        if (p < n) {
            pos = p + 1;
            out[0] = (double) data[p];
        }
        return p < n;
    }

    /** bool next(btVector3&) @00159800 */
    public boolean next(btVector3 v) {
        int p = pos;
        boolean ok = p + 2 < len;
        if (ok) {
            float a = data[p];
            float b = data[p + 1];
            float c = data[p + 2];
            pos = p + 3;
            v.w = 0.0;
            v.x = (double) a;
            v.y = (double) b;
            v.z = (double) c;
        }
        return ok;
    }

    /** bool next(int&) @00159870: rounds half away from zero in float. */
    public boolean next(int[] out) {
        int p = pos;
        if (len <= p) {
            return false;
        }
        pos = p + 1;
        float f = data[p];
        if (0.0f <= f) {
            out[0] = cvttss2si(f + 0.5f);
            return true;
        }
        out[0] = cvttss2si(f - 0.5f);
        return true;
    }

    /** bool next(bool&) @001598f0 */
    public boolean next(boolean[] out) {
        int p = pos;
        int n = len;
        if (p < n) {
            pos = p + 1;
            out[0] = data[p] != 0.0f;
        }
        return p < n;
    }

    /** x86 cvttss2si: NaN / out of range gives INT_MIN. */
    static int cvttss2si(float f) {
        if (f != f || f >= 2147483648.0f || f < -2147483648.0f) {
            return Integer.MIN_VALUE;
        }
        return (int) f;
    }

    /** bool fromJava(float*, int) @00159940 */
    public boolean fromJava(float[] f, int n) {
        data = f;
        len = n;
        pos = 0;
        double[] d = new double[1];
        int[] iv = new int[1];
        boolean[] bv = new boolean[1];
        if (!next(d)) return false;
        modelScale = d[0];
        if (!next(d)) return false;
        mass = d[0];
        if (!next(d)) return false;
        rollInfluence = d[0];
        if (!next(d)) return false;
        suspensionStiffness = d[0];
        if (!next(d)) return false;
        suspensionCompression = d[0];
        if (!next(d)) return false;
        suspensionDamping = d[0];
        if (!next(d)) return false;
        maxSuspensionTravelCm = d[0];
        if (!next(d)) return false;
        suspensionRestLength = d[0];
        if (!next(d)) return false;
        wheelFriction = d[0];
        if (!next(d)) return false;
        stoppingMovementForce = d[0];
        if (!next(iv)) return false;
        wheelCount = iv[0];
        // NOTE: C++ has no bound check; wheelCount > 8 overruns into wheelCount/data (UB, not
        // reproduced).
        for (int i = 0; i < wheelCount; i++) {
            Wheel w = wheels[i];
            if (!next(bv)) return false;
            w.front = bv[0];
            if (!next(w.offset)) return false;
            if (!next(d)) return false;
            w.radius = d[0];
        }
        if (!next(iv)) return false;
        shapeCount = iv[0];
        if (shapeCount < 1) {
            return true;
        }
        // NOTE: C++ has no bound check; shapeCount > 10 overruns into shapeCount/wheels (UB, not
        // reproduced).
        int i = 0;
        while (true) {
            Shape s = shapes[i];
            if (!next(iv)) return false;
            s.type = iv[0];
            if (!next(s.offset)) return false;
            if (s.type == 1) {
                if (!next(s.extents)) return false;
                if (!next(s.rotate)) return false;
            }
            if (s.type == 2) {
                if (!next(d)) return false;
                s.radius = d[0];
            }
            i++;
            if (shapeCount <= i) {
                return true;
            }
        }
    }

    /** static PZVehicleScript* fromJava(char const*, float*, int) @0015b900 */
    public static PZVehicleScript fromJava(String name, float[] f, int n) {
        PZVehicleScript script = findScript(name);
        if (script == null) {
            script = new PZVehicleScript();
            if (!script.fromJava(f, n)) {
                PZDebugLog.instance()
                        .logf(
                                "PZBullet",
                                PZDebugType.Error,
                                "/usr/src/pz/pzbullet/PZVehicle.cpp:70",
                                "%s:%d ERROR\n",
                                "/usr/src/pz/pzbullet/PZVehicle.cpp",
                                70);
                return null;
            }
            script.name = name;
            m_scripts.add(script);
            return script;
        }
        // existing script is re-parsed in place; returns NULL even on success (confirmed asm
        // 0x5bd98)
        if (!script.fromJava(f, n)) {
            PZDebugLog.instance()
                    .logf(
                            "PZBullet",
                            PZDebugType.Error,
                            "/usr/src/pz/pzbullet/PZVehicle.cpp:77",
                            "%s:%d ERROR\n",
                            "/usr/src/pz/pzbullet/PZVehicle.cpp",
                            77);
        }
        return null;
    }

    /** static PZVehicleScript* findScript(char const*) @00159b60 (linear, std::string::compare) */
    public static PZVehicleScript findScript(String name) {
        int n = m_scripts.size();
        for (int i = 0; i < n; i++) {
            PZVehicleScript s = m_scripts.get(i);
            if (s.name.equals(name)) {
                return s;
            }
        }
        return null;
    }

    /**
     * definePhysicsMesh(int shapeIndex, int numPoints, float* points) @001594b0. Sets the shape
     * type to 3 and APPENDS numPoints*3 widened floats to the shape's mesh (the vector is not
     * cleared).
     */
    public int definePhysicsMesh(int shapeIndex, int numPoints, float[] points) {
        if (shapeIndex < 0) {
            return 0;
        }
        if (shapeCount <= shapeIndex) {
            return 0;
        }
        int n = numPoints * 3;
        Shape s = shapes[shapeIndex];
        s.type = 3;
        if (n < 0) {
            // (size_t)n > max_size(): C++ throws std::length_error("vector::reserve")
            throw new RuntimeException("vector::reserve");
        }
        s.mesh.ensureCapacity(s.mesh.size() + n);
        for (int i = 0; i < n; i++) {
            s.mesh.add((double) points[i]);
        }
        return 0;
    }
}
