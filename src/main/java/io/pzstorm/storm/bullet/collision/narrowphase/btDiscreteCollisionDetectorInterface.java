// Port of BulletCollision/NarrowPhaseCollision/btDiscreteCollisionDetectorInterface.h (Bullet 2.82)
package io.pzstorm.storm.bullet.collision.narrowphase;

import io.pzstorm.storm.bullet.linearmath.btIDebugDraw;
import io.pzstorm.storm.bullet.linearmath.btScalar;
import io.pzstorm.storm.bullet.linearmath.btTransform;
import io.pzstorm.storm.bullet.linearmath.btVector3;

/**
 * This interface is made to be used by an iterative approach to do TimeOfImpact calculations. The
 * closest point is on the second object (B), and the normal points from the surface on B towards A.
 */
public abstract class btDiscreteCollisionDetectorInterface {

    public abstract static class Result {
        /**
         * setShapeIdentifiersA/B provides experimental support for per-triangle material / custom
         * material combiner
         */
        public abstract void setShapeIdentifiersA(int partId0, int index0);

        public abstract void setShapeIdentifiersB(int partId1, int index1);

        public abstract void addContactPoint(
                btVector3 normalOnBInWorld, btVector3 pointInWorld, double depth);
    }

    public static class ClosestPointInput {
        public final btTransform m_transformA = new btTransform();
        public final btTransform m_transformB = new btTransform();
        public double m_maximumDistanceSquared;

        public ClosestPointInput() {
            m_maximumDistanceSquared = btScalar.BT_LARGE_FLOAT;
        }
    }

    // give either closest points (distance > 0) or penetration (distance)
    // the normal always points from B towards A
    public abstract void getClosestPoints(
            ClosestPointInput input, Result output, btIDebugDraw debugDraw, boolean swapResults);

    /** Default argument {@code swapResults=false}. */
    public void getClosestPoints(ClosestPointInput input, Result output, btIDebugDraw debugDraw) {
        getClosestPoints(input, output, debugDraw, false);
    }
}
