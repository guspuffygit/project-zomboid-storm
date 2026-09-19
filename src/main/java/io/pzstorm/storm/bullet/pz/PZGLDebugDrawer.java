// Port of PZ glue PZGLDebugDrawer (libPZBullet64.so only — the GL/client build).
package io.pzstorm.storm.bullet.pz;

import io.pzstorm.storm.bullet.linearmath.btTransform;
import io.pzstorm.storm.bullet.linearmath.btVector3;

/**
 * The drawer the GL build's {@code WorldSimulation} embeds at WS+0x68 (0x30 bytes: vptr, {@code
 * m_debugMode} at +8, {@link #m_offset} at +0x10 = WS+0x78). Each primitive upcalls {@code
 * zombie.core.physics.PhysicsDebugRenderer} through {@link PhysicsDebugRenderer#instance}.
 *
 * <p>Every float argument is computed in double ({@code v + m_offset}, or the raw double component)
 * and narrowed to float by the JVM per the method signature — hence {@code (float)(a + b)}.
 * Additions are commutative so the operand order visible in the disassembly does not matter.
 *
 * <p>Overrides (vtable): dtor, {@code drawLine} x2, {@code drawSphere(p, r, color)}, {@code
 * drawTriangle} (5-arg), {@code drawContactPoint}, {@code draw3dText} (empty), {@code drawCapsule}.
 * {@code PZGLDebugDrawer::drawCapsule} (0x18ed10) is instruction-equivalent to {@code
 * btIDebugDraw::drawCapsule} (only register/stack-slot allocation differs), so it is ported as the
 * base default; the Java {@code drawCapsule} upcall is never used.
 *
 * <p>The ctor (0x91010) runs {@code GLDebugDrawer()} then sets the vptr; {@link #m_offset} is left
 * uninitialised (heap garbage in C++, zero here) until a {@code PhysicsDebugRenderer} render entry
 * sets it.
 */
public class PZGLDebugDrawer extends GLDebugDrawer {

    /** +0x10: draw offset added to every position ({@code (ddwX, 0, ddwY, 0)}). */
    public final btVector3 m_offset = new btVector3();

    public PZGLDebugDrawer() {
        super();
    }

    @Override
    public void drawLine(btVector3 from, btVector3 to, btVector3 color) {
        PhysicsDebugRenderer.drawLine(
                (float) (from.x + m_offset.x),
                (float) (from.y + m_offset.y),
                (float) (from.z + m_offset.z),
                (float) (to.x + m_offset.x),
                (float) (to.y + m_offset.y),
                (float) (to.z + m_offset.z),
                (float) color.x,
                (float) color.y,
                (float) color.z,
                (float) color.x,
                (float) color.y,
                (float) color.z);
    }

    @Override
    public void drawLine(btVector3 from, btVector3 to, btVector3 fromColor, btVector3 toColor) {
        PhysicsDebugRenderer.drawLine(
                (float) (from.x + m_offset.x),
                (float) (from.y + m_offset.y),
                (float) (from.z + m_offset.z),
                (float) (to.x + m_offset.x),
                (float) (to.y + m_offset.y),
                (float) (to.z + m_offset.z),
                (float) fromColor.x,
                (float) fromColor.y,
                (float) fromColor.z,
                (float) toColor.x,
                (float) toColor.y,
                (float) toColor.z);
    }

    @Override
    public void drawSphere(btVector3 p, double radius, btVector3 color) {
        PhysicsDebugRenderer.drawSphere(
                (float) (p.x + m_offset.x),
                (float) (p.y + m_offset.y),
                (float) (p.z + m_offset.z),
                (float) radius,
                (float) color.x,
                (float) color.y,
                (float) color.z);
    }

    @Override
    public void drawTriangle(btVector3 a, btVector3 b, btVector3 c, btVector3 color, double alpha) {
        PhysicsDebugRenderer.drawTriangle(
                (float) (a.x + m_offset.x),
                (float) (a.y + m_offset.y),
                (float) (a.z + m_offset.z),
                (float) (b.x + m_offset.x),
                (float) (b.y + m_offset.y),
                (float) (b.z + m_offset.z),
                (float) (c.x + m_offset.x),
                (float) (c.y + m_offset.y),
                (float) (c.z + m_offset.z),
                (float) color.x,
                (float) color.y,
                (float) color.z,
                (float) alpha);
    }

    @Override
    public void drawContactPoint(
            btVector3 PointOnB,
            btVector3 normalOnB,
            double distance,
            int lifeTime,
            btVector3 color) {
        PhysicsDebugRenderer.drawContactPoint(
                (float) (PointOnB.x + m_offset.x),
                (float) (PointOnB.y + m_offset.y),
                (float) (PointOnB.z + m_offset.z),
                (float) normalOnB.x,
                (float) normalOnB.y,
                (float) normalOnB.z,
                (float) distance,
                lifeTime,
                (float) color.x,
                (float) color.y,
                (float) color.z);
    }

    @Override
    public void draw3dText(btVector3 location, String textString) {}

    /** Equivalent to {@code btIDebugDraw::drawCapsule} (see class doc); no log, no upcall. */
    @Override
    public void drawCapsule(
            double radius, double halfHeight, int upAxis, btTransform transform, btVector3 color) {
        btIDebugDraw_drawCapsule(radius, halfHeight, upAxis, transform, color);
    }
}
