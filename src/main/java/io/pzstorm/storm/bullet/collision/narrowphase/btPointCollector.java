// Port of BulletCollision/NarrowPhaseCollision/btPointCollector.h (Bullet 2.82)
package io.pzstorm.storm.bullet.collision.narrowphase;

import io.pzstorm.storm.bullet.linearmath.btScalar;
import io.pzstorm.storm.bullet.linearmath.btVector3;

public class btPointCollector extends btDiscreteCollisionDetectorInterface.Result {
    public final btVector3 m_normalOnBInWorld = new btVector3();
    public final btVector3 m_pointInWorld = new btVector3();
    public double m_distance; // negative means penetration
    public boolean m_hasResult;

    public btPointCollector() {
        m_distance = btScalar.BT_LARGE_FLOAT;
        m_hasResult = false;
    }

    @Override
    public void setShapeIdentifiersA(int partId0, int index0) {}

    @Override
    public void setShapeIdentifiersB(int partId1, int index1) {}

    @Override
    public void addContactPoint(btVector3 normalOnBInWorld, btVector3 pointInWorld, double depth) {
        if (depth < m_distance) {
            m_hasResult = true;
            m_normalOnBInWorld.set(normalOnBInWorld);
            m_pointInWorld.set(pointInWorld);
            // negative means penetration
            m_distance = depth;
        }
    }
}
