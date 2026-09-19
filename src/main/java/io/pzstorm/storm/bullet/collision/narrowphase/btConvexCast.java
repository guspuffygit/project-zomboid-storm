// Port of BulletCollision/NarrowPhaseCollision/btConvexCast.cpp (Bullet 2.82)
package io.pzstorm.storm.bullet.collision.narrowphase;

import io.pzstorm.storm.bullet.linearmath.btIDebugDraw;
import io.pzstorm.storm.bullet.linearmath.btScalar;
import io.pzstorm.storm.bullet.linearmath.btTransform;
import io.pzstorm.storm.bullet.linearmath.btVector3;

/** btConvexCast is an interface for Casting */
public abstract class btConvexCast {

    /** RayResult stores the closest result */
    public static class CastResult {
        public void DebugDraw(double fraction) {}

        public void drawCoordSystem(btTransform trans) {}

        public void reportFailure(int errNo, int numIterations) {}

        public CastResult() {
            m_fraction = btScalar.BT_LARGE_FLOAT;
            m_debugDrawer = null;
            m_allowedPenetration = 0.0;
        }

        public final btTransform m_hitTransformA = new btTransform();
        public final btTransform m_hitTransformB = new btTransform();
        public final btVector3 m_normal = new btVector3();
        public final btVector3 m_hitPoint = new btVector3();
        public double m_fraction; // input and output
        public btIDebugDraw m_debugDrawer;
        public double m_allowedPenetration;
    }

    /** cast a convex against another convex object */
    public abstract boolean calcTimeOfImpact(
            btTransform fromA,
            btTransform toA,
            btTransform fromB,
            btTransform toB,
            CastResult result);
}
