// Port of PZ glue GLDebugDrawer (libPZBullet64.so only — the GL/client build;
// /usr/src/pz/pzbullet/GLDebugDrawer.cpp).
package io.pzstorm.storm.bullet.pz;

import io.pzstorm.storm.bullet.linearmath.btIDebugDraw;
import io.pzstorm.storm.bullet.linearmath.btTransform;
import io.pzstorm.storm.bullet.linearmath.btVector3;

/**
 * The GL build's base debug drawer. Every primitive either logs ("IS USED!, but calls nothing") or
 * forwards to another virtual; nothing is drawn here. {@link PZGLDebugDrawer} overrides the
 * primitives that reach Java.
 *
 * <p>Vtable (2.82 order) overrides: dtor, {@code drawLine} x2, {@code drawSphere(p, r, color)},
 * {@code drawTriangle} (5-arg), {@code drawContactPoint} (empty), {@code reportErrorWarning}
 * (empty), {@code draw3dText} (empty), {@code setDebugMode}/{@code getDebugMode} (field at +8),
 * {@code drawCapsule}. Everything else is the {@link btIDebugDraw} default.
 *
 * <p>The {@code __onceGLDebugDraw} flags are C++ function-local statics: one per function, shared
 * by every instance. The 3-arg {@code drawLine} contains a speculatively devirtualised inline copy
 * of the 4-arg body (taken when the vtable slot is {@code GLDebugDrawer::drawLine}); it shares the
 * 4-arg function's flag and is semantically identical to the virtual call.
 */
public class GLDebugDrawer extends btIDebugDraw {

    static final String FILE = "/usr/src/pz/pzbullet/GLDebugDrawer.cpp:";

    /**
     * {@code drawLine(const btVector3&, const btVector3&, const btVector3&, const
     * btVector3&)::__onceGLDebugDraw} (0x1593c0).
     */
    public static boolean drawLine4_onceGLDebugDraw;

    /**
     * {@code drawLine(const btVector3&, const btVector3&, const btVector3&)::__onceGLDebugDraw}.
     */
    public static boolean drawLine3_onceGLDebugDraw;

    /**
     * {@code drawCapsule(double, double, int, const btTransform&, const
     * btVector3&)::__onceGLDebugDraw} (0x1593c2).
     */
    public static boolean drawCapsule_onceGLDebugDraw;

    /** +8 */
    public int m_debugMode;

    public GLDebugDrawer() {
        m_debugMode = 0;
    }

    static void log(int line, String msg) {
        PZDebugLog.instance().log("PZBullet", PZDebugType.Debug, FILE + line, msg);
    }

    @Override
    public void drawLine(btVector3 from, btVector3 to, btVector3 fromColor, btVector3 toColor) {
        if (drawLine4_onceGLDebugDraw) {
            drawLine4_onceGLDebugDraw = true;
            return;
        }
        log(17, "GLDebugDrawer::drawLine - IS USED!, but calls nothing");
        drawLine4_onceGLDebugDraw = true;
    }

    @Override
    public void drawLine(btVector3 from, btVector3 to, btVector3 color) {
        if (!drawLine3_onceGLDebugDraw) {
            log(23, "GLDebugDrawer::onceDrawLine00");
        }
        // local btVector3(1,0,0) passed as both colours (the incoming colour is ignored).
        btVector3 red = new btVector3(1.0, 0.0, 0.0);
        drawLine(from, to, red, red);
        drawLine3_onceGLDebugDraw = true;
    }

    @Override
    public void drawSphere(btVector3 p, double radius, btVector3 color) {
        log(30, "GLDebugDrawer::drawSphere - IS USED!, but calls nothing");
    }

    @Override
    public void drawTriangle(btVector3 a, btVector3 b, btVector3 c, btVector3 color, double alpha) {
        log(43, "GLDebugDrawer::drawTriangle - IS USED!, but calls nothing");
    }

    @Override
    public void drawContactPoint(
            btVector3 PointOnB,
            btVector3 normalOnB,
            double distance,
            int lifeTime,
            btVector3 color) {}

    @Override
    public void reportErrorWarning(String warningString) {}

    @Override
    public void draw3dText(btVector3 location, String textString) {}

    @Override
    public void setDebugMode(int debugMode) {
        m_debugMode = debugMode;
    }

    @Override
    public int getDebugMode() {
        return m_debugMode;
    }

    /**
     * Logs once, then calls the non-virtual {@code btIDebugDraw::drawCapsule} with a local colour
     * {@code (0,1,1)} — the caller's colour is ignored (0x88750).
     */
    @Override
    public void drawCapsule(
            double radius, double halfHeight, int upAxis, btTransform transform, btVector3 color) {
        if (!drawCapsule_onceGLDebugDraw) {
            log(36, "GLDebugDrawer::drawCapsule");
        }
        btVector3 cyan = new btVector3(0.0, 1.0, 1.0);
        super.drawCapsule(radius, halfHeight, upAxis, transform, cyan);
        drawCapsule_onceGLDebugDraw = true;
    }

    /**
     * Qualified call {@code btIDebugDraw::drawCapsule(...)} for subclasses (Java cannot name a
     * grandparent's implementation directly).
     */
    protected final void btIDebugDraw_drawCapsule(
            double radius, double halfHeight, int upAxis, btTransform transform, btVector3 color) {
        super.drawCapsule(radius, halfHeight, upAxis, transform, color);
    }
}
