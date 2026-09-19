// Port of BulletCollision/NarrowPhaseCollision/btDiscreteCollisionDetectorInterface.h struct
// btStorageResult (Bullet 2.82)
package io.pzstorm.storm.bullet.collision.narrowphase;

import io.pzstorm.storm.bullet.linearmath.btScalar;
import io.pzstorm.storm.bullet.linearmath.btVector3;

public abstract class btStorageResult extends btDiscreteCollisionDetectorInterface.Result {
    public final btVector3 m_normalOnSurfaceB = new btVector3();
    public final btVector3 m_closestPointInB = new btVector3();
    public double m_distance; // negative means penetration !

    public btStorageResult() {
        m_distance = btScalar.BT_LARGE_FLOAT;
    }

    @Override
    public void addContactPoint(btVector3 normalOnBInWorld, btVector3 pointInWorld, double depth) {
        if (depth < m_distance) {
            m_normalOnSurfaceB.set(normalOnBInWorld);
            m_closestPointInB.set(pointInWorld);
            m_distance = depth;
        }
    }
}
