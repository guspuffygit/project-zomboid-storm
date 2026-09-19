// Port of PZ glue PZBullet (PZBullet.cpp): Instance @00156260, Init @00156270, GetJNIEnv
// @00156330, initClass @00156360, initStaticMethod @00156400. Static init
// (_GLOBAL__sub_I_PZBullet.cpp @001484b0) zeroes {@link #hack_vehicles}.
package io.pzstorm.storm.bullet.pz;

import java.util.ArrayList;

/**
 * JNI bookkeeping singleton. The Java port has no JavaVM/JNIEnv: {@link #Init()} only keeps the
 * "initialised"/"success" flags (+0xc/+0xd). Class and static-method lookups resolve the fixed
 * upcall targets in {@link io.pzstorm.storm.bullet.BulletUpcalls}, which always exist, so they
 * return a non-null token and never take the "Failed to find ..." log path (kept for fidelity).
 */
public final class PZBullet {

    private static final PZBullet s_instance = new PZBullet();

    /**
     * Global {@code std::vector<PZVehicle*> hack_vehicles} (PZBullet.cpp): refilled by
     * getVehicleCount, read by getVehiclePhysics (jni.VehicleNatives).
     */
    public static final ArrayList<PZVehicle> hack_vehicles = new ArrayList<>();

    /** +0xc */
    public boolean m_initialised;

    /** +0xd */
    public boolean m_success;

    private PZBullet() {}

    public static PZBullet Instance() {
        return s_instance;
    }

    /** {@code PZBullet::Init(JNIEnv*)}: GetJavaVM + GetVersion on the first call only. */
    public boolean Init() {
        if (!m_initialised) {
            m_initialised = true;
            m_success = true;
            return true;
        }
        return m_success;
    }

    /** {@code FindClass} + {@code NewGlobalRef}; null (after an Error log) when not found. */
    public Object initClass(String name) {
        Object cls = resolveClass(name);
        if (cls == null) {
            PZDebugLog.instance()
                    .logf(
                            "PZBullet",
                            PZDebugType.Error,
                            "/usr/src/pz/pzbullet/PZBullet.cpp:2128",
                            "Failed to find class: %s",
                            name);
            return null;
        }
        return cls;
    }

    /** {@code GetStaticMethodID}; null (after an Error log) when not found. */
    public Object initStaticMethod(Object cls, String name, String sig) {
        Object m = cls == null ? null : name + sig;
        if (m != null) {
            return m;
        }
        PZDebugLog.instance()
                .logf(
                        "PZBullet",
                        PZDebugType.Error,
                        "/usr/src/pz/pzbullet/PZBullet.cpp:2145",
                        "Failed to find static method: %s %s",
                        name,
                        sig);
        return null;
    }

    private static Object resolveClass(String name) {
        // Only "zombie/debug/DebugLog" is looked up; its upcall is BulletUpcalls.callNativeLog.
        return name;
    }
}
