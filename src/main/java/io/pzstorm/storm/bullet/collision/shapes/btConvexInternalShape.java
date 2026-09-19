// Port of btConvexInternalShape.cpp (Bullet 2.82)
package io.pzstorm.storm.bullet.collision.shapes;

import io.pzstorm.storm.bullet.linearmath.btMatrix3x3;
import io.pzstorm.storm.bullet.linearmath.btScalar;
import io.pzstorm.storm.bullet.linearmath.btTransform;
import io.pzstorm.storm.bullet.linearmath.btVector3;

public abstract class btConvexInternalShape extends btConvexShape {
    public final btVector3 m_localScaling = new btVector3();

    /** Uninitialized in C++ (every linked subclass ctor writes it); zero here. */
    public final btVector3 m_implicitShapeDimensions = new btVector3();

    public double m_collisionMargin;
    public double m_padding;

    protected btConvexInternalShape() {
        m_localScaling.setValue(1.0, 1.0, 1.0);
        m_collisionMargin = btCollisionMargin.CONVEX_DISTANCE_MARGIN;
    }

    @Override
    public btVector3 localGetSupportingVertex(btVector3 vec) {
        btVector3 supVertex = localGetSupportingVertexWithoutMargin(vec);

        if (getMargin() != 0.0) {
            btVector3 vecnorm = new btVector3(vec);
            if (vecnorm.length2() < (btScalar.SIMD_EPSILON * btScalar.SIMD_EPSILON)) {
                vecnorm.setValue(-1.0, -1.0, -1.0);
            }
            vecnorm.normalize();
            supVertex.addLocal(vecnorm.mul(getMargin()));
        }
        return supVertex;
    }

    public btVector3 getImplicitShapeDimensions() {
        return m_implicitShapeDimensions;
    }

    public void setImplicitShapeDimensions(btVector3 dimensions) {
        m_implicitShapeDimensions.set(dimensions);
    }

    public void setSafeMargin(double minDimension) {
        setSafeMargin(minDimension, (double) 0.1f);
    }

    public void setSafeMargin(double minDimension, double defaultMarginMultiplier) {
        double safeMargin = defaultMarginMultiplier * minDimension;
        if (safeMargin < getMargin()) {
            setMargin(safeMargin);
        }
    }

    public void setSafeMargin(btVector3 halfExtents) {
        setSafeMargin(halfExtents, (double) 0.1f);
    }

    public void setSafeMargin(btVector3 halfExtents, double defaultMarginMultiplier) {
        double minDimension = halfExtents.get(halfExtents.minAxis());
        setSafeMargin(minDimension, defaultMarginMultiplier);
    }

    @Override
    public void getAabb(btTransform t, btVector3 aabbMin, btVector3 aabbMax) {
        getAabbSlow(t, aabbMin, aabbMax);
    }

    @Override
    public void getAabbSlow(btTransform trans, btVector3 minAabb, btVector3 maxAabb) {
        double margin = getMargin();
        for (int i = 0; i < 3; i++) {
            btVector3 vec = new btVector3(0.0, 0.0, 0.0);
            vec.set(i, 1.0);

            btVector3 sv = localGetSupportingVertex(btMatrix3x3.mul(vec, trans.getBasis()));

            btVector3 tmp = trans.transform(sv);
            maxAabb.set(i, tmp.get(i) + margin);
            vec.set(i, -1.0);
            tmp = trans.transform(localGetSupportingVertex(btMatrix3x3.mul(vec, trans.getBasis())));
            minAabb.set(i, tmp.get(i) - margin);
        }
    }

    @Override
    public void setLocalScaling(btVector3 scaling) {
        m_localScaling.set(scaling.absolute());
    }

    @Override
    public btVector3 getLocalScaling() {
        return m_localScaling;
    }

    public btVector3 getLocalScalingNV() {
        return m_localScaling;
    }

    @Override
    public void setMargin(double margin) {
        m_collisionMargin = margin;
    }

    @Override
    public double getMargin() {
        return m_collisionMargin;
    }

    public double getMarginNV() {
        return m_collisionMargin;
    }

    @Override
    public int getNumPreferredPenetrationDirections() {
        return 0;
    }

    @Override
    public void getPreferredPenetrationDirection(int index, btVector3 penetrationVector) {}
}
