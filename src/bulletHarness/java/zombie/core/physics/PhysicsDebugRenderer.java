package zombie.core.physics;

import io.pzstorm.storm.bullet.trace.scenario.DebugDrawProbe;
import java.util.function.Consumer;

/**
 * HARNESS STUB of the game's {@code zombie.core.physics.PhysicsDebugRenderer}: the five instance
 * natives {@code libPZBullet64.so} binds, and the five upcall targets its {@code GetMethodID}
 * resolves (same names and signatures), each logging one {@link DebugDrawProbe#line} to {@link
 * #out}. Exists only in the bulletHarness source set.
 */
public class PhysicsDebugRenderer {
    public static Consumer<String> out;

    public void drawLine(
            float a,
            float b,
            float c,
            float d,
            float e,
            float f,
            float g,
            float h,
            float i,
            float j,
            float k,
            float l) {
        out.accept(DebugDrawProbe.line("drawLine", a, b, c, d, e, f, g, h, i, j, k, l));
    }

    public void drawSphere(float a, float b, float c, float d, float e, float f, float g) {
        out.accept(DebugDrawProbe.line("drawSphere", a, b, c, d, e, f, g));
    }

    public void drawTriangle(
            float a,
            float b,
            float c,
            float d,
            float e,
            float f,
            float g,
            float h,
            float i,
            float j,
            float k,
            float l,
            float m) {
        out.accept(DebugDrawProbe.line("drawTriangle", a, b, c, d, e, f, g, h, i, j, k, l, m));
    }

    public void drawContactPoint(
            float a,
            float b,
            float c,
            float d,
            float e,
            float f,
            float g,
            int h,
            float i,
            float j,
            float k) {
        out.accept(DebugDrawProbe.line("drawContactPoint", a, b, c, d, e, f, g, h, i, j, k));
    }

    public void drawCapsule(
            float a,
            float b,
            int c,
            float d,
            float e,
            float f,
            float g,
            float h,
            float i,
            float j,
            float k,
            float l,
            float m,
            float n) {
        out.accept(DebugDrawProbe.line("drawCapsule", a, b, c, d, e, f, g, h, i, j, k, l, m, n));
    }

    public native void n_debugDrawWorld(int a, int b, int c, int d);

    public native void renderRagdoll(int a, int b, int c);

    public native void renderBallistics(int a, int b, int c);

    public native void renderBallisticsTarget(int a, int b, int c);

    public native void renderVehicle(int a, int b, int c);
}
