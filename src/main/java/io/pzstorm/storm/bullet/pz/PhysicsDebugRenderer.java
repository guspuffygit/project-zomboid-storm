// Port of PZ glue zombie_core_physics_PhysicsDebugRenderer.cpp's static state (libPZBullet64.so
// only).
package io.pzstorm.storm.bullet.pz;

/**
 * Mirror of the native {@code PhysicsDebugRenderer::instance} — a static pointer to a 0x48-byte
 * struct holding the JNI state the drawer upcalls through:
 *
 * <pre>
 * +0x00 JNIEnv*          (stored on every JNI render entry)      -> hasEnv
 * +0x08 jobject self     (stored on every JNI render entry)      -> obj
 * +0x10 jclass global ref of GetObjectClass(self) (first init)   -> clazz
 * +0x18 bool init attempted                                      -> initAttempted
 * +0x19 bool init succeeded                                      -> initOk
 * +0x20 jmethodID drawLine          (FFFFFFFFFFFF)V              -> mid_drawLine
 * +0x28 jmethodID drawSphere        (FFFFFFF)V                   -> mid_drawSphere
 * +0x30 jmethodID drawTriangle      (FFFFFFFFFFFFF)V             -> mid_drawTriangle
 * +0x38 jmethodID drawContactPoint  (FFFFFFFIFFF)V               -> mid_drawContactPoint
 * +0x40 jmethodID drawCapsule       (FFIFFFFFFFFFFF)V            -> mid_drawCapsule
 * </pre>
 *
 * <p>A jmethodID is modelled as "resolved" ({@code true}); the call itself goes through the {@link
 * DrawSink} (default: {@link GameSink}, the real {@code zombie.core.physics.PhysicsDebugRenderer}).
 * Tests swap {@link #sink} to capture the exact upcall sequence.
 *
 * <p>Native crash sites (a drawer primitive reached while {@code instance} is null — the native
 * allocates a zeroed struct and calls {@code CallVoidMethod} through a NULL {@code JNIEnv*} — or
 * through an unresolved (zero) jmethodID) throw {@link IllegalStateException} here instead.
 */
public final class PhysicsDebugRenderer {

    /**
     * Receiver of the five Java upcalls (the {@code CallVoidMethod} targets) plus the {@code
     * GetMethodID} probe.
     */
    public interface DrawSink {
        /**
         * {@code GetMethodID(clazz, name, sig)}: true if resolvable. On false the native returns
         * with the JVM's pending {@code NoSuchMethodError}; the caller throws that.
         */
        boolean getMethodID(Class<?> clazz, String name, String sig);

        void drawLine(
                Object obj,
                float fromX,
                float fromY,
                float fromZ,
                float toX,
                float toY,
                float toZ,
                float fromR,
                float fromG,
                float fromB,
                float toR,
                float toG,
                float toB);

        void drawSphere(
                Object obj, float pX, float pY, float pZ, float radius, float r, float g, float b);

        void drawTriangle(
                Object obj,
                float aX,
                float aY,
                float aZ,
                float bX,
                float bY,
                float bZ,
                float cX,
                float cY,
                float cZ,
                float r,
                float g,
                float b,
                float alpha);

        void drawContactPoint(
                Object obj,
                float pointOnBX,
                float pointOnBY,
                float pointOnBZ,
                float normalOnBX,
                float normalOnBY,
                float normalOnBZ,
                float distance,
                int lifeTime,
                float r,
                float g,
                float b);

        void drawCapsule(
                Object obj,
                float radius,
                float halfHeight,
                int upAxis,
                float pX,
                float pY,
                float pZ,
                float rX,
                float rY,
                float rZ,
                float qZ,
                float r,
                float g,
                float b,
                float a);
    }

    /** {@code PhysicsDebugRenderer::instance} (0x1593c8); null until the first JNI render entry. */
    public static PhysicsDebugRenderer instance;

    /** Where upcalls go. Default: the game's {@code zombie.core.physics.PhysicsDebugRenderer}. */
    public static volatile DrawSink sink = GameSink.INSTANCE;

    public boolean hasEnv;
    public Object obj;
    public Class<?> clazz;
    public boolean initAttempted;
    public boolean initOk;
    public boolean mid_drawLine;
    public boolean mid_drawSphere;
    public boolean mid_drawTriangle;
    public boolean mid_drawContactPoint;
    public boolean mid_drawCapsule;

    /** {@code operator new(0x48)} + zero fill. */
    PhysicsDebugRenderer() {}

    /**
     * The prologue every JNI render entry inlines: allocate {@code instance} if null, store {@code
     * env}/{@code self}, and on the first call resolve the class and the five method IDs in order.
     * Returns false when the entry must return without drawing (this call's lookup failed — which
     * throws {@link NoSuchMethodError} like the JVM's pending exception — or an earlier one did,
     * which returns silently forever).
     */
    public static boolean initInstance(Object self) {
        PhysicsDebugRenderer inst = instance;
        if (inst == null) {
            inst = new PhysicsDebugRenderer();
            instance = inst;
        }
        inst.hasEnv = true;
        inst.obj = self;
        if (inst.initAttempted) {
            return inst.initOk;
        }
        inst.initAttempted = true;
        // GetObjectClass + NewGlobalRef + DeleteLocalRef
        inst.clazz = self.getClass();
        DrawSink s = sink;
        if (!(inst.mid_drawLine = s.getMethodID(inst.clazz, "drawLine", "(FFFFFFFFFFFF)V"))) {
            throw new NoSuchMethodError("drawLine");
        }
        if (!(inst.mid_drawSphere = s.getMethodID(inst.clazz, "drawSphere", "(FFFFFFF)V"))) {
            throw new NoSuchMethodError("drawSphere");
        }
        if (!(inst.mid_drawTriangle =
                s.getMethodID(inst.clazz, "drawTriangle", "(FFFFFFFFFFFFF)V"))) {
            throw new NoSuchMethodError("drawTriangle");
        }
        if (!(inst.mid_drawContactPoint =
                s.getMethodID(inst.clazz, "drawContactPoint", "(FFFFFFFIFFF)V"))) {
            throw new NoSuchMethodError("drawContactPoint");
        }
        if (!(inst.mid_drawCapsule =
                s.getMethodID(inst.clazz, "drawCapsule", "(FFIFFFFFFFFFFF)V"))) {
            throw new NoSuchMethodError("drawCapsule");
        }
        // the entry re-checks instance for null here (redundant; can't be null)
        inst.initOk = true;
        return true;
    }

    /**
     * Drawer-side access: allocates a zeroed struct if null (then the native crashes on the NULL
     * env).
     */
    private static PhysicsDebugRenderer forCall(String name) {
        PhysicsDebugRenderer inst = instance;
        if (inst == null) {
            inst = new PhysicsDebugRenderer();
            instance = inst;
        }
        if (!inst.hasEnv) {
            throw new IllegalStateException(
                    "PhysicsDebugRenderer::instance has no JNIEnv: native "
                            + name
                            + " would crash");
        }
        return inst;
    }

    private static void checkMid(boolean resolved, String name) {
        if (!resolved) {
            throw new IllegalStateException(
                    "PhysicsDebugRenderer jmethodID " + name + " unresolved: native would crash");
        }
    }

    public static void drawLine(
            float fromX,
            float fromY,
            float fromZ,
            float toX,
            float toY,
            float toZ,
            float fromR,
            float fromG,
            float fromB,
            float toR,
            float toG,
            float toB) {
        PhysicsDebugRenderer inst = forCall("drawLine");
        checkMid(inst.mid_drawLine, "drawLine");
        sink.drawLine(
                inst.obj, fromX, fromY, fromZ, toX, toY, toZ, fromR, fromG, fromB, toR, toG, toB);
    }

    public static void drawSphere(
            float pX, float pY, float pZ, float radius, float r, float g, float b) {
        PhysicsDebugRenderer inst = forCall("drawSphere");
        checkMid(inst.mid_drawSphere, "drawSphere");
        sink.drawSphere(inst.obj, pX, pY, pZ, radius, r, g, b);
    }

    public static void drawTriangle(
            float aX,
            float aY,
            float aZ,
            float bX,
            float bY,
            float bZ,
            float cX,
            float cY,
            float cZ,
            float r,
            float g,
            float b,
            float alpha) {
        PhysicsDebugRenderer inst = forCall("drawTriangle");
        checkMid(inst.mid_drawTriangle, "drawTriangle");
        sink.drawTriangle(inst.obj, aX, aY, aZ, bX, bY, bZ, cX, cY, cZ, r, g, b, alpha);
    }

    public static void drawContactPoint(
            float pointOnBX,
            float pointOnBY,
            float pointOnBZ,
            float normalOnBX,
            float normalOnBY,
            float normalOnBZ,
            float distance,
            int lifeTime,
            float r,
            float g,
            float b) {
        PhysicsDebugRenderer inst = forCall("drawContactPoint");
        checkMid(inst.mid_drawContactPoint, "drawContactPoint");
        sink.drawContactPoint(
                inst.obj,
                pointOnBX,
                pointOnBY,
                pointOnBZ,
                normalOnBX,
                normalOnBY,
                normalOnBZ,
                distance,
                lifeTime,
                r,
                g,
                b);
    }

    /**
     * Resets the static state (tests / world teardown). The native never frees {@code instance}.
     */
    public static void resetForTests() {
        instance = null;
        sink = GameSink.INSTANCE;
    }

    /**
     * The real target: {@code zombie.core.physics.PhysicsDebugRenderer}. Kept in its own nested
     * class so nothing else here names the game class (it is only touched when a call runs).
     */
    public static final class GameSink implements DrawSink {
        public static final GameSink INSTANCE = new GameSink();

        private GameSink() {}

        @Override
        public boolean getMethodID(Class<?> clazz, String name, String sig) {
            Class<?>[] params = paramsOf(sig);
            // GetMethodID searches the class and its superclasses, any access level.
            for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
                try {
                    java.lang.reflect.Method m = c.getDeclaredMethod(name, params);
                    if (m.getReturnType() == void.class
                            && !java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                        return true;
                    }
                } catch (NoSuchMethodException ignored) {
                    // keep searching
                }
            }
            return false;
        }

        private static Class<?>[] paramsOf(String sig) {
            String p = sig.substring(1, sig.indexOf(')'));
            Class<?>[] out = new Class<?>[p.length()];
            for (int i = 0; i < p.length(); i++) {
                out[i] = p.charAt(i) == 'I' ? int.class : float.class;
            }
            return out;
        }

        private static zombie.core.physics.PhysicsDebugRenderer r(Object obj) {
            return (zombie.core.physics.PhysicsDebugRenderer) obj;
        }

        @Override
        public void drawLine(
                Object obj,
                float fromX,
                float fromY,
                float fromZ,
                float toX,
                float toY,
                float toZ,
                float fromR,
                float fromG,
                float fromB,
                float toR,
                float toG,
                float toB) {
            r(obj).drawLine(fromX, fromY, fromZ, toX, toY, toZ, fromR, fromG, fromB, toR, toG, toB);
        }

        @Override
        public void drawSphere(
                Object obj, float pX, float pY, float pZ, float radius, float r, float g, float b) {
            r(obj).drawSphere(pX, pY, pZ, radius, r, g, b);
        }

        @Override
        public void drawTriangle(
                Object obj,
                float aX,
                float aY,
                float aZ,
                float bX,
                float bY,
                float bZ,
                float cX,
                float cY,
                float cZ,
                float r,
                float g,
                float b,
                float alpha) {
            r(obj).drawTriangle(aX, aY, aZ, bX, bY, bZ, cX, cY, cZ, r, g, b, alpha);
        }

        @Override
        public void drawContactPoint(
                Object obj,
                float pointOnBX,
                float pointOnBY,
                float pointOnBZ,
                float normalOnBX,
                float normalOnBY,
                float normalOnBZ,
                float distance,
                int lifeTime,
                float r,
                float g,
                float b) {
            r(obj).drawContactPoint(
                            pointOnBX,
                            pointOnBY,
                            pointOnBZ,
                            normalOnBX,
                            normalOnBY,
                            normalOnBZ,
                            distance,
                            lifeTime,
                            r,
                            g,
                            b);
        }

        @Override
        public void drawCapsule(
                Object obj,
                float radius,
                float halfHeight,
                int upAxis,
                float pX,
                float pY,
                float pZ,
                float rX,
                float rY,
                float rZ,
                float qZ,
                float r,
                float g,
                float b,
                float a) {
            r(obj).drawCapsule(radius, halfHeight, upAxis, pX, pY, pZ, rX, rY, rZ, qZ, r, g, b, a);
        }
    }
}
