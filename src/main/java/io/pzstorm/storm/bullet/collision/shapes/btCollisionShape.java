// Port of btCollisionShape.cpp (Bullet 2.82)
package io.pzstorm.storm.bullet.collision.shapes;

import io.pzstorm.storm.bullet.collision.broadphase.BroadphaseNativeTypes;
import io.pzstorm.storm.bullet.collision.broadphase.btBroadphaseProxy;
import io.pzstorm.storm.bullet.linearmath.btTransform;
import io.pzstorm.storm.bullet.linearmath.btVector3;

/**
 * Serialization (calculateSerializeBufferSize / serialize / serializeSingleShape) is linked in the
 * binary but never called by PZ code; not ported (see docs/re-bullet/shapes.md).
 */
public abstract class btCollisionShape {
    public int m_shapeType;
    public Object m_userPointer;

    public btCollisionShape() {
        m_shapeType = BroadphaseNativeTypes.INVALID_SHAPE_PROXYTYPE;
        m_userPointer = null;
    }

    public abstract void getAabb(btTransform t, btVector3 aabbMin, btVector3 aabbMax);

    /** {@code getBoundingSphere(btVector3& center, btScalar& radius)}; radius is radius[0]. */
    public void getBoundingSphere(btVector3 center, double[] radius) {
        btTransform tr = new btTransform();
        tr.setIdentity();
        btVector3 aabbMin = new btVector3(), aabbMax = new btVector3();

        getAabb(tr, aabbMin, aabbMax);

        radius[0] = (aabbMax.sub(aabbMin)).length() * 0.5;
        center.set((aabbMin.add(aabbMax)).mul(0.5));
    }

    public double getAngularMotionDisc() {
        btVector3 center = new btVector3();
        double[] disc = new double[1];
        getBoundingSphere(center, disc);
        disc[0] += (center).length();
        return disc[0];
    }

    public double getContactBreakingThreshold(double defaultContactThreshold) {
        return getAngularMotionDisc() * defaultContactThreshold;
    }

    public void calculateTemporalAabb(
            btTransform curTrans,
            btVector3 linvel,
            btVector3 angvel,
            double timeStep,
            btVector3 temporalAabbMin,
            btVector3 temporalAabbMax) {
        getAabb(curTrans, temporalAabbMin, temporalAabbMax);

        double temporalAabbMaxx = temporalAabbMax.getX();
        double temporalAabbMaxy = temporalAabbMax.getY();
        double temporalAabbMaxz = temporalAabbMax.getZ();
        double temporalAabbMinx = temporalAabbMin.getX();
        double temporalAabbMiny = temporalAabbMin.getY();
        double temporalAabbMinz = temporalAabbMin.getZ();

        btVector3 linMotion = linvel.mul(timeStep);
        if (linMotion.x() > 0.0) temporalAabbMaxx += linMotion.x();
        else temporalAabbMinx += linMotion.x();
        if (linMotion.y() > 0.0) temporalAabbMaxy += linMotion.y();
        else temporalAabbMiny += linMotion.y();
        if (linMotion.z() > 0.0) temporalAabbMaxz += linMotion.z();
        else temporalAabbMinz += linMotion.z();

        double angularMotion = angvel.length() * getAngularMotionDisc() * timeStep;
        btVector3 angularMotion3d = new btVector3(angularMotion, angularMotion, angularMotion);
        temporalAabbMin.set(new btVector3(temporalAabbMinx, temporalAabbMiny, temporalAabbMinz));
        temporalAabbMax.set(new btVector3(temporalAabbMaxx, temporalAabbMaxy, temporalAabbMaxz));

        temporalAabbMin.subLocal(angularMotion3d);
        temporalAabbMax.addLocal(angularMotion3d);
    }

    public boolean isPolyhedral() {
        return btBroadphaseProxy.isPolyhedral(getShapeType());
    }

    public boolean isConvex2d() {
        return btBroadphaseProxy.isConvex2d(getShapeType());
    }

    public boolean isConvex() {
        return btBroadphaseProxy.isConvex(getShapeType());
    }

    public boolean isNonMoving() {
        return btBroadphaseProxy.isNonMoving(getShapeType());
    }

    public boolean isConcave() {
        return btBroadphaseProxy.isConcave(getShapeType());
    }

    public boolean isCompound() {
        return btBroadphaseProxy.isCompound(getShapeType());
    }

    public boolean isSoftBody() {
        return btBroadphaseProxy.isSoftBody(getShapeType());
    }

    public boolean isInfinite() {
        return btBroadphaseProxy.isInfinite(getShapeType());
    }

    public abstract void setLocalScaling(btVector3 scaling);

    public abstract btVector3 getLocalScaling();

    public abstract void calculateLocalInertia(double mass, btVector3 inertia);

    public abstract String getName();

    public int getShapeType() {
        return m_shapeType;
    }

    public btVector3 getAnisotropicRollingFrictionDirection() {
        return new btVector3(1, 1, 1);
    }

    public abstract void setMargin(double margin);

    public abstract double getMargin();

    public void setUserPointer(Object userPtr) {
        m_userPointer = userPtr;
    }

    public Object getUserPointer() {
        return m_userPointer;
    }
}
