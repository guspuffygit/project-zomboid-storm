// Port of btPolyhedralConvexShape.cpp (Bullet 2.82) class btPolyhedralConvexAabbCachingShape
package io.pzstorm.storm.bullet.collision.shapes;

import io.pzstorm.storm.bullet.linearmath.btAabbUtil2;
import io.pzstorm.storm.bullet.linearmath.btTransform;
import io.pzstorm.storm.bullet.linearmath.btVector3;

public abstract class btPolyhedralConvexAabbCachingShape extends btPolyhedralConvexShape {
    public final btVector3 m_localAabbMin = new btVector3(1, 1, 1);
    public final btVector3 m_localAabbMax = new btVector3(-1, -1, -1);
    public boolean m_isLocalAabbValid = false;

    public btPolyhedralConvexAabbCachingShape() {
        super();
    }

    protected void setCachedLocalAabb(btVector3 aabbMin, btVector3 aabbMax) {
        m_isLocalAabbValid = true;
        m_localAabbMin.set(aabbMin);
        m_localAabbMax.set(aabbMax);
    }

    protected void getCachedLocalAabb(btVector3 aabbMin, btVector3 aabbMax) {
        aabbMin.set(m_localAabbMin);
        aabbMax.set(m_localAabbMax);
    }

    public void getNonvirtualAabb(
            btTransform trans, btVector3 aabbMin, btVector3 aabbMax, double margin) {
        btAabbUtil2.btTransformAabb(
                m_localAabbMin, m_localAabbMax, margin, trans, aabbMin, aabbMax);
    }

    @Override
    public void setLocalScaling(btVector3 scaling) {
        super.setLocalScaling(scaling);
        recalcLocalAabb();
    }

    @Override
    public void getAabb(btTransform trans, btVector3 aabbMin, btVector3 aabbMax) {
        getNonvirtualAabb(trans, aabbMin, aabbMax, getMargin());
    }

    public void recalcLocalAabb() {
        m_isLocalAabbValid = true;

        btVector3[] _directions = {
            new btVector3(1., 0., 0.),
            new btVector3(0., 1., 0.),
            new btVector3(0., 0., 1.),
            new btVector3(-1., 0., 0.),
            new btVector3(0., -1., 0.),
            new btVector3(0., 0., -1.)
        };

        btVector3[] _supporting = {
            new btVector3(0., 0., 0.),
            new btVector3(0., 0., 0.),
            new btVector3(0., 0., 0.),
            new btVector3(0., 0., 0.),
            new btVector3(0., 0., 0.),
            new btVector3(0., 0., 0.)
        };

        batchedUnitVectorGetSupportingVertexWithoutMargin(_directions, _supporting, 6);

        for (int i = 0; i < 3; ++i) {
            m_localAabbMax.set(i, _supporting[i].get(i) + m_collisionMargin);
            m_localAabbMin.set(i, _supporting[i + 3].get(i) - m_collisionMargin);
        }
    }
}
